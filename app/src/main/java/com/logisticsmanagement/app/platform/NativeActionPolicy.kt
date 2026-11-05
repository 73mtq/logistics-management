package com.logisticsmanagement.app.platform

import android.view.accessibility.AccessibilityNodeInfo

private const val ACTION_CLICK = AccessibilityNodeInfo.ACTION_CLICK

internal enum class NativeActionPurpose { ACCOUNT, HOME, MALL, ORDERS, ALL, ORDER_DETAIL, ORDER_INFORMATION, LOGISTICS, LOGISTICS_INFORMATION, LOGISTICS_BODY, SCROLL, UNKNOWN }

internal object NativeActionPolicy {
    private val forbidden = listOf("确认收货", "确认签收", "立即付款", "立即支付", "去支付", "取消订单", "申请退款", "申请售后", "删除订单", "评价", "再来一单", "加入购物车", "立即购买")
    fun forbidden(node: NativeNode) = DouyinNativeOrders.textValues(node).any { (_, text) -> text != "待评价" && forbidden.any(text::contains) }
    fun descendant(path: List<Int>, parent: List<Int>) = path.size >= parent.size && path.take(parent.size) == parent
    fun requiresSemantic(purpose: NativeActionPurpose) = purpose in setOf(NativeActionPurpose.ORDER_DETAIL, NativeActionPurpose.LOGISTICS_BODY)
    private fun hasLabel(page: NativePage, label: String) = page.nodes.any { it.visible && DouyinNativeOrders.label(it) == label }

    fun purpose(page: NativePage, node: NativeNode): NativeActionPurpose {
        if (forbidden(node)) return NativeActionPurpose.UNKNOWN
        val label = DouyinNativeOrders.label(node)
        return when {
            label == "我" && hasLabel(page, "首页") -> NativeActionPurpose.ACCOUNT
            label == "首页" && hasLabel(page, "我") -> NativeActionPurpose.HOME
            label == "商城" && hasLabel(page, "我") -> NativeActionPurpose.MALL
            label in setOf("我的订单", "我的订单 按钮") -> NativeActionPurpose.ORDERS
            label == "全部" && DouyinNativeOrders.isList(page) -> NativeActionPurpose.ALL
            NativePaginationRules.cardsOnPage(page).any { it.node == node } -> NativeActionPurpose.ORDER_DETAIL
            label == "展开" && hasLabel(page, "更多信息") && hasLabel(page, "订单编号") && informationScope(page, node) != null -> NativeActionPurpose.ORDER_INFORMATION
            label == "查看物流" && hasLabel(page, "订单编号") -> NativeActionPurpose.LOGISTICS
            label == "查看更多物流信息" && logisticsPage(page) -> NativeActionPurpose.LOGISTICS_INFORMATION
            label.endsWith("展开") && label.length > 10 && logisticsPage(page) && eventScope(page, node) != null -> NativeActionPurpose.LOGISTICS_BODY
            else -> NativeActionPurpose.UNKNOWN
        }
    }

    private fun logisticsPage(page: NativePage) = !DouyinNativeOrders.isList(page) && DouyinNativeOrders.carrier(page) != null

    fun eventScope(page: NativePage, node: NativeNode): List<Int>? {
        if (!logisticsPage(page)) return null
        val time = Regex("(?:[0-9]{4}[-年])?[0-9]{2}[-月][0-9]{2}日?\\s+[0-9]{2}:[0-9]{2}(?::[0-9]{2})?")
        for (depth in 1..minOf(6, node.path.size - 1)) {
            val prefix = node.path.dropLast(depth)
            val dates = page.nodes.filter { it.visible && descendant(it.path, prefix) && DouyinNativeOrders.textValues(it).any { (_, text) -> time.matches(text) } }
            if (dates.size > 1) return null
            if (dates.size == 1) return prefix
        }
        return null
    }

    private fun informationScope(page: NativePage, node: NativeNode): List<Int>? {
        val header = page.nodes.singleOrNull { it.visible && DouyinNativeOrders.label(it) == "更多信息" } ?: return null
        if (node.bounds.size != 4 || header.bounds.size != 4 || node.bounds[0] <= header.bounds[2] ||
            node.bounds[1] >= header.bounds[3] || header.bounds[1] >= node.bounds[3]) return null
        val scope = DouyinNativeOrders.informationRoot(page)?.takeIf { descendant(node.path, it) } ?: return null
        val area = page.nodes.singleOrNull { it.path == scope } ?: return null
        val clickable = page.nodes.filter { it.visible && it.enabled && ACTION_CLICK in it.actions && descendant(node.path, it.path) }
            .maxByOrNull { it.path.size }
        return if (clickable != null && descendant(scope, clickable.path) && clickable.bounds == area.bounds) clickable.path else scope
    }

    private fun controlScope(page: NativePage, anchor: NativeNode): List<Int>? {
        val clickable = page.nodes.filter { it.visible && it.enabled && ACTION_CLICK in it.actions && descendant(anchor.path, it.path) }
            .maxByOrNull { it.path.size } ?: return anchor.path.takeIf { it.isNotEmpty() }
        val root = page.nodes.singleOrNull { it.path.isEmpty() } ?: return null
        if (clickable.bounds.size != 4 || root.bounds.size != 4) return null
        val width = clickable.bounds[2] - clickable.bounds[0]
        val height = clickable.bounds[3] - clickable.bounds[1]
        if (width <= 0 || height <= 0 || width > (root.bounds[2] - root.bounds[0]) / 3 || height > (root.bounds[3] - root.bounds[1]) / 5) return null
        return clickable.path
    }

    fun scope(page: NativePage, anchor: NativeNode, purpose: NativeActionPurpose): List<Int>? = when (purpose) {
        NativeActionPurpose.ORDER_DETAIL -> NativePaginationRules.cardsOnPage(page).singleOrNull { it.node == anchor }?.let { card ->
            NativePaginationRules.verticalList(page)?.let { list -> list.path + card.row }
        }
        NativeActionPurpose.ORDER_INFORMATION -> informationScope(page, anchor)
        NativeActionPurpose.LOGISTICS_BODY -> eventScope(page, anchor)
        else -> controlScope(page, anchor)
    }

    fun rejection(page: NativePage, anchor: NativeNode, target: NativeNode = anchor,
        intended: NativeActionPurpose = purpose(page, anchor)): String? {
        if (page.packageName != DouyinCaptureController.PACKAGE || !anchor.visible || !anchor.enabled || !target.visible || !target.enabled)
            return "目标不属于当前可操作的官方页面"
        if (forbidden(anchor) || forbidden(target)) return "目标区域包含交易操作，禁止自动点击"
        if (intended == NativeActionPurpose.UNKNOWN || purpose(page, anchor) != intended) return "当前节点不符合本次读取用途"
        val scope = scope(page, anchor, intended) ?: return "当前节点缺少可确认的读取范围"
        if (!descendant(target.path, scope) || !descendant(anchor.path, target.path)) return "动作节点超出已确认的读取范围"
        if (page.nodes.any { it.visible && descendant(it.path, target.path) && forbidden(it) }) return "动作区域包含交易按钮，禁止自动点击"
        return null
    }
}
