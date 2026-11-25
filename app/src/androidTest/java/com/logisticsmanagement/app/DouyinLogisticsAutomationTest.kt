package com.logisticsmanagement.app

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

    private fun shell(automation: UiAutomation, command: String): String {
        val descriptor = automation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun topPackage(automation: UiAutomation): String {
        val output = shell(automation, "dumpsys activity activities | grep topResumedActivity")
        return Regex("ActivityRecord\\{[^}]*\\s([A-Za-z0-9_.]+)/").find(output)?.groupValues?.get(1).orEmpty()
    }

    private suspend fun ensureAccessibility(automation: UiAutomation) {
        val component = "com.logisticsmanagement.app/com.logisticsmanagement.app.platform.DouyinCaptureService"
        val current = shell(automation, "settings get secure enabled_accessibility_services").trim()
        val normalized = if (current == "null") "" else current
        val others = normalized.split(':').filter { it.isNotBlank() && it != component }
        if (others.isEmpty()) shell(automation, "settings delete secure enabled_accessibility_services")
        else shell(automation, "settings put secure enabled_accessibility_services " + others.joinToString(":"))
        delay(500)
        val value = if (others.isEmpty()) component else others.joinToString(":") + ":" + component
        shell(automation, "settings put secure enabled_accessibility_services " + value)
        shell(automation, "settings put secure accessibility_enabled 1")
        delay(1_200)
    }

    private suspend fun shipmentsOf(orderId: String) = container.repository.getShipments().filter { shipment ->
        container.repository.getSources(shipment.id).any { it.orderId == orderId }
    }

    private suspend fun awaitService(automation: UiAutomation): DouyinCaptureService {
        container.ready.await()
        repeat(3) {
            DouyinCaptureController.service?.let { return it }
            withContext(Dispatchers.Main.immediate) {
                val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            delay(1_000)
            ensureAccessibility(automation)
            withTimeoutOrNull(60_000) { while (DouyinCaptureController.service == null) delay(200) }
        }
        return checkNotNull(DouyinCaptureController.service) { "抖音采集服务未连接" }
    }

    private fun export(directory: File, captureId: String, automation: UiAutomation) {
        val evidence = File(directory, "evidence").apply { check(mkdirs() || isDirectory) }
        val failures = mutableListOf<String>()
        File(context.filesDir, "native-entry-evidence/$captureId").listFiles().orEmpty()
            .filter { it.extension == "enc" }
            .forEach { file ->
                try {
                    File(evidence, file.name.removeSuffix(".enc") + ".json").writeText(container.credentials.decrypt(file.readText()))
                } catch (failure: Exception) {
                    failures.add(file.name + ": " + (failure.message ?: failure.javaClass.simpleName))
                }
            }
        check(failures.isEmpty()) { "采集资料无法解密：${failures.joinToString()}" }
        val ocr = File(directory, "ocr").apply { check(mkdirs() || isDirectory) }
        DouyinCaptureController.ocrEvidence().forEachIndexed { index, record ->
            val name = index.toString().padStart(3, '0')
            File(ocr, "$name.txt").writeText("capturedAt=${record.capturedAt}\n" + record.text)
            record.jpeg?.let { File(ocr, "$name.jpg").writeBytes(it) }
        }
        try {
            automation.takeScreenshot()?.let { bitmap ->
                File(directory, "screen.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } catch (failure: Exception) {
            File(directory, "screenshot-error.txt").writeText(failure.message ?: failure.javaClass.simpleName)
        }
    }

    @Test fun importLogisticsAutomatically(): Unit = runBlocking {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val round = arguments.getString("round") ?: "1"
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "s1/round-$round-${System.currentTimeMillis()}")
        check(directory.mkdirs() || directory.isDirectory)
        val service = awaitService(automation)
        shell(automation, "am force-stop " + DouyinCaptureController.PACKAGE)
        delay(1_000)
        shell(automation, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.ss.android.ugc.aweme/.splash.SplashActivity")
        val launchDeadline = SystemClock.elapsedRealtime() + 20_000
        var top = topPackage(automation)
        while (top != DouyinCaptureController.PACKAGE && SystemClock.elapsedRealtime() < launchDeadline) {
            delay(500)
            top = topPackage(automation)
        }
        check(top == DouyinCaptureController.PACKAGE) { "抖音没有进入前台：${top.ifBlank { "未知" }}" }
        delay(1_500)
        val account = container.repository.getAccounts().firstOrNull { it.platform == Platform.DOUYIN && !it.deleted }
            ?: container.repository.createAccount(Platform.DOUYIN, "抖音物流采集")
        val settings = container.settings.get()
        container.settings.save(settings.copy(nativeEvidenceVerbose = true))
        DouyinCaptureController.clearOcrEvidence()
        val captureId = "S1R" + round + "T" + System.currentTimeMillis()
        val targetId = arguments.getString("targetOrderId") ?: "6918361666413145327"
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
