package com.logisticsmanagement.app

import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.platform.NativeFieldEvidence
import com.logisticsmanagement.app.platform.NativeOrderProgress
import com.logisticsmanagement.app.platform.NativeSyncProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeProgressTest {
    private fun order(id: String, captureId: String, finished: Boolean) = NativeOrderProgress(
        remoteId = id, title = "订单 $id", status = ParcelStatus.SIGNED,
        orderSaved = finished, logisticsSaved = finished, evidenceCaptureId = captureId)

    private val terminal = NativeFieldEvidence("listEnd", "没有更多订单", 1, listOf(0), 1)

    @Test fun resumeKeepsPreviousBlockersAndClearsCurrent() {
        val first = NativeSyncProgress(accountId = "account-real", externalIdentity = "identity", captureId = "capture-1",
            listEnded = true, scopeConfirmed = true, terminalEvidence = terminal,
            orders = mapOf("order-a" to order("order-a", "capture-1", true)),
            blockedCards = mapOf("cycle-1:card-1" to "未识别商品区域"))
        val resumed = first.resumeFor("capture-2")
        assertEquals(mapOf("cycle-1:card-1" to "未识别商品区域"), resumed.previousBlocked)
        assertTrue(resumed.blockedCards.isEmpty())
        assertTrue(resumed.running)
        assertFalse(resumed.listEnded)
        assertNotEquals(first.cycleId, resumed.cycleId)
        assertEquals(first.orders, resumed.orders)
    }

    @Test fun unseenOrdersBecomeStaleAndRoundCompletes() {
        val resumed = NativeSyncProgress(accountId = "account-real", externalIdentity = "identity", captureId = "capture-2",
            orders = mapOf(
                "order-a" to order("order-a", "capture-1", true),
                "order-b" to order("order-b", "capture-2", true)))
        val stale = resumed.staleUnseen("capture-2")
        assertTrue(stale.orders.getValue("order-a").stale)
        assertEquals("本轮未再出现", stale.orders.getValue("order-a").staleReason)
        assertFalse(stale.orders.getValue("order-b").stale)
        val complete = stale.copy(listEnded = true, scopeConfirmed = true, terminalEvidence = terminal)
        assertTrue(complete.completeFor("capture-2"))
        assertFalse(complete.copy(blockedCards = mapOf("cycle-2:card" to "未识别")).completeFor("capture-2"))
        assertFalse(complete.copy(listEnded = false).completeFor("capture-2"))
    }
}
