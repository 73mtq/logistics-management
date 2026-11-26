package com.logisticsmanagement.app

import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.platform.DouyinOcrLogistics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DouyinOcrLogisticsTest {
    @Test fun parsesRealLogisticsScreen() {
        val directory = System.getenv("S1_OCR_DIR")?.let(::File)?.takeIf(File::isDirectory)
        assumeTrue("缺少 S1_OCR_DIR 下的真实识别资料", directory != null)
        val base = checkNotNull(directory)
        val text = File(base, "logistics.txt").takeIf(File::isFile)?.readText()
        assumeTrue("缺少 logistics.txt", text != null)
        val result = checkNotNull(DouyinOcrLogistics.parse(checkNotNull(text), System.currentTimeMillis(), null))
        assertTrue("识别没有取得运单号", result.trackingNumber.length >= 10)
        assertTrue("识别没有取得承运商", result.carrier.isNotBlank())
        assertEquals("识别应只提供一个包裹", 1, result.parcels.size)
        assertEquals(result.carrier, result.parcels.single().carrier)
        assertEquals(result.trackingNumber, result.parcels.single().trackingNumber)
        assertEquals("时间证据与轨迹数量一致", result.events.size, result.times.size)
        assertTrue("缺少下单时间时应保留未定年的时间原文", result.unresolved.size >= 3)
        assertEquals("轨迹没有去重", result.events.size, result.events.distinctBy { it.time to it.text }.size)
        val expected = File(base, "expected.json").takeIf(File::isFile)?.readText()
            ?.let { Json.parseToJsonElement(it).jsonObject }
        expected?.get("carrier")?.jsonPrimitive?.content?.let { assertEquals(it, result.carrier) }
        expected?.get("trackingNumber")?.jsonPrimitive?.content?.let { assertEquals(it, result.trackingNumber) }
        val minEvents = expected?.get("minEvents")?.jsonPrimitive?.int
        expected?.get("createdAt")?.jsonPrimitive?.longOrNull?.let { createdAt ->
            val resolved = checkNotNull(DouyinOcrLogistics.parse(checkNotNull(text), System.currentTimeMillis(), createdAt))
            minEvents?.let { assertTrue("轨迹少于真实样本", resolved.events.size >= it) }
            assertTrue("提供了下单时间时应解析出轨迹", resolved.events.isNotEmpty())
            assertEquals(resolved.events.size, resolved.times.size)
            assertTrue("提供了下单时间后不应再有未定年时间", resolved.unresolved.isEmpty())
        }
        if (expected?.get("createdAt") == null) assertTrue("缺少下单时间时不应猜测年份", result.events.isEmpty())
        expected?.get("status")?.jsonPrimitive?.content?.let { assertEquals(ParcelStatus.valueOf(it), result.status) }
    }
}
