package com.logisticsmanagement.app

import android.app.UiAutomation
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.model.AccountState
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DouyinLogisticsAutomationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container get() = (context.applicationContext as LogisticsApplication).container
    private val arguments = InstrumentationRegistry.getArguments()

    private fun shell(automation: UiAutomation, command: String): String = DouyinDeviceHarness.shell(automation, command)

    private fun topPackage(automation: UiAutomation): String = DouyinDeviceHarness.topPackage(automation)

    private suspend fun ensureAccessibility(automation: UiAutomation) = DouyinDeviceHarness.ensureAccessibility(automation)

    private suspend fun shipmentsOf(orderId: String) = container.repository.getShipments().filter { shipment ->
        container.repository.getSources(shipment.id).any { it.orderId == orderId }
    }

    private suspend fun awaitService(automation: UiAutomation): DouyinCaptureService =
        DouyinDeviceHarness.awaitService(automation, context, container)

    private fun export(directory: File, captureId: String, automation: UiAutomation) =
        DouyinDeviceHarness.export(directory, captureId, automation, context, container)

    @Test fun importLogisticsAutomatically(): Unit = runBlocking {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val round = arguments.getString("round") ?: "1"
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "s1/round-$round-${System.currentTimeMillis()}")
        check(directory.mkdirs() || directory.isDirectory)
        val service = awaitService(automation)
        DouyinDeviceHarness.launchDouyin(automation, context)
        val account = container.repository.getAccounts().firstOrNull { it.platform == Platform.DOUYIN && !it.deleted }
            ?: container.repository.createAccount(Platform.DOUYIN, "抖音物流采集")
        val settings = container.settings.get()
        container.settings.save(settings.copy(nativeEvidenceVerbose = true))
        DouyinCaptureController.clearOcrEvidence()
        val captureId = "S1R" + round + "T" + System.currentTimeMillis()
        val targetId = arguments.getString("targetOrderId")?.takeIf { it.isNotBlank() } ?: error("缺少 targetOrderId 参数")
        val captureTimeout = arguments.getString("captureTimeoutMillis")?.toLongOrNull() ?: 480_000L
        val expectedTracking = arguments.getString("trackingNumber")?.takeIf { it.isNotBlank() }
        try {
            val beforeOrders = container.repository.getOrders().size
            val beforeTarget = container.repository.getOrders().firstOrNull { it.remoteId == targetId }
            val beforeSources = beforeTarget?.let { order -> shipmentsOf(order.id).flatMap { container.repository.getSources(it.id) }.size } ?: 0
            val beforeEvents = beforeTarget?.let { container.repository.getEvents(it.id) }?.size ?: 0
            val started = SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main.immediate) {
                DouyinCaptureController.startWithEvidence(account, captureId, captureTimeout, importOrders = true, pagination = true, focusOrderId = targetId)
            }
            val deadline = SystemClock.elapsedRealtime() + captureTimeout + 60_000L
            while (DouyinCaptureController.account != null && SystemClock.elapsedRealtime() < deadline) {
                val running = container.repository.getAccount(account.id)
                val saved = running?.let { candidate ->
                    try {
                        val progress = container.syncCoordinator.nativeProgressStore.read(candidate)
                        progress != null && progress.captureId == captureId && progress.orders[targetId]?.logisticsSaved == true &&
                            !progress.step.startsWith("读取目标订单物流")
                    } catch (failure: AppFailure) {
                        null
                    }
                } == true
                if (saved) {
                    withContext(Dispatchers.Main.immediate) { DouyinCaptureController.stop("目标运单已保存，本轮结束") }
                    break
                }
                delay(1_000)
            }
            if (DouyinCaptureController.account != null) {
                withContext(Dispatchers.Main.immediate) { DouyinCaptureController.stop("本轮达到等待期限") }
            }
            service.awaitOrdersFinished()
            val elapsed = SystemClock.elapsedRealtime() - started
            delay(1_000)
            val orders = container.repository.getOrders()
            val target = orders.firstOrNull { it.remoteId == targetId }
            val shipments = target?.let { order -> shipmentsOf(order.id) }.orEmpty()
            val events = shipments.flatMap { container.repository.getEvents(it.id) }
            export(directory, captureId, automation)
            val current = container.repository.getAccount(account.id) ?: account
            val progress = container.syncCoordinator.nativeProgressStore.read(current)
            File(directory, "summary.json").writeText(buildJsonObject {
                put("round", round); put("captureId", captureId); put("elapsedMillis", elapsed)
                put("accountId", account.id); put("targetOrderId", targetId); put("expectedTracking", expectedTracking ?: "")
                put("beforeOrders", beforeOrders); put("beforeSources", beforeSources); put("beforeEvents", beforeEvents)
                put("orders", orders.size); put("shipments", shipments.size)
                put("targetOrderPresent", target != null)
                put("targetOrderStatus", target?.status?.name.orEmpty())
                put("carriers", JsonArray(shipments.map { it.carrier }.distinct().map(::JsonPrimitive)))
                put("trackingEvents", events.size)
                put("duplicateEvents", events.size - events.distinctBy { it.time to it.text }.size)
                put("accountState", current.state.name)
                put("serviceStatus", DouyinCaptureController.status.value)
                put("topPackage", topPackage(automation))
                progress?.let { put("progress", platformJson.parseToJsonElement(platformJson.encodeToString(it))) }
            }.toString())
            assertNotNull("目标订单没有导入，账号状态=" + current.state, target)
            assertEquals("目标订单状态与页面不一致", ParcelStatus.SIGNED, target?.status)
            assertTrue("目标订单没有关联包裹", shipments.isNotEmpty())
            assertEquals("同一运单出现多个包裹", shipments.size, shipments.map { it.trackingNumber }.distinct().size)
            expectedTracking?.let { tracking ->
                assertTrue("目标运单不存在：$tracking", shipments.any { it.trackingNumber == tracking })
            }
            assertTrue("物流轨迹为空", events.isNotEmpty())
            assertEquals("轨迹存在重复", 0, events.size - events.distinctBy { it.time to it.text }.size)
            assertNotEquals("账号状态为失败", AccountState.FAILED, current.state)
            assertEquals("采集结束时前台不是抖音", DouyinCaptureController.PACKAGE, topPackage(automation))
        } finally {
            withContext(Dispatchers.Main.immediate) { if (DouyinCaptureController.account != null) DouyinCaptureController.stop("自动导入验证结束") }
            service.awaitOrdersFinished()
            container.settings.save(settings)
        }
    }
}
