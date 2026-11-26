package com.logisticsmanagement.app.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import android.content.Context
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.logisticsmanagement.app.LogisticsApplication
import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.AccountState
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.Platform
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume

@Serializable
internal data class NativeNode(
    val text: String,
    val description: String,
    val className: String,
    val resourceId: String,
    val path: List<Int>,
    val bounds: List<Int>,
    val clickable: Boolean,
    val scrollable: Boolean,
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val actions: List<Int> = emptyList(),
    val childCount: Int = 0,
    val supplementalTexts: Map<String, String> = emptyMap(),
    val expandedState: Int = 0,
    val selected: Boolean = false,
    val availableExtraData: List<String> = emptyList(),
)

@Serializable
internal data class NativePage(val packageName: String, val nodes: List<NativeNode>, val capturedAt: Long,
    val captureId: String = "", val accountId: String = "", val windowId: Int = -1,
    val nodeHash: String = "", val readCount: Int = nodes.size, val invisibleCount: Int = 0,
    val reader: String = "service")

@Serializable
internal data class NativeWindow(val id: Int, val type: Int, val layer: Int, val active: Boolean,
    val focused: Boolean, val bounds: List<Int>, val accepted: Boolean, val reason: String)

@Serializable
internal data class NativeScan(val captureId: String, val accountId: String, val sequence: Int,
    val capturedAt: Long, val phase: String, val eventType: Int, val eventClassName: String,
    val windows: List<NativeWindow>, val pages: List<NativePage>, val reasons: List<String>,
    val serviceConnected: Boolean = true, val touchExplorationRequested: Boolean = false,
    val refreshResults: List<NativeRefreshResult> = emptyList(), val textQueries: List<NativeTextQuery> = emptyList())

@Serializable
internal data class NativeTextQuery(val windowId: Int, val text: String, val matches: List<NativeNode>, val unresolved: Int)

@Serializable
internal data class NativeEventEvidence(val captureId: String, val accountId: String, val sequence: Int,
    val capturedAt: Long, val type: Int, val windowId: Int, val className: String,
    val text: List<String>, val description: String, val source: NativeNode? = null)

@Serializable
internal data class NativeRefreshResult(val windowId: Int, val path: List<Int>, val refreshed: Boolean)

internal data class NativeActionResult(val accepted: Boolean, val reason: String, val method: String = "node")

internal object DouyinCaptureController {
    const val PACKAGE = "com.ss.android.ugc.aweme"
    var service: DouyinCaptureService? = null
    private val mutableConnected = MutableStateFlow(false)
    val connected = mutableConnected.asStateFlow()
    internal fun attach(service: DouyinCaptureService) { this.service = service; mutableConnected.value = true }
    internal fun detach(service: DouyinCaptureService) { if (this.service === service) { this.service = null; mutableConnected.value = false } }
    fun accessibilityEnabled(context: Context): Boolean = context.getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == context.packageName && it.resolveInfo.serviceInfo.name == DouyinCaptureService::class.java.name }

    fun statusText(context: Context): String {
        val accessibility = if (accessibilityEnabled(context)) "无障碍服务已启用" else "无障碍服务未启用"
        val connection = if (connected.value) "已连接" else "未连接"
        val capture = if (activeAccount() != null) "采集中" else "空闲"
        val touch = if (navigationMode) "触摸模式开" else "触摸模式关"
        return "${accessibility} · $connection · $capture · $touch"
    }
    var account: Account? = null
        private set
    private var expiresAt = 0L
    var captureId: String = ""
        private set
    var phase: String = "未指定页面"
        private set
    private val mutablePage = MutableStateFlow<NativePage?>(null)
    private val mutableStatus = MutableStateFlow("尚未开始采集")
    val page = mutablePage.asStateFlow()
    val status = mutableStatus.asStateFlow()

    internal data class OcrEvidenceRecord(val capturedAt: Long, val text: String, val lines: List<OcrLine>, val jpeg: ByteArray?)
    private val ocrRecords = ArrayDeque<OcrEvidenceRecord>()
    @Synchronized internal fun recordOcr(value: OcrEvidenceRecord) {
        ocrRecords.addLast(value)
        while (ocrRecords.size > 8) ocrRecords.removeFirst()
    }
    @Synchronized internal fun ocrEvidence(): List<OcrEvidenceRecord> = ocrRecords.toList()
    @Synchronized internal fun clearOcrEvidence() { ocrRecords.clear() }

    var navigationMode: Boolean = false
        private set
    internal var terminationReason: String = ""
        private set
    fun start(account: Account) = startPaginationWithEvidence(account, UUID.randomUUID().toString(), false)
    internal fun startPaginationWithEvidence(account: Account, captureId: String, resume: Boolean, timeoutMillis: Long = 8 * 60_000L,
        focusOrderId: String? = null) =
        startWithEvidence(account, captureId, timeoutMillis, importOrders = true, pagination = true, resume = resume, focusOrderId = focusOrderId)

    internal fun startOrdersWithEvidence(account: Account, captureId: String, timeoutMillis: Long = 8 * 60_000L) =
        startWithEvidence(account, captureId, timeoutMillis, true)

    internal fun startWithEvidence(account: Account, captureId: String, timeoutMillis: Long = 8 * 60_000L, importOrders: Boolean = false,
        pagination: Boolean = false, resume: Boolean = false, focusOrderId: String? = null) {
        val connected = service ?: throw AppFailure("请开启抖音订单采集的无障碍权限", FailureKind.CONFIGURATION)
        require(account.platform == Platform.DOUYIN && account.enabled && !account.deleted)
        require(captureId.matches(Regex("[A-Za-z0-9-]+")) && timeoutMillis > 0)
        if (this.account != null) throw AppFailure("当前采集正在进行，请先结束当前采集", FailureKind.CONFIGURATION)
        connected.prepareEvidence(account, captureId)
        this.account = account
        this.captureId = captureId
        phase = "未指定页面"
        terminationReason = ""
        expiresAt = SystemClock.elapsedRealtime() + timeoutMillis
        mutablePage.value = null
        navigationMode = false
        mutableStatus.value = "正在读取抖音页面，请打开商城订单"
        if (importOrders) connected.startOrders(account, captureId, timeoutMillis, pagination, resume, focusOrderId)
        connected.enableCapture(true)
    }

    fun normalNavigation() {
        check(activeAccount() != null) { "采集尚未开始" }
        navigationMode = true
        service?.orderSession?.beginNavigation()
        service?.requestTouchExploration(false)
        report("正常触摸导航中；打开目标页面后点击恢复采集，并重新核对账号")
    }

    fun resumeCapture() {
        check(activeAccount() != null) { "采集尚未开始" }
        service?.orderSession?.resumeCapture()
        navigationMode = false
        service?.requestTouchExploration(true)
        report("采集已恢复，请打开账号页重新核对抖音号")
    }

    fun activeAccount(): Account? {
        if (account != null && SystemClock.elapsedRealtime() >= expiresAt) stop("采集时间已结束，已保存的记录保留")
        return account
    }

    fun receive(page: NativePage) {
        check(page.packageName == PACKAGE)
        if (page.captureId != captureId || page.accountId != account?.id) return
        mutablePage.value = page
    }

    fun stop(message: String = "采集已经停止") {
        terminationReason = message
        service?.enableCapture(false)
        if (account != null) service?.recordEnd(message)
        account = null
        navigationMode = false
        mutableStatus.value = message
    }

    fun report(message: String) { mutableStatus.value = message }

    internal fun markPhase(value: String) {
        require(value in setOf("账号", "订单列表", "订单详情", "物流", "未指定页面"))
        phase = value
    }
}

class DouyinCaptureService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastCaptureAt = 0L
    private var lastBody = ""
    private var captureJob: Job? = null
    private var eventCaptureJob: Job? = null
    private var orderJob: Job? = null
    private var orderScans: Channel<NativeScan>? = null
    private var navigationJob: Job? = null
    private var navigationSequence = 0
    internal var orderSession: NativeOrderImportSession? = null
        private set
    private val captureLock = Mutex()
    private var eventType = 0
    private var eventClassName = ""
    private var sequence = 0
    private var directory: File? = null
    private var evidenceAccount: Account? = null
    private var startedAt = 0L
    private val recentPages = ArrayDeque<NativePage>()
    private var eventSequence = 0
    private var eventChannel: Channel<NativeEventEvidence>? = null
    private var eventWriter: Job? = null
    private var actionSequence = 0

    override fun onServiceConnected() {
        DouyinCaptureController.attach(this)
        enableCapture(false)
        val previous = DouyinCaptureController.activeAccount() ?: return
        DouyinCaptureController.stop("采集服务已重新连接，上一轮采集已停止")
        val container = (application as LogisticsApplication).container
        scope.launch {
            container.syncCoordinator.nativeProgressStore.stop(previous, "采集服务已重新连接，上一轮采集已停止")
            container.repository.setSyncState(previous.id, AccountState.PARTIAL, "采集服务已重新连接，上一轮采集已停止，请重新开始")
        }
    }

    internal fun enableCapture(enabled: Boolean) {
        captureJob?.cancel()
        eventCaptureJob?.cancel()
        requestTouchExploration(enabled)
        if (!enabled) { navigationJob?.cancel(); orderJob?.cancel(); orderScans?.cancel(); eventChannel?.close() }
        if (enabled) {
            lastBody = ""
            lastCaptureAt = 0L
            captureJob = scope.launch {
                while (DouyinCaptureController.activeAccount() != null) {
                    capture(false)
                    delay(750)
                }
            }
        }
    }

    internal fun requestTouchExploration(enabled: Boolean) {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            flags = if (enabled) flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
                else flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
        }
    }

    internal fun startOrders(account: Account, captureId: String, timeoutMillis: Long, pagination: Boolean = false, resume: Boolean = false,
        focusOrderId: String? = null) {
        check(orderJob?.isActive != true)
        orderSession = null
        val channel = Channel<NativeScan>(Channel.UNLIMITED)
        orderScans = channel
        val target = File(filesDir, "native-entry-evidence/$captureId/order-import.enc")
        val container = (application as LogisticsApplication).container
        orderJob = scope.launch {
            try {
                container.syncCoordinator.captureNativeAccount(account.id, captureId, channel, timeoutMillis,
                    onSession = { session ->
                        orderSession = session
                        if (pagination) navigationJob = scope.launch {
                            try { NativePaginationRunner(this@DouyinCaptureService, account, session, focusOrderId).run(); channel.close() }
                            catch (failure: AppFailure) { channel.close(failure) }
                        }
                    },
                    onFinished = { value ->
                        withContext(Dispatchers.IO) {
                            try { target.writeText(container.credentials.encrypt(value.evidence())) }
                            catch (failure: IOException) { throw AppFailure("采集资料无法保存", FailureKind.SERVICE_ERROR) }
                        }
                        if (pagination) DouyinCaptureController.report(value.message())
                    }, pagination = pagination, resume = resume, terminationReason = { DouyinCaptureController.terminationReason })
            } catch (failure: Throwable) {
                DouyinCaptureController.stop()
                DouyinCaptureController.report(failure.message ?: "采集已停止")
                if (failure is CancellationException) throw failure
            } finally {
                if (DouyinCaptureController.captureId == captureId && DouyinCaptureController.account != null) DouyinCaptureController.stop()
            }
        }
    }

    internal suspend fun awaitOrdersFinished() { orderJob?.join(); navigationJob?.join(); eventWriter?.join() }
    internal suspend fun paginationEvidence(value: JsonElement) {
        if (!diagnosticsEnabled()) return
        val name = "pagination-${navigationSequence++.toString().padStart(6, '0')}.enc"
        val target = File(checkNotNull(directory), name)
        val credentials = (application as LogisticsApplication).container.credentials
        withContext(Dispatchers.IO) { target.writeText(credentials.encrypt(value.toString())) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != DouyinCaptureController.PACKAGE) return
        eventType = event.eventType
        eventClassName = event.className?.toString().orEmpty()
        DouyinCaptureController.activeAccount()?.let { account ->
            val source = event.source?.takeIf { it.packageName?.toString() == DouyinCaptureController.PACKAGE }
            val value = NativeEventEvidence(DouyinCaptureController.captureId, account.id, eventSequence++,
                System.currentTimeMillis(), event.eventType, event.windowId, eventClassName,
                event.text.map { it.toString() }, event.contentDescription?.toString().orEmpty(),
                source?.let { readNativeNode(it, emptyList()) })
            eventChannel?.trySend(value)
        }
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && DouyinCaptureController.activeAccount() != null) {
            val source = event.source
            if (source != null) {
                val bounds = Rect().also(source::getBoundsInScreen)
                val matches = recentPages.reversed().filter { it.windowId == source.windowId && System.currentTimeMillis() - it.capturedAt <= 3_000 }
                    .flatMap { page -> page.nodes.filter { it.className == source.className?.toString().orEmpty() &&
                        it.text == source.text?.toString().orEmpty() && it.description == source.contentDescription?.toString().orEmpty() &&
                        it.bounds == listOf(bounds.left, bounds.top, bounds.right, bounds.bottom) }.map { page to it } }
                matches.firstOrNull()?.let { (page, node) -> orderSession?.navigation(page, node) }
            }
        }
        if (DouyinCaptureController.activeAccount() != null && eventCaptureJob?.isActive != true &&
            !captureLock.isLocked && System.currentTimeMillis() - lastCaptureAt >= 500) {
            eventCaptureJob = scope.launch { capture(false) }
        }
    }

    internal fun prepareEvidence(account: Account, captureId: String) {
        checkMainThread()
        directory = File(filesDir, "native-entry-evidence/$captureId").apply { check(mkdirs()) }
        evidenceAccount = account
        startedAt = System.currentTimeMillis()
        sequence = 0
        recentPages.clear()
        eventSequence = 0
        actionSequence = 0
        navigationSequence = 0
        cleanupEvidence()
        val diagnostics = diagnosticsEnabled()
        val channel = if (diagnostics) Channel<NativeEventEvidence>(Channel.UNLIMITED) else null
        eventChannel = channel
        if (channel != null) {
            val target = checkNotNull(directory)
            val credentials = (application as LogisticsApplication).container.credentials
            eventWriter = scope.launch(Dispatchers.IO) {
                for (event in channel) {
                    val file = File(target, "event-${event.sequence.toString().padStart(6, '0')}.enc")
                    val pending = File(file.path + ".pending")
                    pending.writeText(credentials.encrypt(platformJson.encodeToString(event)))
                    check(pending.renameTo(file))
                }
            }
        }
    }

    private fun diagnosticsEnabled() = (application as LogisticsApplication).container.settings.current().nativeEvidenceVerbose

    private fun cleanupEvidence() = NativeEvidenceStore.cleanup(filesDir)

    internal fun recordEnd(message: String) {
        val account = evidenceAccount ?: return
        val target = directory ?: return
        val container = (application as LogisticsApplication).container
        val files = target.walkTopDown().filter { it.isFile }.toList()
        val body = buildJsonObject {
            put("captureId", DouyinCaptureController.captureId)
            put("accountId", account.id)
            put("message", message)
            put("capturedAt", System.currentTimeMillis())
            put("scans", sequence)
            put("files", files.size)
            put("bytes", files.sumOf { it.length() })
            put("elapsedMillis", System.currentTimeMillis() - startedAt)
            put("diagnostics", diagnosticsEnabled())
            put("serviceConnected", DouyinCaptureController.service === this@DouyinCaptureService)
            put("touchExplorationRequested", serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0)
        }.toString()
        File(target, "end.enc").writeText(container.credentials.encrypt(body))
        cleanupEvidence()
    }

    internal suspend fun captureScreen(record: Boolean = false): OcrScreen? = suspendCancellableCoroutine { continuation ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                screenshot.hardwareBuffer.close()
                if (bitmap == null) {
                    if (continuation.isActive) continuation.resume(null)
                    return
                }
                val client = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                client.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result ->
                        client.close()
                        val lines = result.textBlocks.flatMap { block -> block.lines.mapNotNull { line ->
                            val box = line.boundingBox ?: return@mapNotNull null
                            OcrLine(line.text, listOf(box.left, box.top, box.right, box.bottom))
                        } }
                        val screen = OcrScreen(result.text, lines)
                        if (record && diagnosticsEnabled()) {
                            scope.launch(Dispatchers.Default) {
                                val bytes = ByteArrayOutputStream()
                                if (bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bytes))
                                    DouyinCaptureController.recordOcr(DouyinCaptureController.OcrEvidenceRecord(
                                        System.currentTimeMillis(), screen.text, lines, bytes.toByteArray()))
                            }
                        }
                        if (continuation.isActive) continuation.resume(screen)
                    }
                    .addOnFailureListener {
                        client.close()
                        if (continuation.isActive) continuation.resume(null)
                    }
            }
            override fun onFailure(errorCode: Int) {
                if (continuation.isActive) continuation.resume(null)
            }
        })
    }

    private fun screenTextLine(screen: OcrScreen, target: String): OcrLine? {
        val compact = target.replace(Regex("\\s+"), "")
        return screen.lines.firstOrNull { it.bounds.size == 4 && it.text.replace(Regex("\\s+"), "") == compact }
            ?: screen.lines.firstOrNull { it.bounds.size == 4 && it.text.replace(Regex("\\s+"), "").contains(compact) &&
                it.text.length <= compact.length + 12 }
    }

    internal suspend fun tapScreenText(target: String): NativeActionResult = withContext(Dispatchers.Main.immediate) {
        val allowed = setOf("更多", "查看物流", "展开全部物流信息", "查看更多物流信息", "查看全部物流信息")
        if (target !in allowed) return@withContext NativeActionResult(false, "该文字不在允许点击的读取入口内", "gesture")
        if (DouyinCaptureController.navigationMode) return@withContext NativeActionResult(false, "当前处于正常触摸导航", "gesture")
        val captureId = DouyinCaptureController.captureId
        val account = DouyinCaptureController.activeAccount()
            ?: return@withContext NativeActionResult(false, "当前没有进行中的采集", "gesture")
        val foreground = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (foreground != DouyinCaptureController.PACKAGE)
            return@withContext NativeActionResult(false, "当前前台不是抖音：${foreground.ifBlank { "未知" }}", "gesture")
        var screen = withTimeoutOrNull(8_000) { captureScreen() }
            ?: return@withContext NativeActionResult(false, "截图识别超时", "gesture")
        var found = screenTextLine(screen, target)
        if (found == null) {
            delay(600)
            screen = withTimeoutOrNull(8_000) { captureScreen() }
                ?: return@withContext NativeActionResult(false, "截图识别超时", "gesture")
            found = screenTextLine(screen, target)
        }
        val line = found ?: return@withContext NativeActionResult(false, "当前页面没有出现“$target”，识别行数 ${screen.lines.size}", "gesture")
        val requested = serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0
        requestTouchExploration(false)
        try {
            val manager = getSystemService(AccessibilityManager::class.java)
            val ready = withTimeoutOrNull(1_500) { while (manager.isTouchExplorationEnabled) delay(25); true } == true
            if (!ready) return@withContext NativeActionResult(false, "系统触摸模式尚未解除", "gesture")
            val active = rootInActiveWindow?.packageName?.toString().orEmpty()
            if (active != DouyinCaptureController.PACKAGE) return@withContext NativeActionResult(false, "点击前前台已经变化：${active.ifBlank { "未知" }}", "gesture")
            val path = Path().apply { moveTo((line.bounds[0] + line.bounds[2]) / 2f, (line.bounds[1] + line.bounds[3]) / 2f) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
            val completed = suspendCancellableCoroutine<Boolean> { continuation ->
                val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }, null)
                if (!accepted && continuation.isActive) continuation.resume(false)
            }
            if (diagnosticsEnabled()) {
                val body = buildJsonObject {
                    put("captureId", captureId); put("accountId", account.id); put("action", "TAP_SCREEN_TEXT"); put("text", target)
                    put("bounds", JsonArray(line.bounds.map(::JsonPrimitive))); put("accepted", completed)
                    put("capturedAt", System.currentTimeMillis())
                }.toString()
                val credentials = (application as LogisticsApplication).container.credentials
                withContext(Dispatchers.IO) {
                    File(checkNotNull(directory), "tap-${(actionSequence++).toString().padStart(6, '0')}.enc").writeText(credentials.encrypt(body))
                }
            }
            NativeActionResult(completed, if (completed) "" else "系统取消或拒绝点击手势", "gesture")
        } finally {
            if (DouyinCaptureController.captureId == captureId)
                requestTouchExploration(DouyinCaptureController.activeAccount() != null && requested && !DouyinCaptureController.navigationMode)
        }
    }


    internal suspend fun tapNode(page: NativePage, node: NativeNode): NativeActionResult = withContext(Dispatchers.Main.immediate) {
        if (DouyinCaptureController.navigationMode) return@withContext NativeActionResult(false, "当前处于正常触摸导航", "gesture")
        val captureId = DouyinCaptureController.captureId
        val account = DouyinCaptureController.activeAccount() ?: return@withContext NativeActionResult(false, "当前没有进行中的采集", "gesture")
        if (page.accountId != account.id || page.captureId != captureId)
            return@withContext NativeActionResult(false, "采集账号或采集编号已经变化", "gesture")
        if (node.bounds.size != 4) return@withContext NativeActionResult(false, "目标范围无效", "gesture")
        val foreground = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (foreground != DouyinCaptureController.PACKAGE)
            return@withContext NativeActionResult(false, "当前前台不是抖音：${foreground.ifBlank { "未知" }}", "gesture")
        val root = rootInActiveWindow ?: return@withContext NativeActionResult(false, "活动窗口没有根节点", "gesture")
        val windowBounds = Rect().also(root::getBoundsInScreen)
        val centerX = (node.bounds[0] + node.bounds[2]) / 2f
        val centerY = (node.bounds[1] + node.bounds[3]) / 2f
        if (!windowBounds.contains(centerX.toInt(), centerY.toInt()))
            return@withContext NativeActionResult(false, "目标范围不在当前官方窗口内", "gesture")
        val requested = serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0
        requestTouchExploration(false)
        try {
            val manager = getSystemService(AccessibilityManager::class.java)
            val ready = withTimeoutOrNull(1_500) { while (manager.isTouchExplorationEnabled) delay(25); true } == true
            if (!ready) return@withContext NativeActionResult(false, "系统触摸模式尚未解除", "gesture")
            val path = Path().apply { moveTo(centerX, centerY) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
            val completed = suspendCancellableCoroutine<Boolean> { continuation ->
                val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }, null)
                if (!accepted && continuation.isActive) continuation.resume(false)
            }
            if (diagnosticsEnabled()) {
                val body = buildJsonObject {
                    put("captureId", captureId); put("accountId", account.id); put("action", "TAP_NODE")
                    put("bounds", JsonArray(node.bounds.map(::JsonPrimitive))); put("accepted", completed)
                    put("capturedAt", System.currentTimeMillis())
                }.toString()
                val credentials = (application as LogisticsApplication).container.credentials
                withContext(Dispatchers.IO) {
                    File(checkNotNull(directory), "tap-${(actionSequence++).toString().padStart(6, '0')}.enc").writeText(credentials.encrypt(body))
                }
            }
            NativeActionResult(completed, if (completed) "" else "系统取消或拒绝点击手势", "gesture")
        } finally {
            if (DouyinCaptureController.captureId == captureId)
                requestTouchExploration(DouyinCaptureController.activeAccount() != null && requested && !DouyinCaptureController.navigationMode)
        }
    }


    internal suspend fun scrollList(page: NativePage, node: NativeNode, forward: Boolean): NativeActionResult = withContext(Dispatchers.Main.immediate) {
        if (DouyinCaptureController.navigationMode) return@withContext NativeActionResult(false, "当前处于正常触摸导航", "gesture")
        val captureId = DouyinCaptureController.captureId
        val account = DouyinCaptureController.activeAccount() ?: return@withContext NativeActionResult(false, "当前没有进行中的采集", "gesture")
        if (page.accountId != account.id || page.captureId != captureId)
            return@withContext NativeActionResult(false, "采集账号或采集编号已经变化", "gesture")
        if (node.bounds.size != 4) return@withContext NativeActionResult(false, "目标范围无效", "gesture")
        val foreground = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (foreground != DouyinCaptureController.PACKAGE)
            return@withContext NativeActionResult(false, "当前前台不是抖音：${foreground.ifBlank { "未知" }}", "gesture")
        val height = (node.bounds[3] - node.bounds[1]).toFloat()
        val x = (node.bounds[0] + node.bounds[2]) / 2f
        val fromY = if (forward) node.bounds[1] + height * 0.75f else node.bounds[1] + height * 0.25f
        val toY = if (forward) node.bounds[1] + height * 0.25f else node.bounds[1] + height * 0.75f
        val requested = serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0
        requestTouchExploration(false)
        try {
            val manager = getSystemService(AccessibilityManager::class.java)
            val ready = withTimeoutOrNull(1_500) { while (manager.isTouchExplorationEnabled) delay(25); true } == true
            if (!ready) return@withContext NativeActionResult(false, "系统触摸模式尚未解除", "gesture")
            val path = Path().apply { moveTo(x, fromY); lineTo(x, toY) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 400)).build()
            val completed = suspendCancellableCoroutine<Boolean> { continuation ->
                val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }, null)
                if (!accepted && continuation.isActive) continuation.resume(false)
            }
            if (diagnosticsEnabled()) {
                val body = buildJsonObject {
                    put("captureId", captureId); put("accountId", account.id); put("action", "SCROLL_GESTURE")
                    put("forward", forward); put("fromY", fromY); put("toY", toY); put("accepted", completed)
                    put("capturedAt", System.currentTimeMillis())
                }.toString()
                val credentials = (application as LogisticsApplication).container.credentials
                withContext(Dispatchers.IO) {
                    File(checkNotNull(directory), "tap-${(actionSequence++).toString().padStart(6, '0')}.enc").writeText(credentials.encrypt(body))
                }
            }
            NativeActionResult(completed, if (completed) "" else "系统取消或拒绝滑动手势", "gesture")
        } finally {
            if (DouyinCaptureController.captureId == captureId)
                requestTouchExploration(DouyinCaptureController.activeAccount() != null && requested && !DouyinCaptureController.navigationMode)
        }
    }

    internal suspend fun captureNow(): NativeScan =
        capture(true) ?: throw AppFailure("采集已经停止", FailureKind.CONFIGURATION)

    internal suspend fun captureRefreshed(path: List<Int>): NativeScan =
        capture(true, path) ?: throw AppFailure("采集已经停止", FailureKind.CONFIGURATION)

    private suspend fun capture(force: Boolean, refreshPath: List<Int>? = null): NativeScan? {
        val requestedAt = SystemClock.elapsedRealtime()
        return withContext(Dispatchers.Main.immediate) { captureLock.withLock {
        val enteredAt = SystemClock.elapsedRealtime()
        val account = DouyinCaptureController.activeAccount() ?: return@withLock null
        val container = (application as LogisticsApplication).container
        container.ready.await()
        val current = checkNotNull(container.repository.getAccount(account.id))
        if (!current.enabled || current.deleted) {
            DouyinCaptureController.stop("采集账号已停用")
            return@withLock null
        }
        val captureId = DouyinCaptureController.captureId
        val targetDirectory = checkNotNull(directory)
        val phase = DouyinCaptureController.phase
        val capturedAt = System.currentTimeMillis()
        val pages = mutableListOf<NativePage>()
        val recordedWindows = mutableListOf<NativeWindow>()
        val reasons = mutableListOf<String>()
        val refreshResults = mutableListOf<NativeRefreshResult>()
        val textQueries = mutableListOf<NativeTextQuery>()
        val roots = linkedMapOf<Int, AccessibilityNodeInfo>()
        val readStartedAt = SystemClock.elapsedRealtime()
        for (window in windows) {
            val root = window.root
            val accepted = root?.packageName?.toString() == DouyinCaptureController.PACKAGE
            val reason = if (root == null) "窗口没有提供根节点" else if (!accepted) "窗口包名不匹配" else ""
            val bounds = Rect().also(window::getBoundsInScreen)
            recordedWindows.add(NativeWindow(window.id, window.type, window.layer, window.isActive, window.isFocused,
                listOf(bounds.left, bounds.top, bounds.right, bounds.bottom), accepted, reason))
            if (accepted) roots[window.id] = checkNotNull(root)
        }
        val activeRoot = rootInActiveWindow
        if (activeRoot == null) reasons.add("活动窗口没有提供根节点")
        else if (activeRoot.packageName?.toString() != DouyinCaptureController.PACKAGE) reasons.add("活动窗口包名不匹配")
        else roots[activeRoot.windowId] = activeRoot
        for ((windowId, root) in roots) {
            val nodes = mutableListOf<NativeNode>()
            fun visit(node: AccessibilityNodeInfo, path: List<Int>) {
                if (refreshPath != null && (path.isEmpty() || path.take(refreshPath.size) == refreshPath)) {
                    val refreshed = node.refresh()
                    refreshResults.add(NativeRefreshResult(windowId, path, refreshed))
                    if (!refreshed) { reasons.add("目标节点刷新失败：${path.joinToString("/")}"); return }
                }
                nodes.add(readNativeNode(node, path))
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index)
                    if (child == null) reasons.add("窗口${windowId}节点子项无法读取：${(path + index).joinToString("/")}")
                    else visit(child, path + index)
                }
            }
            visit(root, emptyList())
            if (force && phase == "订单详情") for (text in listOf("更多信息", "下单时间", "付款时间", "发货时间")) {
                val found = root.findAccessibilityNodeInfosByText(text).filter {
                    it.packageName?.toString() == DouyinCaptureController.PACKAGE && it.windowId == windowId
                }
                val matched = found.mapNotNull { node -> nativePath(root, node)?.let { readNativeNode(node, it) } }
                textQueries.add(NativeTextQuery(windowId, text, matched, found.size - matched.size))
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(platformJson.encodeToString(nodes).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            pages.add(NativePage(DouyinCaptureController.PACKAGE, nodes, capturedAt, captureId, account.id,
                windowId, hash, nodes.size, nodes.count { !it.visible }))
        }
        if (pages.isEmpty()) reasons.add("没有可读取的抖音窗口")
        val readFinishedAt = SystemClock.elapsedRealtime()
        for (page in pages) { recentPages.addLast(page); while (recentPages.size > 8) recentPages.removeFirst() }
        val body = platformJson.encodeToString(pages.map { it.nodes }) + platformJson.encodeToString(reasons) + phase
        lastCaptureAt = capturedAt
        if (!force && body == lastBody) return@withLock null
        lastBody = body
        val scan = NativeScan(captureId, account.id, sequence++, capturedAt, phase, eventType, eventClassName, recordedWindows, pages, reasons,
            DouyinCaptureController.service === this@DouyinCaptureService, serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0,
            refreshResults, textQueries)
        if (diagnosticsEnabled()) withContext(Dispatchers.IO) {
            File(targetDirectory, "scan-${scan.sequence.toString().padStart(6, '0')}.enc")
                .writeText(container.credentials.encrypt(platformJson.encodeToString(scan)))
        }
        for (page in pages) DouyinCaptureController.receive(page)
        orderScans?.takeIf { orderJob?.isActive == true }?.send(scan)
        orderSession?.let { if (!DouyinCaptureController.navigationMode) DouyinCaptureController.report(it.message()) }
        if (diagnosticsEnabled()) {
            val timing = buildJsonObject {
                put("sequence", scan.sequence); put("forced", force)
                put("waitMillis", enteredAt - requestedAt); put("readMillis", readFinishedAt - readStartedAt)
                put("saveMillis", SystemClock.elapsedRealtime() - readFinishedAt)
            }.toString()
            withContext(Dispatchers.IO) { File(targetDirectory, "timing-${scan.sequence.toString().padStart(6,'0')}.enc").writeText(container.credentials.encrypt(timing)) }
        }
        scan
    } } }

    internal fun perform(path: List<Int>, action: Int): Boolean {
        val page = DouyinCaptureController.page.value ?: return false
        val expected = page.nodes.singleOrNull { it.path == path } ?: return false
        return perform(page, expected, action)
    }

    internal fun perform(page: NativePage, expected: NativeNode, action: Int, anchor: NativeNode = expected): Boolean =
        performChecked(page, expected, action, anchor).accepted

    internal suspend fun activateChecked(page: NativePage, anchor: NativeNode,
        purpose: NativeActionPurpose = NativeActionPolicy.purpose(page, anchor)): NativeActionResult = withContext(Dispatchers.Main.immediate) {
        checkMainThread()
        val captureId = DouyinCaptureController.captureId
        val targetDirectory = checkNotNull(directory)
        val number = actionSequence++
        suspend fun activate(): NativeActionResult {
            if (DouyinCaptureController.navigationMode) return NativeActionResult(false, "当前处于正常触摸导航")
            NativeActionPolicy.rejection(page, anchor, intended = purpose)?.let { return NativeActionResult(false, it) }
            if (DouyinCaptureController.activeAccount()?.id != page.accountId || captureId != page.captureId)
                return NativeActionResult(false, "采集账号或采集编号已经变化")
            val root = rootInActiveWindow ?: return NativeActionResult(false, "活动窗口没有根节点")
            if (root.windowId != page.windowId || root.packageName?.toString() != DouyinCaptureController.PACKAGE)
                return NativeActionResult(false, "当前活动窗口已经变化")
            var node = root
            val ancestors = mutableListOf(readNativeNode(root, emptyList()))
            for ((depth, index) in anchor.path.withIndex()) {
                if (index !in 0 until node.childCount) return NativeActionResult(false, "当前节点路径已经变化")
                node = node.getChild(index) ?: return NativeActionResult(false, "当前节点路径已经变化")
                ancestors.add(readNativeNode(node, anchor.path.take(depth + 1)))
            }
            val current = readNativeNode(node, anchor.path)
            if (!current.visible || !current.enabled || current.text != anchor.text || current.description != anchor.description ||
                current.resourceId != anchor.resourceId || current.className != anchor.className || current.bounds != anchor.bounds)
                return NativeActionResult(false, "目标节点文字或范围已经变化")
            val clickable = ancestors.lastOrNull { it.visible && it.enabled && AccessibilityNodeInfo.ACTION_CLICK in it.actions }
            if (clickable != null) {
                NativeActionPolicy.rejection(page, anchor, clickable, purpose)?.let { return NativeActionResult(false, it) }
                return performChecked(page, clickable, AccessibilityNodeInfo.ACTION_CLICK, anchor)
            }
            val bounds = Rect(current.bounds[0], current.bounds[1], current.bounds[2], current.bounds[3])
            val windowBounds = Rect().also(root::getBoundsInScreen)
            if (bounds.isEmpty || !windowBounds.contains(bounds.centerX(), bounds.centerY()))
                return NativeActionResult(false, "节点范围不在当前官方窗口内", "gesture")
            val requested = serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0
            requestTouchExploration(false)
            try {
                val manager = getSystemService(AccessibilityManager::class.java)
                val ready = withTimeoutOrNull(1_500) { while (manager.isTouchExplorationEnabled) delay(25); true } == true
                if (!ready) return NativeActionResult(false, "系统触摸模式尚未解除", "gesture")
                val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
                val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
                val completed = suspendCancellableCoroutine<Boolean> { continuation ->
                    val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }, null)
                    if (!accepted && continuation.isActive) continuation.resume(false)
                }
                if (completed) orderSession?.navigation(page, anchor)
                return NativeActionResult(completed, if (completed) "" else "系统取消或拒绝点击手势", "gesture")
            } finally {
                if (DouyinCaptureController.captureId == captureId)
                    requestTouchExploration(DouyinCaptureController.activeAccount() != null && requested && !DouyinCaptureController.navigationMode)
            }
        }
        val result = activate()
        val body = buildJsonObject {
            put("captureId", captureId); put("accountId", page.accountId); put("capturedAt", System.currentTimeMillis())
            put("windowId", page.windowId); put("method", result.method); put("accepted", result.accepted); put("reason", result.reason)
            put("purpose", purpose.name)
            put("target", platformJson.parseToJsonElement(platformJson.encodeToString(anchor)))
            put("touchExplorationRequested", serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0)
        }.toString()
        if (diagnosticsEnabled()) {
            val credentials = (application as LogisticsApplication).container.credentials
            withContext(Dispatchers.IO) { File(targetDirectory, "action-${number.toString().padStart(6, '0')}.enc").writeText(credentials.encrypt(body)) }
        }
        result
    }

    internal fun performChecked(page: NativePage, expected: NativeNode, action: Int, anchor: NativeNode = expected): NativeActionResult {
        checkMainThread()
        if (DouyinCaptureController.navigationMode) return NativeActionResult(false, "当前处于正常触摸导航")
        if (DouyinCaptureController.activeAccount()?.id != page.accountId || DouyinCaptureController.captureId != page.captureId)
            return NativeActionResult(false, "采集账号或采集编号已经变化")
        val root = rootInActiveWindow?.takeIf { it.windowId == page.windowId }
            ?: return NativeActionResult(false, "目标窗口已经变化或没有根节点")
        if (root.packageName?.toString() != DouyinCaptureController.PACKAGE) return NativeActionResult(false, "当前窗口包名不匹配")
        if (action == AccessibilityNodeInfo.ACTION_CLICK) NativeActionPolicy.rejection(page, anchor, expected)?.let { return NativeActionResult(false, it) }
        fun resolve(value: NativeNode): AccessibilityNodeInfo? {
            var node = root
            for (index in value.path) {
                if (index !in 0 until node.childCount) return null
                node = node.getChild(index) ?: return null
            }
            if (!node.isVisibleToUser || !node.isEnabled || node.text?.toString().orEmpty() != value.text ||
                node.contentDescription?.toString().orEmpty() != value.description || node.viewIdResourceName.orEmpty() != value.resourceId ||
                node.className?.toString().orEmpty() != value.className) return null
            val bounds = Rect().also(node::getBoundsInScreen)
            if (value.bounds != listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)) return null
            return node
        }
        if (resolve(anchor) == null) return NativeActionResult(false, "目标文字节点已经变化或无法操作")
        val target = resolve(expected) ?: return NativeActionResult(false, "目标动作节点已经变化或无法操作")
        if (action == AccessibilityNodeInfo.ACTION_CLICK) {
            fun containsTransaction(node: AccessibilityNodeInfo): Boolean {
                if (node.isVisibleToUser && NativeActionPolicy.forbidden(readNativeNode(node, emptyList()))) return true
                return (0 until node.childCount).any { index -> node.getChild(index)?.let(::containsTransaction) == true }
            }
            if (containsTransaction(target)) return NativeActionResult(false, "当前动作区域包含交易按钮，禁止自动点击")
        }
        if (target.actionList.none { it.id == action }) return NativeActionResult(false, "当前节点没有提供所需动作")
        val accepted = target.performAction(action)
        if (accepted && action == AccessibilityNodeInfo.ACTION_CLICK) orderSession?.navigation(page, anchor)
        return NativeActionResult(accepted, if (accepted) "" else "平台节点拒绝执行动作")
    }

    override fun onInterrupt() { DouyinCaptureController.stop("采集被系统中断") }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        DouyinCaptureController.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        DouyinCaptureController.stop("采集服务连接已结束")
        DouyinCaptureController.detach(this)
        scope.cancel()
        super.onDestroy()
    }
}
