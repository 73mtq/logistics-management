package com.logisticsmanagement.app.platform

import android.os.Bundle
import android.view.ViewGroup
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.logisticsmanagement.app.LogisticsApplication
import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.AccountState
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.model.SourceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class PlatformBrowserActivity : ComponentActivity() {
    private val registry get() = (application as LogisticsApplication).container.platforms
    private lateinit var status: TextView
    private lateinit var webContainer: LinearLayout
    private lateinit var xiaomiConfiguration: Button
    private var session: PlatformWebView? = null
    private var account: Account? = null
    private var source: SourceRef? = null
    private var loginFinished = false

    private val exportDiagnostic = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val diagnostic = registry.diagnostic(checkNotNull(account))
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(uri) ?: throw AppFailure("无法打开诊断文件", FailureKind.CONFIGURATION)
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(diagnostic) }
                }
                status.text = "响应结构诊断已经导出"
            } catch (failure: AppFailure) { status.text = failure.message }
            catch (failure: IOException) { status.text = "诊断文件写入失败，请检查所选文件位置" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val padding = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            WindowInsetsCompat.CONSUMED
        }
        status = TextView(this).apply {
            setPadding(16, 16, 16, 16)
            textSize = 16f
            text = "正在打开平台网页"
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val actions = LinearLayout(this)
        fun action(text: String, body: () -> Unit) {
            actions.addView(Button(this).apply { this.text = text; minHeight = (48 * resources.displayMetrics.density).toInt(); setOnClickListener { body() } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        action("返回") { if (session?.view?.canGoBack() == true) session?.view?.goBack() else finish() }
        action("刷新") { session?.view?.reload() }
        action("完成登录") { finishLogin() }
        action("导出诊断") {
            account?.let { exportDiagnostic.launch("${it.platform.name.lowercase()}-response-schema.json") }
        }
        val platformActions = LinearLayout(this)
        platformActions.addView(Button(this).apply {
            text = "进入订单"
            setOnClickListener {
                val current = account ?: return@setOnClickListener
                val adapter = registry.adapter(current.platform)
                if (current.platform == Platform.DOUYIN) {
                    val browser = session ?: return@setOnClickListener
                    if (android.net.Uri.parse(browser.currentUrl).host == "www.douyin.com") browser.load(adapter.orderListUrl)
                    else browser.evaluate(CaptureScripts.douyinOrders)
                    status.text = "请在官方商城中进入订单页面，应用将采集页面响应"
                } else session?.load(adapter.orderListUrl)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        xiaomiConfiguration = Button(this).apply {
            text = "小米账号配置"
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { account?.takeIf { it.platform == Platform.XIAOMI }?.let { registry.openXiaomiConfiguration(this@PlatformBrowserActivity, it) } }
        }
        platformActions.addView(xiaomiConfiguration, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        webContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(actions)
        root.addView(platformActions)
        root.addView(status)
        root.addView(webContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        onBackPressedDispatcher.addCallback(this) {
            if (session?.view?.canGoBack() == true) session?.view?.goBack() else finish()
        }
        lifecycleScope.launch {
            try { open() } catch (failure: AppFailure) { showFailure(failure) }
        }
    }

    private suspend fun open() {
        val id = intent.getStringExtra("accountId") ?: throw AppFailure("没有提供平台账号", FailureKind.CONFIGURATION)
        val selected = registry.repository.getAccount(id)?.takeIf { !it.deleted }
            ?: throw AppFailure("平台账号已移除", FailureKind.CONFIGURATION)
        account = selected
        xiaomiConfiguration.visibility = if (selected.platform == Platform.XIAOMI) View.VISIBLE else View.GONE
        val shipmentId = intent.getStringExtra("shipmentId")
        val sourceId = intent.getStringExtra("sourceId")
        if (shipmentId != null && sourceId != null) source = registry.repository.getSources(shipmentId).firstOrNull { it.id == sourceId && it.accountId == id }
        val adapter = registry.adapter(selected.platform)
        val browser = PlatformWebView(this, selected, adapter, registry.profiles, true)
        session = browser
        webContainer.addView(browser.view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        browser.onPage = { url ->
            status.text = "${selected.platform.displayName}：${android.net.Uri.parse(url).host.orEmpty()}"
        }
        val mode = intent.getStringExtra("mode") ?: "login"
        val isLogin = mode == "login"
        val initial = when (mode) {
            "login" -> adapter.loginUrl
            "source" -> source?.orderUrl?.takeIf(String::isNotBlank)
                ?: throw AppFailure("该来源没有提供可浏览的订单网页", FailureKind.CONFIGURATION)
            "order" -> {
                val orderId = intent.getStringExtra("orderId")
                    ?: throw AppFailure("没有提供订单标识", FailureKind.CONFIGURATION)
                registry.repository.getOrders().firstOrNull { it.id == orderId && it.accountId == selected.id }
                    ?.orderUrl?.takeIf(String::isNotBlank)
                    ?: throw AppFailure("订单没有提供可浏览的网页", FailureKind.CONFIGURATION)
            }
            else -> throw AppFailure("网页打开方式尚未支持", FailureKind.CONFIGURATION)
        }
        browser.load(initial)
        lifecycleScope.launch {
            try {
                for (capture in browser.messages) {
                    registry.rememberCapture(selected, capture)
                    if (selected.platform == Platform.DOUYIN && capture.kind == "response") {
                        registry.rememberDouyinPage(selected, browser.currentUrl)
                        status.text = "抖音响应已经采集，当前结构需要确认。可以导出响应结构诊断。"
                        updateState(AccountState.NEEDS_VERIFICATION, "抖音响应结构需要通过真实账号确认")
                        continue
                    }
                    if (capture.kind == "challenge") { status.text = capture.body; updateState(AccountState.NEEDS_VERIFICATION, capture.body); continue }
                    try {
                        if (adapter is BrowserAdapter) {
                            val page = withContext(Dispatchers.Default) { adapter.parseOrders(capture) }
                            if (page != null) {
                                adapter.rememberOrderPage(selected, page)
                                registry.repository.ingest(selected, page.orders)
                                status.text = "已保存当前页面的 ${page.orders.size} 个订单"
                                updateState(AccountState.READY, null)
                            }
                            source?.let { currentSource ->
                                withContext(Dispatchers.Default) { adapter.parseTracking(capture, currentSource) }?.let {
                                    registry.repository.applyTracking(currentSource, it)
                                    status.text = "物流记录已经保存"
                                }
                            }
                        } else if (adapter is TaobaoAdapter && capture.kind == "ssr" && source != null) {
                            val currentSource = checkNotNull(source)
                            val incoming = withContext(Dispatchers.Default) { adapter.parseSsr(capture.body, currentSource.remoteShipmentId) }
                            registry.repository.applyTracking(currentSource, incoming)
                            status.text = "物流记录已经保存"
                        }
                    } catch (failure: AppFailure) { recordFailure(failure) }
                }
            } catch (failure: AppFailure) { recordFailure(failure) }
        }
        if (isLogin && selected.platform != Platform.DOUYIN) lifecycleScope.launch {
            while (!loginFinished && !isFinishing) {
                delay(2_000)
                if (hasLoginSession(selected)) {
                    updateState(AccountState.READY, null)
                    loginFinished = true
                    status.text = "登录会话已经保存"
                    if (selected.platform == Platform.XIAOMI && intent.getBooleanExtra("returnToXiaomiConfiguration", false)) {
                        registry.openXiaomiConfiguration(this@PlatformBrowserActivity, selected)
                    }
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }

    private fun hasLoginSession(account: Account): Boolean {
        val browser = session ?: return false
        if (browser.currentUrl.isBlank() || PlatformWebView.isLoginUrl(browser.currentUrl)) return false
        if (account.platform == Platform.XIAOMI && android.net.Uri.parse(browser.currentUrl).host != "api.assistant.miui.com") return false
        return hasPlatformLoginSession(registry.profiles.profile(account), account.platform)
    }

    private fun finishLogin() = lifecycleScope.launch {
        try {
            val current = account ?: throw AppFailure("平台账号尚未打开", FailureKind.CONFIGURATION)
            if (current.platform == Platform.DOUYIN) {
                updateState(AccountState.NEEDS_VERIFICATION, "请在官方商城订单页面采集真实响应")
                status.text = "会话已经保存，请进入官方商城订单页面以采集响应"
            } else if (hasLoginSession(current)) {
                updateState(AccountState.READY, null)
                setResult(RESULT_OK)
                finish()
            } else throw AppFailure("还没有取得完整的平台登录会话，请完成网页登录与验证", FailureKind.NEEDS_LOGIN)
        } catch (failure: AppFailure) { showFailure(failure) }
    }

    private suspend fun updateState(state: AccountState, error: String?) {
        val id = account?.id ?: return
        registry.repository.setSyncState(id, state, error)
    }

    private suspend fun recordFailure(failure: AppFailure) {
        showFailure(failure)
        val state = when (failure.kind) {
            FailureKind.NEEDS_LOGIN -> AccountState.NEEDS_LOGIN
            FailureKind.NEEDS_VERIFICATION -> AccountState.NEEDS_VERIFICATION
            else -> AccountState.FAILED
        }
        updateState(state, failure.message)
    }

    private fun showFailure(failure: AppFailure) { status.text = failure.message }

    override fun onPause() { session?.view?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); session?.view?.onResume() }
    override fun onDestroy() { session?.close(); session = null; super.onDestroy() }
}
