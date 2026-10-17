package com.logisticsmanagement.app

import com.logisticsmanagement.app.platform.parsePlatformTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class PlatformTimeTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun shortDateParsesWithoutException() {
        for (text in listOf("08-28 18:04", "08-28 18:04:05", "01-01 00:00", "12-31 23:59:59")) {
            assertTrue("$text 应当解析为有效时间戳", parsePlatformTime(text) > 0)
        }
    }

    @Test fun shortDateNeverResolvesToFuture() {
        val now = LocalDateTime.now(zone)
        for (text in listOf("08-28 18:04", "08-28 18:04:05", "01-01 00:00", "12-31 23:59:59")) {
            val parsed = LocalDateTime.ofInstant(Instant.ofEpochMilli(parsePlatformTime(text)), zone)
            assertTrue("$text 解析结果不得晚于当前时刻", !parsed.isAfter(now))
        }
    }

    @Test fun shortDateKeepsMonthDayMinute() {
        val parsed = LocalDateTime.ofInstant(Instant.ofEpochMilli(parsePlatformTime("08-28 18:04")), zone)
        assertEquals(8, parsed.monthValue)
        assertEquals(28, parsed.dayOfMonth)
        assertEquals(18, parsed.hour)
        assertEquals(4, parsed.minute)
    }

    @Test fun shortDateWithSecondsParses() {
        val parsed = LocalDateTime.ofInstant(Instant.ofEpochMilli(parsePlatformTime("08-28 18:04:05")), zone)
        assertEquals(8, parsed.monthValue)
        assertEquals(28, parsed.dayOfMonth)
        assertEquals(5, parsed.second)
    }

    @Test fun fullDateParsesUnchanged() {
        val parsed = LocalDateTime.ofInstant(Instant.ofEpochMilli(parsePlatformTime("2026-08-28 18:04:05")), zone)
        assertEquals(2026, parsed.year)
        assertEquals(8, parsed.monthValue)
        assertEquals(28, parsed.dayOfMonth)
    }
}
