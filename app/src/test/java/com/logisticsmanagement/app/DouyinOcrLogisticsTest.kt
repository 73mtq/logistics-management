package com.logisticsmanagement.app

import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.platform.DouyinOcrLogistics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val result = checkNotNull(DouyinOcrLogistics.parse(checkNotNull(text), System.currentTimeMillis()))
        assertTrue("识别没有取得运单号", result.trackingNumber.length >= 10)
        assertTrue("识别没有取得承运商", result.carrier.isNotBlank())
        assertEquals("轨迹没有去重", result.events.size, result.events.distinctBy { it.time to it.text }.size)
        val expected = File(base, "expected.json").takeIf(File::isFile)?.readText()
            ?.let { Json.parseToJsonElement(it).jsonObject }
        expected?.get("carrier")?.jsonPrimitive?.content?.let { assertEquals(it, result.carrier) }
        expected?.get("trackingNumber")?.jsonPrimitive?.content?.let { assertEquals(it, result.trackingNumber) }
        expected?.get("minEvents")?.jsonPrimitive?.int?.let { assertTrue("轨迹少于真实样本", result.events.size >= it) }
        expected?.get("status")?.jsonPrimitive?.content?.let { assertEquals(ParcelStatus.valueOf(it), result.status) }
    }
}
