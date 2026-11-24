package com.logisticsmanagement.app.platform

import com.logisticsmanagement.app.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.temporal.ChronoUnit

internal class NativeOrderImportSession(
    val accountId: String, val captureId: String,
    private val bind: suspend (String) -> Unit,
    private val existing: suspend (String) -> Order?,
    private val save: suspend (String, ImportedOrder) -> IngestResult,
    private val progress: (NativeOrderImportSession) -> Unit,
    saveLogistics: (suspend (String, String, ImportedShipment) -> Unit)? = null,
    internal val runProgress: NativeProgressSession? = null,
) {
    internal var acceptedSequence = -1
        private set
    internal fun currentCandidate() = candidate
    internal val logistics = saveLogistics?.let { NativeLogisticsImportSession(it) }
    var identity: String? = null
        private set
    var totals = IngestResult()
        private set
    val missing = linkedMapOf<String, List<String>>()
    val excluded = linkedSetOf<String>()
    private val fields = mutableListOf<NativeFieldEvidence>()
    private val emitted = mutableMapOf<String, ImportedOrder>()
    private var selectedTitle: String? = null
    private var selectedCard: Triple<List<Int>, Int, String>? = null
    private var selectedSection: Pair<List<Int>, IntRange>? = null
    private var cardFailure: String? = null
    private var candidate: NativeOrderCandidate? = null
    private var expectedLogistics: String? = null
    private val ordinaryIds = mutableSetOf<String>()
    private var navigationIdentity: String? = null
    private val navigationDetails = mutableMapOf<String, Pair<String, NativeOrderCandidate>>()
    private var paused = false
    private var navigationTarget: NativeOrderCandidate? = null
    private var informationPath: List<Int>? = null
    private val navigationDates = mutableListOf<NativeFieldEvidence>()
    private var lastDetailPage: NativePage? = null
    private val dateReadings = mutableMapOf<Pair<String, Long>, MutableSet<Int>>()

    fun invalidateIdentity() { identity = null; candidate = null; expectedLogistics = null; logistics?.reset(); progress(this) }
    fun beginNavigation() {
        navigationIdentity = identity
        navigationTarget = candidate
        informationPath = lastDetailPage?.let(DouyinNativeOrders::informationRoot)
        navigationDates.clear()
        paused = true
        invalidateIdentity()
    }
    fun resumeCapture() { invalidateIdentity(); paused = false }
    fun captureNavigation(scan: NativeScan) {
        check(scan.captureId == captureId && scan.accountId == accountId)
        check(scan.pages.all { it.reader == "service" })
        val owner = navigationIdentity ?: return
        DouyinNativeOrders.detail(scan, selectedTitle)?.let { value ->
            if (navigationTarget?.remoteId != value.remoteId) { navigationDates.clear(); informationPath = null }
            navigationTarget = value
            val previous = navigationDetails[value.remoteId]?.takeIf { it.first == owner }?.second
            navigationDetails[value.remoteId] = owner to value.copy(createdAt = value.createdAt ?: previous?.createdAt,
                fields = value.fields + previous?.fields.orEmpty())
            candidate = value
        }
        val target = navigationTarget
        val info = informationPath
        if (target != null && info != null) {
            val windowId = target.fields.first().windowId
            val page = scan.pages.singleOrNull { it.windowId == windowId }
            if (page == null) {
                navigationTarget = null
                navigationDates.clear()
                informationPath = null
            } else if (DouyinNativeOrders.informationRoot(page) == info) {
                DouyinNativeOrders.orderDate(page, scan.sequence, info)?.let { date ->
                    val field = date.copy(orderId = target.remoteId)
                    navigationDates.add(field)
                    fields.add(field)
                    val matching = navigationDates.filter { DouyinNativeOrders.orderTime(it.value) == DouyinNativeOrders.orderTime(date.value) }
                    if (matching.map { it.sequence }.distinct().size >= 2) {
                        val value = target.copy(createdAt = checkNotNull(DouyinNativeOrders.orderTime(date.value)),
                            fields = target.fields + matching)
                        navigationDetails[target.remoteId] = owner to value
                    }
                }
            }
        }
        DouyinNativeOrders.carrier(scan)?.let { carrier ->
            val id = expectedLogistics
            if (id != null && candidate?.remoteId == id) { ordinaryIds.add(id); fields.add(carrier.copy(orderId = id)) }
        }
    }

    fun navigation(page: NativePage, anchor: NativeNode) {
        if (logistics?.navigationWithinLogistics(page, anchor) == true) return
        logistics?.reset()
        val selected = if (DouyinNativeOrders.selectable(page, anchor)) anchor else if (DouyinNativeOrders.isList(page))
            page.nodes.firstOrNull { it.visible && it.path.take(anchor.path.size) == anchor.path && DouyinNativeOrders.selectable(page, it) } else null
        if (selected != null) {
            val card = DouyinNativeOrders.card(page, selected)
            val section = DouyinNativeOrders.section(page, selected)
            val signature = card?.let { Triple(it.first, it.second, DouyinNativeOrders.cardHash(page, it.first, it.second)) }
            if (selectedTitle == selected.text && selectedCard == signature && selectedSection == section) return
            selectedTitle = selected.text
            selectedCard = signature
            selectedSection = section
            cardFailure = if (card == null || section == null) DouyinNativeOrders.cardDiagnostics(page, selected) else null
            fields.add(NativeFieldEvidence("selectedTitle", selected.text, page.windowId, selected.path, -1))
            candidate = null
            expectedLogistics = null
        } else if (DouyinNativeOrders.label(anchor) == "查看物流" && DouyinNativeOrders.isList(page)) {
            val card = selectedCard ?: return
            val section = selectedSection ?: return
            val location = DouyinNativeOrders.section(page, anchor) ?: return
            val row = anchor.path.getOrNull(card.first.size) ?: return
            if (location.first == card.first && section.first == card.first && row in section.second &&
                DouyinNativeOrders.cardHash(page, card.first, card.second) == card.third) expectedLogistics = candidate?.remoteId
        } else if (DouyinNativeOrders.label(anchor) == "查看物流" && candidate != null &&
            page.nodes.any { it.visible && DouyinNativeOrders.label(it) == "订单编号" }) {
            expectedLogistics = candidate?.remoteId
            candidate?.let { logistics?.expect(it.remoteId) }
        }
    }

    suspend fun accept(scan: NativeScan) {
        check(scan.accountId == accountId && scan.captureId == captureId)
        check(scan.pages.all { it.packageName == DouyinCaptureController.PACKAGE && it.accountId == accountId && it.captureId == captureId && it.reader == "service" })
        if (paused) { captureNavigation(scan); acceptedSequence = scan.sequence; return }
        cardFailure?.let { message -> runProgress?.blocked("card", message); cardFailure = null }
        val prompts = scan.pages.flatMap { page -> page.nodes.filter { it.visible }.map(DouyinNativeOrders::label) }
        if (prompts.any { it in setOf("请先登录", "登录后查看订单", "请登录后查看订单") })
            throw AppFailure("抖音页面要求登录，请在官方App完成登录", FailureKind.NEEDS_LOGIN)
        if (prompts.any { it in setOf("请完成安全验证", "请完成滑块验证", "验证后继续") })
            throw AppFailure("抖音页面要求安全验证，请在官方App完成验证", FailureKind.NEEDS_VERIFICATION)
        DouyinNativeOrders.identity(scan)?.let {
            bind(it.value)
            if (paused) { identity = null; return }
            if (identity != it.value) {
                candidate = null
                expectedLogistics = null
                logistics?.reset()
                ordinaryIds.clear()
                dateReadings.clear()
            }
            identity = it.value
            fields.add(it)
        }
        val current = DouyinNativeOrders.detail(scan, selectedTitle)
        if (current != null) {
            if (identity != null) runProgress?.discovered(current)
            logistics?.detail(current.remoteId)
            current.createdAt?.let { date -> dateReadings.getOrPut(current.remoteId to date) { mutableSetOf() }.add(scan.sequence) }
            lastDetailPage = scan.pages.single { it.windowId == current.fields.first().windowId }
            fields.addAll(current.fields)
            val cached = navigationDetails[current.remoteId]?.takeIf { it.first == identity }?.second
            if (cached != null) fields.addAll(cached.fields)
            candidate = current.copy(createdAt = current.createdAt ?: cached?.createdAt,
                ordinary = current.ordinary || cached?.ordinary == true || current.remoteId in ordinaryIds,
                excluded = current.excluded || cached?.excluded == true)
        }
        DouyinNativeOrders.carrier(scan)?.let { carrier ->
            val orderId = expectedLogistics
            if (orderId != null && candidate?.remoteId == orderId) {
                ordinaryIds.add(orderId)
                candidate = candidate?.copy(ordinary = true)
                fields.add(carrier.copy(orderId = orderId))
            }
        }
        val incoming = candidate
        if (incoming != null && identity != null) {
            if (incoming.excluded) {
                excluded.add(incoming.remoteId)
                missing.remove(incoming.remoteId)
            } else {
                val stored = existing(incoming.remoteId)
                val resolved = incoming.copy(createdAt = incoming.createdAt ?: stored?.createdAt)
                val dateCount = resolved.createdAt?.let { date ->
                    dateReadings[incoming.remoteId to date].orEmpty().size.coerceAtLeast(resolved.fields.filter {
                        it.name in setOf("下单时间", "创建时间", "订单时间") && DouyinNativeOrders.orderTime(it.value) == date
                    }.map { it.sequence }.distinct().size)
                } ?: 0
                val absent = resolved.missing() + (if (identity == null) listOf("本次账号归属核查") else emptyList()) +
                    (if (resolved.status == ParcelStatus.SIGNED && resolved.createdAt != null && dateCount < 2)
                        listOf("下单时间连续两次读取核查") else emptyList())
                if (absent.isNotEmpty()) missing[incoming.remoteId] = absent
                else if (stored == null && resolved.status == ParcelStatus.SIGNED &&
                    checkNotNull(resolved.createdAt) < Instant.now().minus(90, ChronoUnit.DAYS).toEpochMilli()) {
                    excluded.add(incoming.remoteId)
                    missing.remove(incoming.remoteId)
                } else {
                    val order = resolved.imported()
                    if (emitted[order.remoteId] != order) {
                        totals += save(checkNotNull(identity), order)
                        emitted[order.remoteId] = order
                    }
                    missing.remove(order.remoteId)
                }
            }
        }
        val logisticsOrder = expectedLogistics?.let { existing(it) }
        logistics?.accept(scan, identity, logisticsOrder)
        runProgress?.observe(this)
        acceptedSequence = scan.sequence
        progress(this)
    }

    fun message() = runProgress?.value?.message() ?: ("${totals.description}；待补充字段 ${missing.size} 笔；本次未导入 ${excluded.size} 笔" +
        (logistics?.let { "；物流已保存 ${it.savedSources.size} 个；物流待补充字段 ${it.missing.size} 笔" } ?: "") + "；完整订单分页尚未采集" +
        if (identity == null) "；请打开账号页重新核对抖音号" else "")

    fun evidence(): String = buildJsonObject {
        put("captureId", captureId); put("accountId", accountId); put("identity", identity)
        put("inserted", totals.inserted); put("updated", totals.updated)
        put("missing", buildJsonObject { missing.forEach { (id, names) -> put(id, JsonArray(names.map(::JsonPrimitive))) } })
        put("excluded", JsonArray(excluded.map(::JsonPrimitive)))
        logistics?.let { put("logistics", it.evidence()) }
        runProgress?.let { put("pagination", platformJson.parseToJsonElement(platformJson.encodeToString(it.value))) }
        put("fields", platformJson.parseToJsonElement(platformJson.encodeToString(fields.distinct())))
    }.toString()
}
