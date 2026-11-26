package com.logisticsmanagement.app

import com.logisticsmanagement.app.platform.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DouyinRangeEvidenceTest {
    private fun root(): File {
        val value = System.getenv("S2_EVIDENCE_DIR")
        assumeTrue("需要本机 S2 真实扫描目录", !value.isNullOrBlank())
        return File(checkNotNull(value)).apply { check(isDirectory) }
    }

    private fun scan(name: String) = platformJson.decodeFromString<NativeScan>(File(root(), name).readText())

    private fun detailPage(scan: NativeScan) = NativePaginationRules.activePages(scan).single { page ->
        page.nodes.any { it.visible && DouyinNativeOrders.label(it) == "订单编号" }
    }

    @Test fun informationToggleSelectsMoreInformationSection() {
        val page = detailPage(scan("collapsed.json"))
        val toggle = checkNotNull(DouyinNativeOrders.informationToggle(page))
        assertEquals("展开", DouyinNativeOrders.label(toggle))
        assertTrue("更多信息的展开节点位于详情字段区域", toggle.bounds[1] in 1_000..1_400)
        assertTrue("服务保障的展开节点不应被选中", page.nodes.any {
            it.visible && DouyinNativeOrders.label(it) == "展开" && it.bounds[1] > 1_500
        })
    }

    @Test fun detailFieldsRecordKnownFieldsAndNoDeliveryType() {
        val page = detailPage(scan("expanded.json"))
        val named = DouyinNativeOrders.detailFields(page).filter { it.name.isNotBlank() }.associate { it.name to it.value }
        assertEquals("6918361666413145327", named["订单编号"])
        assertEquals("2026-10-09 20:55:35", named["下单时间"])
        assertTrue(named.containsKey("支付方式"))
        assertFalse(named.containsKey("配送方式"))
    }

    @Test fun scopeEvidenceHasNoSelectionSignal() {
        val scan = scan("list.json")
        val evidence = checkNotNull(NativePaginationRules.scopeEvidence(scan))
        assertEquals("全部订单", evidence.title)
        assertEquals(6, evidence.tabs.size)
        assertTrue("真实列表的标签没有 selected 属性", evidence.tabs.none { it.selected })
        assertFalse(NativePaginationRules.scopeSelected(scan))
    }

    @Test fun listProvidesForwardScrollAction() {
        val scan = scan("list.json")
        val page = NativePaginationRules.activePages(scan).single { DouyinNativeOrders.isList(it) }
        val node = checkNotNull(NativePaginationRules.verticalList(page))
        assertTrue("真实列表应提供向前的滚动动作", 4096 in node.actions)
    }
}
