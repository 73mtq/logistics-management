package com.logisticsmanagement.app.platform

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.CookieManagerCompat
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.coroutines.resume

@Serializable
internal data class CapturedResponse(val kind: String, val url: String, val body: String = "", val status: Int = 0,
    val requestType: String = "", val requestPage: Int = 0, val pageUrl: String = "", val characters: Int? = null)

internal class AccountProfiles(context: Context) {
    private val preferences = context.getSharedPreferences("webview_profiles", Context.MODE_PRIVATE)
    private val loaded = mutableSetOf<String>()
    private val active = mutableMapOf<String, MutableSet<PlatformWebView>>()
    private var prepared = false

    fun prepare() {
        checkMainThread()
        if (prepared) return
        requireFeature(WebViewFeature.MULTI_PROFILE)
        requireFeature(WebViewFeature.DELETE_BROWSING_DATA)
        val pending = preferences.getStringSet("pending_deletions", emptySet()).orEmpty().toSet()
        pending.forEach { ProfileStore.getInstance().deleteProfile(it) }
        check(preferences.edit().putStringSet("pending_deletions", emptySet()).commit())
        prepared = true
    }

    fun profile(account: Account): Profile {
        prepare()
        checkAccount(account)
        loaded.add(account.profileName)
        return ProfileStore.getInstance().getOrCreateProfile(account.profileName)
    }

    fun bind(account: Account, view: WebView) {
        prepare()
        checkAccount(account)
        WebViewCompat.setProfile(view, account.profileName)
        loaded.add(account.profileName)
    }

    fun attach(account: Account, session: PlatformWebView) {
        checkMainThread()
        active.getOrPut(account.profileName) { mutableSetOf() }.add(session)
    }

    fun detach(account: Account, session: PlatformWebView) {
        checkMainThread()
        active[account.profileName]?.remove(session)
        if (active[account.profileName]?.isEmpty() == true) active.remove(account.profileName)
    }

    internal fun activeSessionCount(account: Account): Int {
        checkMainThread()
        return active[account.profileName]?.size ?: 0
    }

    suspend fun delete(account: Account) = deleteProfile(account.profileName)

    suspend fun deleteProfile(name: String) = withContext(Dispatchers.Main.immediate) {
        prepare()
        require(name.startsWith("account_") && name != Profile.DEFAULT_PROFILE_NAME)
        val pending = preferences.getStringSet("pending_deletions", emptySet()).orEmpty().toMutableSet()
        pending.add(name)
        check(preferences.edit().putStringSet("pending_deletions", pending).commit())
        active.values.flatten().filter { it.account.profileName == name }.toList().forEach { it.close(AppFailure("账号已移除", FailureKind.NEEDS_LOGIN)) }
        if (name in loaded) {
            requireFeature(WebViewFeature.DELETE_BROWSING_DATA)
            val profile = ProfileStore.getInstance().getProfile(name)
            if (profile != null) {
                suspendCancellableCoroutine { continuation ->
                    WebStorageCompat.deleteBrowsingData(profile.webStorage) {
                        profile.cookieManager.flush()
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
        } else {
            if (ProfileStore.getInstance().getProfile(name) == null) {
                pending.remove(name)
                check(preferences.edit().putStringSet("pending_deletions", pending).commit())
            }
        }
    }

    fun checkAccount(account: Account) {
        checkMainThread()
        if (account.deleted || account.profileName in preferences.getStringSet("pending_deletions", emptySet()).orEmpty()) {
            throw AppFailure("账号已移除", FailureKind.NEEDS_LOGIN)
        }
        require(account.profileName.startsWith("account_") && account.profileName != Profile.DEFAULT_PROFILE_NAME)
    }
}

internal fun requireFeature(feature: String) {
    if (!WebViewFeature.isFeatureSupported(feature)) {
        throw AppFailure("Android System WebView 需要支持 $feature，请更新系统 WebView", FailureKind.UNSUPPORTED_WEBVIEW)
    }
}

internal fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper()) { "WebView 操作必须使用主线程" }

internal fun allowsOrigin(uri: Uri, origins: Set<String>): Boolean = uri.scheme == "https" &&
    (uri.port == -1 || uri.port == 443) && origins.any { origin -> Uri.parse(origin).host == uri.host }

internal fun cookieValues(profile: Profile, url: String): Map<String, String> {
    requireFeature(WebViewFeature.GET_COOKIE_INFO)
    val httpUrl = url.toHttpUrl()
    return CookieManagerCompat.getCookieInfo(profile.cookieManager, url).map { raw ->
        Cookie.parse(httpUrl, raw) ?: throw AppFailure("平台 Cookie 格式尚未支持", FailureKind.UNSUPPORTED_RESPONSE)
    }.associate { it.name to it.value }
}

internal fun hasPlatformLoginSession(profile: Profile, platform: Platform): Boolean {
    checkMainThread()
    return when (platform) {
        Platform.JD -> cookieValues(profile, "https://www.jd.com/").let {
            !it["pt_key"].isNullOrBlank() && !it["pt_pin"].isNullOrBlank()
        }
        Platform.TAOBAO -> cookieValues(profile, "https://h5.m.taobao.com/").let {
            !it["cookie2"].isNullOrBlank() && (!it["unb"].isNullOrBlank() || !it["cookie1"].isNullOrBlank())
        }
        Platform.PDD -> cookieValues(profile, "https://mobile.yangkeduo.com/").let {
            !it["PDDAccessToken"].isNullOrBlank() &&
                listOf("pdduid", "PASS_ID", "pdd_user_id", "pdd_user_uin").any { key -> !it[key].isNullOrBlank() }
        }
        Platform.DOUYIN -> false
    }
}

internal fun requirePlatformLoginSession(profiles: AccountProfiles, account: Account) {
    if (!hasPlatformLoginSession(profiles.profile(account), account.platform)) {
        throw AppFailure("${account.platform.displayName}账号「${account.label}」需要完成登录", FailureKind.NEEDS_LOGIN)
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal class PlatformWebView(
    context: Context,
    val account: Account,
    private val adapter: PlatformAdapter,
    private val profiles: AccountProfiles,
    private val interactive: Boolean,
) {
    val messages = Channel<CapturedResponse>(64)
    val view: WebView
    private val script: ScriptHandler
    private var closed = false
    val isClosed: Boolean get() = closed
    var currentUrl: String = ""
        private set
    var onPage: ((String) -> Unit)? = null

    init {
        checkMainThread()
        requireFeature(WebViewFeature.MULTI_PROFILE)
        requireFeature(WebViewFeature.WEB_MESSAGE_LISTENER)
        requireFeature(WebViewFeature.DOCUMENT_START_SCRIPT)
        requireFeature(WebViewFeature.GET_COOKIE_INFO)
        requireFeature(WebViewFeature.DELETE_BROWSING_DATA)
        profiles.prepare()
        profiles.checkAccount(account)
        view = WebView(context)
        profiles.bind(account, view)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            if (account.platform == Platform.DOUYIN) {
                val version = WebViewCompat.getCurrentWebViewPackage(context)?.versionName
                    ?: throw AppFailure("无法取得系统 WebView 版本，请更新系统 WebView", FailureKind.UNSUPPORTED_WEBVIEW)
                userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Safari/537.36"
            }
        }
        WebViewCompat.getProfile(view).cookieManager.apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        WebViewCompat.addWebMessageListener(view, "LogisticsCapture", adapter.allowedOrigins) { _, message, origin, mainFrame, _ ->
            if (closed || !mainFrame || !allowsOrigin(origin, adapter.allowedOrigins)) return@addWebMessageListener
            if (message.type != WebMessageCompat.TYPE_STRING) {
                close(AppFailure("平台消息格式尚未支持", FailureKind.UNSUPPORTED_RESPONSE))
                return@addWebMessageListener
            }
            val raw = message.data ?: run {
                close(AppFailure("平台消息没有提供数据", FailureKind.UNSUPPORTED_RESPONSE))
                return@addWebMessageListener
            }
            if (raw.length > 2_100_000) {
                close(AppFailure("平台响应超过采集容量", FailureKind.UNSUPPORTED_RESPONSE))
                return@addWebMessageListener
            }
            val capture = try { platformJson.decodeFromString<CapturedResponse>(raw) }
            catch (failure: SerializationException) {
                close(AppFailure("平台消息结构尚未支持", FailureKind.UNSUPPORTED_RESPONSE))
                return@addWebMessageListener
            }
            if (capture.pageUrl.isNotBlank() && capture.pageUrl.substringBefore('#') != view.url?.substringBefore('#')) return@addWebMessageListener
            if (capture.kind == "capacity") {
                messages.trySend(capture)
                close(AppFailure("平台响应超过采集容量（${capture.characters} 字符），可导出响应结构诊断", FailureKind.UNSUPPORTED_RESPONSE))
                return@addWebMessageListener
            }
            if (capture.kind == "challenge" && !interactive) {
                messages.trySend(capture)
                close(AppFailure("${account.platform.displayName}账号「${account.label}」需要完成验证", FailureKind.NEEDS_VERIFICATION))
                return@addWebMessageListener
            }
            if (capture.kind == "error") {
                messages.trySend(capture)
                close(AppFailure(capture.body, FailureKind.UNSUPPORTED_RESPONSE))
                return@addWebMessageListener
            }
            if (capture.kind == "network") {
                messages.trySend(capture)
                close(AppFailure("${account.platform.displayName}接口网络请求失败，请检查网络连接", FailureKind.NETWORK))
                return@addWebMessageListener
            }
            if (capture.kind == "response" && capture.status >= 400) {
                messages.trySend(capture)
                val kind = if (capture.status == 401) FailureKind.NEEDS_LOGIN else FailureKind.SERVICE_ERROR
                close(AppFailure("${account.platform.displayName}接口返回 HTTP ${capture.status}", kind))
                return@addWebMessageListener
            }
            if (messages.trySend(capture).isFailure) close(AppFailure("平台响应数量超过采集容量", FailureKind.UNSUPPORTED_RESPONSE))
        }
        script = WebViewCompat.addDocumentStartJavaScript(view, CaptureScripts.install(account.platform), adapter.allowedOrigins)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val allowed = allowsOrigin(request.url, adapter.allowedOrigins)
                if (request.isForMainFrame) {
                    messages.trySend(CapturedResponse("redirect", request.url.toString(), if (allowed) "allowed-navigation" else "blocked-navigation"))
                }
                return !allowed
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (closed) return
                currentUrl = url
                messages.trySend(CapturedResponse("pageFinished", url))
                val uri = Uri.parse(url)
                if (!allowsOrigin(uri, adapter.allowedOrigins)) {
                    messages.trySend(CapturedResponse("redirect", url, "finished-unauthorized"))
                    close(AppFailure("平台跳转到尚未授权的网页来源", FailureKind.NEEDS_VERIFICATION))
                    return
                }
                if (!interactive && isLoginUrl(url)) {
                    close(AppFailure("${account.platform.displayName}账号「${account.label}」需要重新登录", FailureKind.NEEDS_LOGIN))
                    return
                }
                onPage?.invoke(url)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) close(AppFailure("网页加载失败：${error.description}", FailureKind.NETWORK))
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) close(AppFailure("网页返回 HTTP ${response.statusCode}", FailureKind.SERVICE_ERROR))
            }
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                close(AppFailure("WebView 渲染进程终止，请重新打开页面", FailureKind.UNSUPPORTED_WEBVIEW))
                return true
            }
        }
        if (!interactive) {
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val width = context.resources.displayMetrics.widthPixels
            val height = context.resources.displayMetrics.heightPixels
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
        }
        profiles.attach(account, this)
    }

    fun load(url: String) {
        checkMainThread()
        check(!closed)
        view.stopLoading()
        while (messages.tryReceive().isSuccess) Unit
        currentUrl = ""
        if (!allowsOrigin(Uri.parse(url), adapter.allowedOrigins)) throw AppFailure("网页来源尚未授权", FailureKind.CONFIGURATION)
        view.loadUrl(url)
    }

    fun evaluate(script: String) {
        checkMainThread()
        if (!closed) view.evaluateJavascript(script, null)
    }

    suspend fun evaluateAwait(script: String): String {
        checkMainThread()
        if (closed) throw AppFailure("平台页面已经关闭，请重新打开", FailureKind.CONFIGURATION)
        return suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { value ->
                if (continuation.isActive) continuation.resume(value)
            }
        }
    }

    fun tapPage(x: Float, y: Float, pageWidth: Float, pageHeight: Float) {
        checkMainThread()
        if (closed || pageWidth <= 0f || pageHeight <= 0f || view.width <= 0 || view.height <= 0) return
        val px = x * view.width / pageWidth
        val py = y * view.height / pageHeight
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, px, py, 0)
        val up = MotionEvent.obtain(downTime, downTime + 80, MotionEvent.ACTION_UP, px, py, 0)
        try {
            view.dispatchTouchEvent(down)
            view.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    fun swipePage(fromX: Float, fromY: Float, toX: Float, toY: Float, pageWidth: Float, pageHeight: Float) {
        checkMainThread()
        if (closed || pageWidth <= 0f || pageHeight <= 0f || view.width <= 0 || view.height <= 0) return
        val x1 = fromX * view.width / pageWidth
        val y1 = fromY * view.height / pageHeight
        val x2 = toX * view.width / pageWidth
        val y2 = toY * view.height / pageHeight
        val downTime = SystemClock.uptimeMillis()
        fun dispatch(time: Long, action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(downTime, time, action, x, y, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        dispatch(downTime, MotionEvent.ACTION_DOWN, x1, y1)
        for (index in 1..10) {
            val fraction = index / 10f
            dispatch(downTime + (index * 30).toLong(), MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * fraction, y1 + (y2 - y1) * fraction)
        }
        dispatch(downTime + 340, MotionEvent.ACTION_UP, x2, y2)
    }

    fun advanceOrders() {
        evaluate(CaptureScripts.moreOrders)
        if (closed || view.width == 0 || view.height == 0) return
        val downTime = SystemClock.uptimeMillis()
        val x = view.width / 2f
        val y = view.height * 0.85f
        fun dispatch(time: Long, action: Int, position: Float) {
            val event = MotionEvent.obtain(downTime, time, action, x, position, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        dispatch(downTime, MotionEvent.ACTION_DOWN, y)
        for (index in 1..8) dispatch(downTime + index * 30, MotionEvent.ACTION_MOVE, y - view.height * 0.085f * index)
        dispatch(downTime + 270, MotionEvent.ACTION_UP, view.height * 0.17f)
    }

    fun close(failure: AppFailure? = null) {
        checkMainThread()
        if (closed) return
        closed = true
        profiles.detach(account, this)
        view.stopLoading()
        script.remove()
        WebViewCompat.removeWebMessageListener(view, "LogisticsCapture")
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        messages.close(failure)
    }

    companion object {
        fun isLoginUrl(url: String): Boolean {
            val uri = Uri.parse(url)
            val host = uri.host.orEmpty()
            val path = uri.path.orEmpty()
            return host.startsWith("plogin.") || host.startsWith("login.") ||
                path.contains("/login") || path.contains("/passport") || path.contains("nopasswordcmcc")
        }
    }
}
