package com.logisticsmanagement.app.platform

import com.logisticsmanagement.app.model.*
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
internal data class NativeFieldEvidence(val name: String, val value: String, val windowId: Int, val path: List<Int>, val sequence: Int, val orderId: String? = null,
    val valuePaths: List<List<Int>> = emptyList(), val sourceProperty: String = "text")

internal data class NativeOrderCandidate(val remoteId: String, val title: String?, val status: ParcelStatus,
    val createdAt: Long?, val ordinary: Boolean, val excluded: Boolean, val fields: List<NativeFieldEvidence>) {
    fun missing(): List<String> = buildList {
        if (title.isNullOrBlank()) add("商品名称")
        if (status == ParcelStatus.UNKNOWN) add("订单状态")
        if (status == ParcelStatus.SIGNED && createdAt == null) add("包含年份的下单时间")
        if (!ordinary && !excluded) add("普通零售配送依据")
    }
    fun imported() = ImportedOrder(remoteId, checkNotNull(title), status, createdAt, "", shipments = emptyList())
}

internal object DouyinNativeOrders {
    private val navigation = setOf("全部", "待发货", "待付款", "待收货/使用", "查看物流", "更多", "返回", "我的订单", "商城", "我")
    fun label(node: NativeNode) = node.text.ifBlank { node.description }.trim()
    fun textValues(node: NativeNode): List<Pair<String, String>> =
        (listOf("text" to node.text, "description" to node.description) + node.supplementalTexts.toList())
            .filter { it.second.isNotBlank() }.map { it.first to it.second.trim() }

    fun servicePages(scan: NativeScan): List<NativePage> = scan.pages.map { page ->
        val nodes = page.nodes.associateBy { it.path }.toMutableMap()
        for (node in scan.textQueries.filter { it.windowId == page.windowId }.flatMap { it.matches }) {
            val previous = nodes[node.path]
            if (previous == null || previous.text == node.text && previous.description == node.description &&
                previous.className == node.className && previous.resourceId == node.resourceId) nodes[node.path] = node
        }
        page.copy(nodes = nodes.values.toList())
    }
    fun isList(page: NativePage) = page.nodes.any { label(it).startsWith("待收货/使用") } &&
        page.nodes.none { label(it) == "订单编号" }

    fun identity(scan: NativeScan): NativeFieldEvidence? {
        val fields = scan.pages.filter { page -> page.nodes.any { it.visible && label(it).startsWith("我的订单") } }
            .flatMap { page -> page.nodes.filter { it.visible }.mapNotNull { node ->
            Regex("^抖音号\\s*[:：]\\s*([A-Za-z0-9_.-]+)$").matchEntire(label(node))?.let {
                NativeFieldEvidence("externalIdentity", it.groupValues[1], page.windowId, node.path, scan.sequence)
            }
        } }
        if (fields.map { it.value }.distinct().size > 1) throw AppFailure("当前页面提供多个抖音号，请重新核对账号", FailureKind.NEEDS_VERIFICATION)
        return fields.firstOrNull()
    }

    private fun field(page: NativePage, names: Set<String>, sequence: Int): NativeFieldEvidence? {
        for (node in page.nodes.filter { it.visible }) {
            val text = label(node)
            for (name in names) {
                if (text.startsWith("$name：") || text.startsWith("$name:")) {
                    return NativeFieldEvidence(name, text.drop(name.length + 1).trim(), page.windowId, node.path, sequence)
                }
                if (text == name) {
                    val prefix = node.path.dropLast(2)
                    val values = page.nodes.filter { it.visible && it.path.take(prefix.size) == prefix && label(it).isNotBlank() &&
                        label(it) !in names && label(it) !in setOf("复制", "展开", "收起") }
                    val leaves = values.filter { candidate -> values.none { it.path.size > candidate.path.size && it.path.take(candidate.path.size) == candidate.path } }
                    val texts = leaves.map(::label).distinct()
                    if (texts.size == 1) return NativeFieldEvidence(name, texts.single(), page.windowId, leaves.first().path, sequence)
                }
            }
        }
        return null
    }

    fun orderTime(value: String): Long? {
        val text = value.replace("年", "-").replace("月", "-").replace("日", " ").replace('.', '-').trim()
            .replace(Regex("\\s+"), " ")
        return text.takeIf { it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}(?::[0-9]{2})?")) }
            ?.let(::parsePlatformTime)
    }

    fun orderDate(page: NativePage, sequence: Int, prefix: List<Int> = emptyList()): NativeFieldEvidence? {
        val nodes = page.nodes.filter { it.visible && it.path.take(prefix.size) == prefix }
        val names = setOf("下单时间", "创建时间", "订单时间")
        val otherTimes = setOf("付款时间", "支付时间", "发货时间")
        for (node in nodes) for ((property, text) in textValues(node)) for (name in names) {
            if (text.startsWith("$name：") || text.startsWith("$name:")) {
                val value = text.drop(name.length + 1).trim()
                if (orderTime(value) != null) return NativeFieldEvidence(name, value, page.windowId, node.path, sequence,
                    valuePaths = listOf(node.path), sourceProperty = property)
            }
        }
        for (anchor in nodes.filter { textValues(it).any { value -> value.second in names } }) {
            val name = textValues(anchor).first { it.second in names }.second
            for (depth in 1..minOf(6, anchor.path.size - prefix.size)) {
                val group = anchor.path.dropLast(depth)
                val values = nodes.filter { it.path.take(group.size) == group }
                if (values.any { node -> textValues(node).any { it.second in otherTimes } } ||
                    values.count { node -> textValues(node).any { it.second in names } } > 1) break
                val dates = values.flatMap { node -> textValues(node).filter { orderTime(it.second) != null }.map { Triple(node, it.first, it.second) } }
                if (dates.map { orderTime(it.third) }.distinct().size > 1)
                    throw AppFailure("下单时间字段提供多个不同日期，请重新读取当前详情", FailureKind.UNSUPPORTED_RESPONSE)
                dates.firstOrNull()?.let { (node, property, value) ->
                    return NativeFieldEvidence(name, value, page.windowId, anchor.path, sequence,
                        valuePaths = listOf(node.path), sourceProperty = property)
                }
                val parts = values.filter { it.childCount == 0 }.mapNotNull { node ->
                    textValues(node).firstOrNull { value -> value.second.matches(Regex("[0-9]{4}[-.年][0-9]{2}[-.月][0-9]{2}日?|[0-9]{2}:[0-9]{2}(?::[0-9]{2})?")) }
                        ?.let { node to it }
                }
                val value = parts.joinToString(" ") { it.second.second }
                if (orderTime(value) != null) return NativeFieldEvidence(name, value, page.windowId, anchor.path, sequence,
                    valuePaths = parts.map { it.first.path }, sourceProperty = parts.joinToString("+") { it.second.first })
            }
        }
        return null
    }

    fun informationRoot(page: NativePage): List<Int>? {
        val header = page.nodes.singleOrNull { label(it) == "更多信息" } ?: return null
        val toggle = page.nodes.filter { label(it) in setOf("展开", "收起") }.singleOrNull { node ->
            header.path.zip(node.path).takeWhile { it.first == it.second }.size >= header.path.size - 5
        } ?: return null
        return header.path.zip(toggle.path).takeWhile { it.first == it.second }.map { it.first }
    }

    fun detail(scan: NativeScan, selectedTitle: String?): NativeOrderCandidate? {
        val page = servicePages(scan).singleOrNull { page -> page.nodes.any { it.visible && label(it) == "订单编号" } } ?: return null
        val id = field(page, setOf("订单编号"), scan.sequence)
            ?: throw AppFailure("抖音订单编号对应节点无法读取", FailureKind.UNSUPPORTED_RESPONSE)
        if (!id.value.matches(Regex("[0-9]{6,}"))) throw AppFailure("抖音订单编号格式尚未支持", FailureKind.UNSUPPORTED_RESPONSE)
        val visible = page.nodes.filter { it.visible }
        val explicitTitle = field(page, setOf("商品名称"), scan.sequence)
        val prefix = selectedTitle?.replace(Regex("\\s+"), "")?.take(10)?.takeIf { it.length >= 6 }
        val titleGroup = if (prefix == null) null else visible.mapNotNull { root ->
            val leaves = visible.filter { it.childCount == 0 && it.text.isNotBlank() && it.path.take(root.path.size) == root.path }
            val text = leaves.joinToString("") { it.text }.trim()
            if (text.length in 6..200 && text.replace(Regex("\\s+"), "").contains(prefix)) root to text else null
        }.maxByOrNull { it.first.path.size }
        val titleEvidence = explicitTitle ?: titleGroup?.let { NativeFieldEvidence("商品名称", it.second, page.windowId, it.first.path, scan.sequence) }
        val title = titleEvidence?.value
        val closed = setOf("交易关闭", "已关闭", "已取消", "已退款", "退款成功")
        val statusNode = visible.firstOrNull { label(it) in setOf("已签收", "交易成功", "交易完成", "已完成", "待发货", "待收货", "已发货", "运输中", "派送中", "待取件") + closed }
        val status = statusNode?.let { orderStatus(label(it)) } ?: ParcelStatus.UNKNOWN
        val date = orderDate(page, scan.sequence)
        val createdAt = date?.value?.let(::orderTime)
        val delivery = field(page, setOf("配送方式", "配送类型", "订单类型"), scan.sequence)
        val excluded = statusNode?.let { label(it) in closed } == true || delivery?.value?.let { value -> listOf("外卖", "小时达", "即时配送", "同城配送", "到家", "充值", "服务订单").any(value::contains) } == true
        val ordinary = !excluded && delivery?.value?.let { value -> listOf("普通快递", "快递配送", "快递发货", "标准快递").any(value::contains) } == true
        val fields = listOfNotNull(id, titleEvidence, date, delivery, statusNode?.let { NativeFieldEvidence("status", label(it), page.windowId, it.path, scan.sequence) })
        return NativeOrderCandidate(id.value, title, status, createdAt, ordinary, excluded, fields)
    }

    fun carrier(scan: NativeScan): NativeFieldEvidence? = scan.pages.firstNotNullOfOrNull { carrier(it, scan.sequence) }
    fun carrier(page: NativePage, sequence: Int = -1): NativeFieldEvidence? = page.nodes.filter { it.visible }.firstNotNullOfOrNull { node ->
        Regex("^([\\u4e00-\\u9fff]{2,20})\\s+([A-Za-z0-9-]{8,40})$").matchEntire(label(node))?.takeIf {
            it.groupValues[1].contains("快递") || it.groupValues[1].contains("物流")
        }?.let { NativeFieldEvidence("ordinaryCarrier", it.groupValues[1], page.windowId, node.path, sequence) }
    }

    fun selectable(page: NativePage, anchor: NativeNode) = isList(page) && anchor.text.isNotBlank() && label(anchor) !in navigation &&
        anchor.text.length >= 2 && anchor.path.size >= 15

    fun card(page: NativePage, anchor: NativeNode): Pair<List<Int>, Int>? {
        if (!isList(page) || anchor.path.size < 15) return null
        val prefix = anchor.path.take(14)
        val index = anchor.path[14]
        return prefix to index
    }

    fun cardHash(page: NativePage, prefix: List<Int>, index: Int): String {
        val values = page.nodes.filter { it.visible && it.path.size > prefix.size && it.path.take(prefix.size) == prefix &&
            it.path[prefix.size] in (index - 1)..(index + 2) }.flatMap { listOf(it.text, it.description) }
            .filter(String::isNotBlank).distinct().sorted().joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(values.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
