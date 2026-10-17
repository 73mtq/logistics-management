package com.logisticsmanagement.app.platform

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

internal fun readNativeNode(node: AccessibilityNodeInfo, path: List<Int>): NativeNode {
    val bounds = Rect().also(node::getBoundsInScreen)
    val texts = linkedMapOf<String, String>()
    fun add(name: String, value: CharSequence?) {
        if (!value.isNullOrBlank()) texts[name] = value.toString()
    }
    add("hintText", node.hintText)
    if (Build.VERSION.SDK_INT >= 28) {
        add("paneTitle", node.paneTitle)
        add("tooltipText", node.tooltipText)
    }
    if (Build.VERSION.SDK_INT >= 30) add("stateDescription", node.stateDescription)
    if (Build.VERSION.SDK_INT >= 34) add("containerTitle", node.containerTitle)
    if (Build.VERSION.SDK_INT >= 36) add("supplementalDescription", node.supplementalDescription)
    for (key in node.extras.keySet().sorted()) {
        @Suppress("DEPRECATION") val value = node.extras.get(key)
        when (value) {
            is CharSequence -> add("extras.$key", value)
            is Array<*> -> {
                val values = value.filterIsInstance<CharSequence>()
                if (values.size == value.size) add("extras.$key", values.joinToString(" "))
            }
        }
    }
    return NativeNode(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(),
        node.className?.toString().orEmpty(), node.viewIdResourceName.orEmpty(), path,
        listOf(bounds.left, bounds.top, bounds.right, bounds.bottom), node.isClickable, node.isScrollable,
        node.isVisibleToUser, node.isEnabled, node.actionList.map { it.id }, node.childCount,
        texts, if (Build.VERSION.SDK_INT >= 36) node.expandedState else 0, node.isSelected, node.availableExtraData)
}

internal fun nativePath(root: AccessibilityNodeInfo, target: AccessibilityNodeInfo): List<Int>? {
    val path = mutableListOf<Int>()
    val visited = mutableSetOf<AccessibilityNodeInfo>()
    var current = target
    while (current != root) {
        if (!visited.add(current) || current.windowId != root.windowId) return null
        val parent = current.parent ?: return null
        val index = (0 until parent.childCount).singleOrNull { parent.getChild(it) == current } ?: return null
        path.add(index)
        current = parent
    }
    return path.reversed()
}
