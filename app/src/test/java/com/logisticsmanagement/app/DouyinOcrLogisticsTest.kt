package com.logisticsmanagement.app

import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.platform.DouyinOcrLogistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DouyinOcrLogisticsTest {
    @Test fun parseRealLogisticsText() {
        val text = checkNotNull(javaClass.classLoader.getResource("ocr-logistics.txt")).readText()
        val capturedAt = Instant.parse("2026-10-16T12:00:00+08:00").toEpochMilli()
        val result = checkNotNull(DouyinOcrLogistics.parse(text, capturedAt))
        assertEquals("韵达快递", result.carrier)
        assertEquals("321371002676018", result.trackingNumber)
        assertEquals(ParcelStatus.SIGNED, result.status)
        assertTrue(result.events.any { it.text.contains("待取件") })
        assertTrue(result.events.any { it.text.contains("派件中") })
        assertTrue(result.events.any { it.text.contains("运输中") })
        assertTrue(result.events.size >= 4)
    }
}
