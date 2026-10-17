package com.logisticsmanagement.app

import android.app.UiAutomation
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.logisticsmanagement.app.data.LogisticsDatabase
import com.logisticsmanagement.app.model.*
import com.logisticsmanagement.app.ui.LogisticsApp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class DataUiEvidenceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun verifyActualDataInTwoRounds(): Unit = runBlocking {
        val directory = T14Evidence.directory("ui")
        val application = T14Evidence.context.applicationContext as LogisticsApplication
        application.container.ready.await()
        assertFalse(application.container.syncCoordinator.hasActiveAccounts())
        val mainBefore = T14Evidence.snapshot(application.container.database)
        T14Evidence.save(directory, "main-before.json", mainBefore)
        val name = T14Evidence.copyDatabase("schema2Path", "ui")
        var database = T14Evidence.open(name)
        var container = AppContainer(T14Evidence.context, database).apply { ready.complete(Unit) }
        assertFalse(container.automaticUpdatesEnabled)
        val before = T14Evidence.snapshot(database)
        T14Evidence.save(directory, "before.json", before)
        val shipments = container.repository.getShipments()
        val allSources = shipments.flatMap { container.repository.getSources(it.id) }
        val accounts = container.repository.getAccounts()
        val target = shipments.firstOrNull { shipment -> shipment.status == ParcelStatus.SIGNED && !shipment.archived && !shipment.trackingNumber.isNullOrBlank() &&
            allSources.any { it.shipmentId == shipment.id && it.platform == Platform.DOUYIN } } ?: error("真实副本缺少已签收抖音包裹")
        val targetSource = allSources.single { it.shipmentId == target.id }
        val targetAccount = accounts.single { it.id == targetSource.accountId }
        val targetOrder = container.repository.getOrders().single { it.id == targetSource.orderId }
        val sourceIntent = container.platforms.sourceIntent(T14Evidence.context, targetAccount, targetSource)
        assertEquals(com.logisticsmanagement.app.platform.DouyinCaptureActivity::class.java.name, sourceIntent.component!!.className)
        assertEquals(targetAccount.id, sourceIntent.getStringExtra("accountId"))
        assertEquals(targetOrder.remoteId, sourceIntent.getStringExtra("remoteOrderId"))
        val number = checkNotNull(target.trackingNumber)
        val events = container.repository.getEvents(target.id)
        check(events.isNotEmpty())
        val descriptor = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand("am start -W -n com.logisticsmanagement.app/androidx.activity.ComponentActivity")
        File(directory, "foreground.txt").writeText(ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() })
        compose.enableAccessibilityChecks()
        var scenario: ActivityScenario<ComponentActivity>? = null
        var inspectKeyboard = true
        var keyboardChecks = 0
        fun mount(dark: Boolean = false, fontScale: Float = 1f) {
            if (scenario == null) scenario = ActivityScenario.launch(Intent(T14Evidence.context, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            render(checkNotNull(scenario), container, dark, fontScale)
            inspectKeyboard = true
            compose.onNodeWithTag("navigation_shipments").assertIsDisplayed()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("shipmentsSearch").fetchSemanticsNodes().isNotEmpty() }
        }
        fun unmount() {
            scenario?.onActivity { activity -> activity.setContent {} }
            compose.waitForIdle()
        }
        fun hideKeyboard() {
            checkNotNull(scenario).onActivity { activity ->
                activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
            compose.waitForIdle()
        }
        fun search(value: String) {
            compose.onNodeWithTag("shipmentsList").performScrollToIndex(0)
            compose.onNodeWithTag("shipmentsSearch").performTextClearance()
            compose.onNodeWithTag("shipmentsSearch").performTextInput(value)
            if (inspectKeyboard) {
                compose.waitUntil(5_000) {
                    var visible = false
                    checkNotNull(scenario).onActivity { activity -> visible = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                    visible
                }
                compose.waitUntil(5_000) {
                    val bounds = compose.onNodeWithTag("navigation_shipments").fetchSemanticsNode().boundsInWindow
                    var available = false
                    checkNotNull(scenario).onActivity { activity ->
                        val insets = checkNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView)).getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
                        available = bounds.top >= insets.top && bounds.bottom <= activity.window.decorView.height - insets.bottom + 1
                        File(directory, "keyboard-$keyboardChecks.json").writeText(buildJsonObject {
                            put("navigationTop", bounds.top); put("navigationBottom", bounds.bottom)
                            put("systemTop", insets.top); put("availableBottom", activity.window.decorView.height - insets.bottom)
                            put("visible", available)
                        }.toString())
                    }
                    available
                }
                inspectKeyboard = false
                keyboardChecks++
            }
            hideKeyboard()
        }
        fun status(value: String) {
            compose.onNodeWithTag("shipmentsList").performScrollToIndex(0)
            compose.onNodeWithTag("statusFilters").performScrollToNode(hasTestTag("statusFilter_$value"))
            compose.onNodeWithTag("statusFilter_$value").performClick()
        }
        fun targetCard() = compose.onNodeWithTag("shipment_${target.id}")
        fun openTarget() {
            compose.onNodeWithTag("shipmentsList").performScrollToNode(hasTestTag("shipment_${target.id}"))
            targetCard().assertIsDisplayed().performClick()
            compose.onNodeWithTag("shipmentDetailList").assertIsDisplayed()
        }
        fun detailText(text: String) {
            compose.onNodeWithTag("shipmentDetailList").performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assertIsDisplayed()
        }
        fun back() { compose.onNodeWithContentDescription("返回").performClick() }
        try {
            repeat(2) { round ->
                mount()
                search(number)
                compose.onNodeWithTag("shipmentsList").performScrollToNode(hasTestTag("shipment_${target.id}"))
                targetCard().assertIsDisplayed()
                compose.onNodeWithTag("order_${targetOrder.id}").assertDoesNotExist()
                status("已签收")
                compose.onNodeWithTag("shipmentsList").performScrollToNode(hasTestTag("shipment_${target.id}"))
                targetCard().assertIsDisplayed()
                status("在途")
                targetCard().assertDoesNotExist()
                compose.onNodeWithTag("shipmentsList").performScrollToNode(hasText("没有找到包含", substring = true))
                compose.onNode(hasText("没有找到包含", substring = true)).assertIsDisplayed()
                status("全部")
                compose.onNodeWithContentDescription("筛选平台和账号").performClick()
                compose.onNodeWithText("抖音").performClick()
                compose.onNodeWithText("抖音 · ${targetAccount.label}").performClick()
                compose.onNodeWithText("查看包裹").performClick()
                openTarget()
                detailText(target.carrier)
                detailText(number)
                detailText("复制运单号")
                compose.onNodeWithText("复制运单号").performClick()
                compose.runOnIdle {
                    assertEquals(number, T14Evidence.context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).coerceToText(T14Evidence.context).toString())
                }
                detailText("${targetSource.platform.displayName} · ${targetSource.accountLabel}")
                detailText("查看平台订单")
                for (event in events) {
                    val row = hasTestTag("trackingEvent_${event.id}")
                    compose.onNodeWithTag("shipmentDetailList").performScrollToNode(row)
                    compose.onNode(hasText(com.logisticsmanagement.app.ui.formatTimestamp(event.time)) and hasAnyAncestor(row)).assertIsDisplayed()
                    compose.onNode(hasText(event.text) and hasAnyAncestor(row)).assertIsDisplayed()
                }
                val note = "T14 第${round + 1}轮备注保存验证"
                compose.onNodeWithTag("shipmentDetailList").performScrollToNode(hasText("包裹备注"))
                compose.onNode(hasSetTextAction() and hasText("包裹备注")).performTextClearance()
                compose.onNode(hasSetTextAction() and hasText("包裹备注")).performTextInput(note)
                hideKeyboard()
                compose.onNodeWithTag("shipmentDetailList").performScrollToNode(hasText("保存备注"))
                compose.onNodeWithText("保存备注").performClick()
                compose.waitUntil(10_000) { runBlocking { container.repository.getShipment(target.id)?.notes == note } }
                back()
                openTarget()
                detailText(note)
                compose.onNodeWithTag("shipmentDetailList").performScrollToNode(hasText("归档包裹"))
                compose.onNodeWithText("归档包裹").assertIsEnabled()
                val archiveBounds = compose.onNodeWithText("归档包裹").fetchSemanticsNode().boundsInRoot
                val messages = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "提醒")).fetchSemanticsNodes()
                messages.forEach { message -> assertFalse("归档操作不能被提示信息遮挡", archiveBounds.overlaps(message.boundsInRoot)) }
                compose.onNodeWithText("归档包裹").performClick()
                compose.waitUntil(10_000) { runBlocking { container.repository.getShipment(target.id)?.archived == true } }
                back()
                targetCard().assertDoesNotExist()
                compose.onNodeWithTag("shipmentsList").performScrollToIndex(0)
                compose.onNodeWithTag("statusFilters").performScrollToNode(hasTestTag("archivedFilter"))
                compose.onNodeWithTag("archivedFilter").performClick()
                openTarget()
                compose.onNodeWithTag("shipmentDetailList").performScrollToNode(hasText("恢复到包裹列表"))
                compose.onNodeWithText("恢复到包裹列表").performClick()
                compose.waitUntil(10_000) { runBlocking { container.repository.getShipment(target.id)?.archived == false } }
                back()
                compose.onNodeWithTag("shipmentsList").performScrollToIndex(0)
                compose.onNodeWithTag("statusFilters").performScrollToNode(hasTestTag("archivedFilter"))
                compose.onNodeWithTag("archivedFilter").performClick()
                search(number)
                checkNotNull(scenario).recreate()
                render(checkNotNull(scenario), container, false, 1f)
                compose.onNodeWithTag("shipmentsSearch").assertTextContains(number)
                openTarget()
                detailText(note)
                back()
                search("T14-无匹配-${System.nanoTime()}")
                compose.onNodeWithTag("shipmentsList").performScrollToNode(hasText("没有找到包含", substring = true))
                compose.onNode(hasText("没有找到包含", substring = true)).assertIsDisplayed()
                compose.onNodeWithTag("shipmentsList").performScrollToNode(hasText("清除筛选"))
                compose.onNodeWithText("清除筛选").performClick()
                compose.onNodeWithTag("navigation_calendar").performClick()
                val today = LocalDate.now()
                val expected = shipments.count { !it.archived && it.estimatedDeliveryAt?.let { time -> Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate() } == today }
                compose.onNodeWithTag("calendarList").performScrollToNode(hasText("${today.monthValue}月${today.dayOfMonth}日预计送达 · $expected 件"))
                compose.onNodeWithContentDescription("下一周").performClick()
                compose.onNodeWithText("返回今天").performClick()
                compose.onNodeWithTag("calendarDateSelector").performClick()
                compose.onNode(hasText("选择日期") and hasClickAction()).performClick()
                checkNotNull(scenario).onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithTag("shipmentsSearch").assertIsDisplayed()
                compose.onNodeWithTag("navigation_settings").performClick()
                compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("account_${targetAccount.id}"))
                compose.onNodeWithTag("account_${targetAccount.id}").assertIsDisplayed()
                val accountCard = hasTestTag("account_${targetAccount.id}")
                compose.onNode(hasText(com.logisticsmanagement.app.ui.accountStateLabel(targetAccount.state)) and hasAnyAncestor(accountCard)).assertExists()
                compose.onNode(hasText("最近成功更新 ${com.logisticsmanagement.app.ui.formatTimestamp(targetAccount.lastSyncedAt)}") and hasAnyAncestor(accountCard)).assertExists()
                targetAccount.error?.let { error -> compose.onNode(hasText(error) and hasAnyAncestor(accountCard)).assertExists() }
                unmount()
                T14Evidence.assertRelations(database)
                val after = T14Evidence.snapshot(database)
                val expectedTables = before.toMutableMap()
                expectedTables["Shipment"] = before.getValue("Shipment").map { row -> if (row["id"] == target.id) row + mapOf("notes" to note, "archived" to "0") else row }
                assertEquals("独立副本仅目标备注变化", expectedTables, after)
                T14Evidence.save(directory, "round-${round + 1}.json", after)
                database.close()
                database = T14Evidence.open(name)
                container = AppContainer(T14Evidence.context, database).apply { ready.complete(Unit) }
                assertEquals(note, container.repository.getShipment(target.id)!!.notes)
            }
            for (platform in Platform.entries) {
                val source = allSources.first { it.platform == platform }
                val shipment = shipments.single { it.id == source.shipmentId }
                val account = accounts.single { it.id == source.accountId }
                val intent = container.platforms.sourceIntent(T14Evidence.context, account, source)
                assertEquals(account.id, intent.getStringExtra("accountId"))
                if (platform != Platform.DOUYIN) {
                    assertEquals(com.logisticsmanagement.app.platform.PlatformBrowserActivity::class.java.name, intent.component!!.className)
                    assertEquals(source.id, intent.getStringExtra("sourceId"))
                    assertEquals(source.shipmentId, intent.getStringExtra("shipmentId"))
                }
                mount()
                search(checkNotNull(shipment.trackingNumber))
                compose.onNodeWithContentDescription("筛选平台和账号").performClick()
                compose.onNodeWithText(platform.displayName).performClick()
                compose.onNodeWithText("${platform.displayName} · ${account.label}").performClick()
                compose.onNodeWithText("查看包裹").performClick()
                compose.onNodeWithTag("shipmentsList").performScrollToNode(hasTestTag("shipment_${shipment.id}"))
                compose.onNodeWithTag("shipment_${shipment.id}").assertIsDisplayed()
                unmount()
            }
            for (dark in listOf(false, true)) for (font in listOf(1f, 1.5f)) {
                mount(dark, font)
                search(number)
                openTarget()
                detailText(number)
                compose.onNodeWithText("复制运单号").assertHasClickAction().assertIsDisplayed()
                back()
                compose.onNodeWithTag("navigation_calendar").performClick()
                compose.onNodeWithText("返回今天").assertIsDisplayed()
                compose.onNodeWithTag("navigation_settings").performClick()
                compose.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("account_${targetAccount.id}"))
                compose.onNodeWithTag("account_${targetAccount.id}").assertIsDisplayed()
                unmount()
            }
            val pickup = shipments.firstOrNull { !it.pickupCode.isNullOrBlank() && !it.trackingNumber.isNullOrBlank() && !it.archived }
            if (pickup != null) {
                mount()
                search(checkNotNull(pickup.trackingNumber))
                compose.onNodeWithTag("shipmentsList").performScrollToNode(hasTestTag("shipment_${pickup.id}"))
                compose.onNodeWithTag("shipment_${pickup.id}").performClick()
                detailText("复制取件码")
                compose.onNodeWithText("复制取件码").performClick()
                compose.runOnIdle { assertEquals(pickup.pickupCode, T14Evidence.context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).coerceToText(T14Evidence.context).toString()) }
                unmount()
            }
            assertFalse(container.syncCoordinator.hasActiveAccounts())
            assertTrue(container.syncCoordinator.progress.value.isEmpty())
            val mainAfter = T14Evidence.snapshot(application.container.database)
            T14Evidence.save(directory, "main-after.json", mainAfter)
            assertEquals("手机主库八张表保持", mainBefore, mainAfter)
            File(directory, "result.json").writeText(buildJsonObject {
                put("rounds", 2); put("themeFontCombinations", 4); put("mainDatabasePreserved", true)
                put("notesAndArchivePersisted", true); put("clipboardTrackingNumber", true)
                put("pickupCopySamples", if (pickup == null) 0 else 1)
                put("unlinkedNativeOrderSamples", container.repository.getOrders().count { it.accountId == targetAccount.id && allSources.none { source -> source.orderId == it.id } })
                put("automaticPlatformRequests", 0)
                put("keyboardBoundsChecks", keyboardChecks)
                put("accountStateMatchesDatabase", true)
            }.toString())
        } finally { unmount(); scenario?.close(); database.close() }
    }

    private fun render(scenario: ActivityScenario<ComponentActivity>, container: AppContainer, dark: Boolean, font: Float) {
        scenario.onActivity { activity -> activity.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(density, font)) {
                LogisticsApp(container)
            }
        } }
        compose.waitUntil(10_000) { compose.onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }
}
