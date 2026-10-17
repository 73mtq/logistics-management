package com.logisticsmanagement.app

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import com.logisticsmanagement.app.data.CredentialStore
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.platform.PlatformBrowserActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assume.assumeTrue
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import kotlinx.coroutines.runBlocking
import com.logisticsmanagement.app.sync.SyncEvidence
import com.logisticsmanagement.app.sync.SyncStage
import com.logisticsmanagement.app.model.AccountState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class DeviceAcceptanceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val foregroundApplication = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val descriptor = instrumentation.uiAutomation.executeShellCommand("am start -W -n com.logisticsmanagement.app/.MainActivity")
            val output = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            check(!output.contains("Error:")) { "应用前台启动失败：$output" }
            instrumentation.waitForIdleSync()
        }
    }
    @get:Rule val applicationRule: TestRule = RuleChain.outerRule(foregroundApplication).around(compose)

    @Before fun enableAccessibilityValidation() {
        compose.enableAccessibilityChecks()
    }

    @Test fun navigationAndAccountsUseRealApplication() {
        listOf("包裹", "日历", "设置").forEach { compose.onNode(hasText(it) and hasClickAction()).assertIsDisplayed() }
        compose.onNode(hasText("设置") and hasClickAction()).performClick()
        compose.onNodeWithTag("settingsList").performScrollToNode(hasText("添加抖音账号") and hasClickAction())
        compose.onNode(hasText("添加抖音账号") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("日历") and hasClickAction()).performClick()
        compose.onNodeWithText("返回今天").assertIsDisplayed()
        compose.onNode(hasText("包裹") and hasClickAction()).performClick()
    }

    @Test fun recoveryProgressMatchesSavedAccount() {
        val container = (compose.activity.application as LogisticsApplication).container
        val arguments = InstrumentationRegistry.getArguments()
        val id = checkNotNull(arguments.getString("accountId"))
        val directory = File(compose.activity.filesDir, "sync-recovery-evidence/${checkNotNull(arguments.getString("runName"))}")
        check(directory.mkdirs())
        var captures = 0
        runBlocking {
            container.ready.await()
            container.syncCoordinator.syncAccountWithEvidence(id, timeoutMillis = 15_000,
                evidence = SyncEvidence(onCapture = { _, capture ->
                    File(directory, "capture-${captures++}.json").writeText(Json.encodeToString(capture))
                }))
        }
        val progress = checkNotNull(container.syncCoordinator.progress.value[id])
        val account = runBlocking { checkNotNull(container.repository.getAccount(id)) }
        assertEquals(AccountState.PARTIAL, account.state)
        assertEquals(SyncStage.PARTIAL, progress.stage)
        assertEquals(account.error, progress.message)
        assertEquals(runBlocking { container.repository.pendingTracking(id).size }, progress.pending)
        assertTrue(captures > 0)
        compose.onNode(hasText("设置") and hasClickAction()).performClick()
        val card = hasTestTag("account_$id")
        compose.onNodeWithTag("settingsList").performScrollToNode(card)
        compose.onAllNodes(hasText(progress.message) and hasAnyAncestor(card)).assertCountEquals(2)
        compose.onNode(hasText("部分包裹等待更新") and hasAnyAncestor(card)).assertIsDisplayed()
        File(directory, "result.json").writeText("{\"progressMatchesDatabase\":true,\"pending\":${progress.pending},\"captures\":$captures}")
    }

    @Test fun searchAndFiltersReturnUseRealApplication() {
        val searchText = "验收搜索${System.nanoTime()}"
        compose.onNodeWithTag("shipmentsSearch").performClick().performTextInput(searchText)
        compose.onNodeWithTag("shipmentsSearch").assertTextContains(searchText)
        compose.onNodeWithTag("shipmentsSearch").performTextClearance()
        compose.waitUntil(5_000) {
            var keyboardVisible = false
            compose.runOnIdle { keyboardVisible = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            keyboardVisible
        }
        compose.onNodeWithContentDescription("筛选平台和账号").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText("选择平台").assertIsDisplayed()
        compose.onNodeWithText("查看包裹").performClick()
        compose.onNodeWithTag("navigation_shipments").assertIsDisplayed()
        compose.onNodeWithTag("shipmentsSearch").assertIsDisplayed()
    }

    @Test fun calendarDateSelectionAndSystemBackUseRealApplication() {
        compose.onNodeWithTag("navigation_calendar").performClick()
        compose.onNodeWithContentDescription("下一周").performClick()
        compose.onNodeWithText("返回今天").performClick()
        compose.onNodeWithTag("calendarDateSelector").performClick()
        compose.onNode(hasText("选择日期") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("上一周").assertIsDisplayed()
        compose.activity.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("shipmentsSearch").assertIsDisplayed()
    }

    @Test fun signedShipmentListAndDetailUseActualRecord() {
        val arguments = InstrumentationRegistry.getArguments()
        val accountId = checkNotNull(arguments.getString("accountId"))
        val remoteId = checkNotNull(arguments.getString("remoteOrderId"))
        val container = (compose.activity.application as LogisticsApplication).container
        val shipment = runBlocking {
            container.ready.await()
            container.repository.getShipments().single { shipment ->
                container.repository.getSources(shipment.id).any { it.accountId == accountId && it.remoteOrderId == remoteId }
            }
        }
        assertEquals(com.logisticsmanagement.app.model.ParcelStatus.SIGNED, shipment.status)
        val number = checkNotNull(shipment.trackingNumber)
        compose.onNodeWithTag("shipmentsSearch").performClick().performTextClearance()
        compose.onNodeWithTag("shipmentsSearch").performTextInput(number)
        val card = hasText(shipment.title) and hasText("已签收") and hasClickAction()
        compose.onNodeWithTag("shipmentsList").performScrollToNode(card)
        compose.onNode(card).assertIsDisplayed().performClick()
        compose.onNodeWithTag("shipmentDetailList").assertIsDisplayed()
        compose.onNodeWithText("已签收").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("shipmentsSearch").performTextClearance()
    }

    @Test fun importedShipmentDetailsAndCopyUseActualRecord() {
        val container = (compose.activity.application as LogisticsApplication).container
        val actual = runBlocking { container.repository.getShipments().firstOrNull { !it.trackingNumber.isNullOrBlank() } }
        assumeTrue("需要已经导入且提供运单号的真实包裹", actual != null)
        val shipment = checkNotNull(actual)
        fun openDetail() {
            compose.runOnIdle {
                compose.activity.startActivity(Intent(compose.activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("shipmentId", shipment.id))
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("shipmentDetailList").fetchSemanticsNodes().isNotEmpty() }
        }
        openDetail()
        compose.onNodeWithTag("shipmentDetailList").performScrollToNode(hasText("复制运单号") and hasClickAction())
        compose.onNodeWithText("复制运单号").performClick()
        compose.runOnIdle {
            val value = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(compose.activity)?.toString()
            assertTrue("剪贴板内容需要与当前包裹运单一致", value == shipment.trackingNumber)
        }
        if (!shipment.pickupCode.isNullOrBlank()) {
            compose.onNodeWithTag("shipmentDetailList").performScrollToNode(hasText("复制取件码") and hasClickAction())
            compose.onNodeWithText("复制取件码").performClick()
            compose.runOnIdle {
                val value = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(compose.activity)?.toString()
                assertTrue("剪贴板内容需要与当前包裹取件码一致", value == shipment.pickupCode)
            }
        }
        compose.onNodeWithContentDescription("返回").performClick()
        openDetail()
        compose.onNodeWithTag("shipmentDetailList").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
    }

    @Test fun nativePlatformToolbarUsesAvailableWindowBounds() {
        val container = (compose.activity.application as LogisticsApplication).container
        val account = runBlocking { container.repository.getAccounts().firstOrNull { it.captureMode == com.logisticsmanagement.app.model.CaptureMode.WEB } }
        assumeTrue("需要已经创建的真实平台账号", account != null)
        val intent = Intent(compose.activity, PlatformBrowserActivity::class.java)
            .putExtra("accountId", checkNotNull(account).id).putExtra("mode", "login")
        ActivityScenario.launch<PlatformBrowserActivity>(intent).use { scenario ->
            val ready = CountDownLatch(1)
            scenario.onActivity { activity -> activity.window.decorView.doOnLayout { ready.countDown() } }
            assertTrue("平台网页需要完成窗口布局", ready.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                assertVisibleButtonsUseAvailableBounds(activity.window.decorView, setOf("返回", "刷新", "完成登录", "导出诊断", "进入订单"))
            }
        }
    }


    @Test fun deviceSupportsRequiredWebViewCapabilities() {
        listOf(WebViewFeature.MULTI_PROFILE, WebViewFeature.DOCUMENT_START_SCRIPT, WebViewFeature.WEB_MESSAGE_LISTENER, WebViewFeature.DELETE_BROWSING_DATA).forEach {
            assertTrue("系统 WebView 缺少 $it", WebViewFeature.isFeatureSupported(it))
        }
    }

    @Test fun keystoreEncryptsAndAuthenticatesActualRandomContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CredentialStore(context)
        val content = Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        val encrypted = store.encrypt(content)
        assertNotEquals(content, encrypted)
        assertEquals(content, store.decrypt(encrypted))
        assertNotEquals(encrypted, store.encrypt(content))
    }

    private fun assertVisibleButtonsUseAvailableBounds(decor: View, labels: Set<String>) {
        val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor)).getInsets(WindowInsetsCompat.Type.systemBars())
        val location = IntArray(2).also(decor::getLocationOnScreen)
        val top = location[1] + insets.top
        val bottom = location[1] + decor.height - insets.bottom
        val minimumSize = (48 * decor.resources.displayMetrics.density).toInt()
        val buttons = childViews(decor).filterIsInstance<Button>().filter { it.text.toString() in labels }.toList()
        assertTrue("页面需要显示原生操作按钮", buttons.isNotEmpty())
        var visibleCount = 0
        buttons.forEach { button ->
            button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
            val bounds = Rect()
            if (button.getGlobalVisibleRect(bounds)) {
                visibleCount++
                assertTrue("原生操作按钮需要位于系统栏可用区域", bounds.top >= top && bounds.bottom <= bottom)
                assertTrue("原生操作按钮需要满足48.dp触摸区域", bounds.width() >= minimumSize && bounds.height() >= minimumSize)
                val textLayout = checkNotNull(button.layout)
                assertTrue("原生按钮文字需要完整显示", (0 until textLayout.lineCount).all { textLayout.getEllipsisCount(it) == 0 })
            }
        }
        assertTrue("页面需要存在可见的原生操作按钮", visibleCount > 0)
    }

    private fun childViews(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(childViews(view.getChildAt(index)))
        }
    }
}
