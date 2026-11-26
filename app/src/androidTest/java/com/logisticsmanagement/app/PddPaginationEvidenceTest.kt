package com.logisticsmanagement.app

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.platform.CapturedResponse
import com.logisticsmanagement.app.platform.PddAdapter
import com.logisticsmanagement.app.platform.PddPageLedger
import com.logisticsmanagement.app.platform.PddTab
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PddPaginationEvidenceTest {
    @Test fun replayCapturedPages(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val name = InstrumentationRegistry.getArguments().getString("capturesPath") ?: "pdd-orders.json"
        val file = File(context.filesDir, name)
        assertTrue("需要真实捕获文件 " + name, file.exists())
        val json = Json { ignoreUnknownKeys = true }
        val captures = json.decodeFromString<List<CapturedResponse>>(file.readText())
        val adapter = PddAdapter(container.platforms)
        val relevant = captures.filter { it.kind == "response" && Uri.parse(it.url).path == "/proxy/api/api/aristotle/order_list_v4" }
        assertTrue("真实捕获需要订单响应", relevant.isNotEmpty())
        assertTrue("样本只有第 1 页", relevant.all { it.requestPage == 1 })
        val types = relevant.map { it.requestType }.filter { it.isNotBlank() }.toSet()
        assertTrue("样本需要多个 type", types.size >= 2)
        val ledger = PddPageLedger(PddTab("全部", "all"))
        var rawOrders = 0
        for (capture in relevant) {
            val page = adapter.parseOrders(capture)
            assertNotNull(page)
            assertTrue(page!!.remoteIds.size >= page.orders.size)
            rawOrders += page.remoteIds.size
            if (capture.requestType == "all") ledger.record(capture.requestPage, page.remoteIds.toList(), page.remoteIds.size)
        }
        ledger.finish("no-progress")
        val summary = ledger.summary()
        assertEquals(listOf(1), summary.pages)
        assertEquals("no-progress", summary.endedBy)
        assertTrue("全部标签需要真实订单", summary.distinct > 0)
        assertTrue("样本不包含后续页", summary.pages.none { it > 1 })
        val directory = File(context.filesDir, "pdd-pagination-evidence/" + System.nanoTime()).apply { check(mkdirs()) }
        File(directory, "summary.json").writeText(json.encodeToString(summary))
        Log.i("PddPaginationEvidence", "captures=" + relevant.size + " types=" + types.joinToString(",") + " raw=" + rawOrders + " all=" + summary.distinct)
    }
}
