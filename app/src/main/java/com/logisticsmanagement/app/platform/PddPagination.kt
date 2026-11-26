package com.logisticsmanagement.app.platform

import kotlinx.serialization.Serializable

internal data class PddTab(val label: String, val type: String)

@Serializable
internal data class PddPageSummary(
    val label: String,
    val type: String,
    val pages: List<Int>,
    val counts: List<Int>,
    val loads: Int,
    val distinct: Int,
    val endedBy: String,
    val anomalies: List<String>,
)

@Serializable
internal data class PddScrollInfo(
    val tag: String = "",
    val sh: Double = 0.0,
    val ch: Double = 0.0,
    val top: Double = 0.0,
    val moved: Boolean = false,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val w: Double = 0.0,
    val h: Double = 0.0,
    val vw: Double = 1200.0,
    val vh: Double = 2670.0,
    val error: String = "",
)

internal class PddPageLedger(private val tab: PddTab) {
    private val pages = mutableListOf<Int>()
    private val counts = mutableListOf<Int>()
    private val ids = linkedSetOf<String>()
    private val problems = mutableListOf<String>()
    private var endedBy: String? = null
    private var shortPage = false
    private var loads = 0

    val empty: Boolean get() = endedBy == "empty-page"
    val size: Int get() = pages.size
    val type: String get() = tab.type

    fun record(page: Int, rawIds: List<String>, rawCount: Int): Boolean {
        if (page <= 0) {
            problems += "响应缺少 page"
            return false
        }
        val previous = pages.lastOrNull()
        if (previous == null && page != 1) problems += "首页不是 1：$page"
        if (previous != null) {
            if (page > previous + 1) problems += "页码跳变：$previous 到 $page"
            if (page < previous) problems += "页码回退：$previous 到 $page"
        }
        if (rawIds.size != rawCount) problems += "第 $page 页原始条数不一致：${rawIds.size} 与 $rawCount"
        loads++
        val known = page in pages
        val fresh = rawIds.filter { ids.add(it) }
        if (!known) {
            pages += page
            counts += rawCount
            if (page == 1 && rawCount in 1..9) shortPage = true
            if (page > 1 && rawCount > 0) shortPage = false
        }
        if (rawCount == 0) endedBy = "empty-page"
        return !known || fresh.isNotEmpty()
    }

    fun finish(reason: String) {
        if (endedBy == null) endedBy = if (shortPage) "short-page" else reason
    }

    fun summary(): PddPageSummary = PddPageSummary(
        label = tab.label,
        type = tab.type,
        pages = pages.toList(),
        counts = counts.toList(),
        loads = loads,
        distinct = ids.size,
        endedBy = endedBy ?: "unfinished",
        anomalies = problems.toList(),
    )
}
