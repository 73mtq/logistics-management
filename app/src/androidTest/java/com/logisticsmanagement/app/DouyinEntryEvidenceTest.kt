package com.logisticsmanagement.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.platform.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@kotlinx.serialization.Serializable
internal data class NativeEntryInstruction(val sequence: Int, val action: String, val phase: String, val label: String = "",
    val path: List<Int>? = null)

@RunWith(AndroidJUnit4::class)
class DouyinEntryEvidenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val container get() = (context.applicationContext as LogisticsApplication).container
    private val json = Json { ignoreUnknownKeys = true }
    private fun argument(key: String) = checkNotNull(arguments.getString(key))
    private fun input(key: String) = File(context.filesDir, argument(key)).apply {
        check(isFile) { "${key}指定的本机资料不存在" }
    }
    private fun output() = File(context.filesDir, "douyin-entry-evidence/${argument("runName")}").apply { check(mkdirs()) }

    private suspend fun service(): DouyinCaptureService {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        container.ready.await()
        if (DouyinCaptureController.service == null) {
            context.startActivity(Intent(context, DouyinCaptureActivity::class.java)
                .putExtra("accountId", argument("accountId")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return withTimeout(120_000) {
            while (DouyinCaptureController.service == null) delay(100)
            checkNotNull(DouyinCaptureController.service)
        }
    }

    private fun launchOfficialApp() {
        val component = checkNotNull(context.packageManager.getLaunchIntentForPackage(DouyinCaptureController.PACKAGE)?.component)
        val descriptor = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand("am start -n ${component.flattenToShortString()}")
        val result = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        File(context.filesDir, "douyin-entry-evidence/${argument("runName")}/launch.txt").writeText(result)
        check(!result.contains("Error:") && !result.contains("Exception")) { "抖音启动失败" }
    }

    private fun export(directory: File, captureId: String) {
        val source = File(context.filesDir, "native-entry-evidence/$captureId")
        assertTrue(source.isDirectory)
        for (file in checkNotNull(source.listFiles()).sortedBy { it.name }) {
            val raw = container.credentials.decrypt(file.readText())
            json.parseToJsonElement(raw)
            File(directory, file.name.removeSuffix(".enc") + ".json").writeText(raw)
        }
    }

    private suspend fun record(launch: Boolean, navigate: Boolean) {
        val connected = service()
        val account = checkNotNull(container.repository.getAccount(argument("accountId")))
        assertEquals(Platform.DOUYIN, account.platform)
        val directory = output()
        val captureId = argument("runName")
        val beforeOrders = container.repository.getOrders()
        val beforeShipments = container.repository.getShipments()
        val beforeQueue = container.repository.pendingTracking(account.id)
        val scans = mutableListOf<NativeScan>()
        var actionResult: Boolean? = null
        withContext(Dispatchers.Main.immediate) {
            DouyinCaptureController.startWithEvidence(account, captureId)
            DouyinCaptureController.markPhase(arguments.getString("phase") ?: "未指定页面")
            assertTrue(connected.serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0)
            if (launch) launchOfficialApp()
        }
        try {
            delay(2_000)
            if (navigate) withContext(Dispatchers.Main.immediate) {
                val before = connected.captureNow()
                scans.add(before)
                val label = argument("actionLabel")
                val targets = before.pages.flatMap { page -> page.nodes.filter {
                    it.visible && it.enabled && (it.text == label || it.description == label)
                }.map { page to it } }
                assertEquals("目标标签必须唯一，查看保存的真实节点资料", 1, targets.size)
                val (page, anchor) = targets.single()
                val target = page.nodes.filter { candidate ->
                    candidate.visible && candidate.enabled && AccessibilityNodeInfo.ACTION_CLICK in candidate.actions &&
                        anchor.path.take(candidate.path.size) == candidate.path
                }.maxByOrNull { it.path.size }
                assertNotNull("目标没有可读取的点击动作", target)
                actionResult = connected.perform(page, checkNotNull(target), AccessibilityNodeInfo.ACTION_CLICK, anchor)
                assertEquals("真实节点点击结果", true, actionResult)
            }
            if (navigate) delay(2_000)
            repeat(2) {
                val scan = connected.captureNow()
                scans.add(scan)
                assertEquals(account.id, scan.accountId)
                assertEquals(captureId, scan.captureId)
                assertTrue("需要真实抖音窗口", scan.pages.isNotEmpty())
                for (page in scan.pages) {
                    assertEquals(DouyinCaptureController.PACKAGE, page.packageName)
                    assertEquals(account.id, page.accountId)
                    assertEquals(captureId, page.captureId)
                    assertEquals(page.nodes.size, page.readCount)
                    assertEquals(page.nodes.count { !it.visible }, page.invisibleCount)
                    assertEquals(page.nodes.size, page.nodes.map { it.path }.toSet().size)
                    assertTrue(page.nodeHash.isNotBlank())
                }
                delay(800)
            }
            assertTrue(scans.last().sequence > scans[scans.lastIndex - 1].sequence)
            assertEquals(beforeOrders, container.repository.getOrders())
            assertEquals(beforeShipments, container.repository.getShipments())
            assertEquals(beforeQueue, container.repository.pendingTracking(account.id))
            File(directory, "result.json").writeText(buildJsonObject {
                put("phase", arguments.getString("phase") ?: "未指定页面"); put("captureId", captureId)
                put("explicitScans", scans.size); put("actionResult", actionResult)
                put("windows", scans.last().windows.size); put("pages", scans.last().pages.size)
                put("nodes", scans.last().pages.sumOf { it.readCount })
                put("labelledNodes", scans.last().pages.sumOf { page -> page.nodes.count { it.text.isNotBlank() || it.description.isNotBlank() } })
                put("businessPreserved", true)
            }.toString())
            Log.i("DouyinEntryEvidence", "recorded phase=${arguments.getString("phase")} scans=${scans.size} pages=${scans.last().pages.size}")
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                DouyinCaptureController.stop()
                assertNull(DouyinCaptureController.activeAccount())
                assertEquals(0, connected.serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE)
            }
            export(directory, captureId)
        }
    }

    @Test fun launchAndRecord(): Unit = runBlocking { record(true, false) }
    @Test fun launchOfficialAppUsesRealEntry(): Unit = runBlocking {
        container.ready.await()
        output()
        launchOfficialApp()
    }
    @Test fun recordCurrentPage(): Unit = runBlocking { record(false, false) }
    @Test fun navigateAndRecord(): Unit = runBlocking { record(false, true) }

    @Test fun captureSession(): Unit = runBlocking {
        val instructionFile = input("instructionsPath")
        val historicalFile = arguments.getString("capturesPath")?.let { input("capturesPath") }
        val connected = service()
        val account = checkNotNull(container.repository.getAccount(argument("accountId")))
        assertEquals(Platform.DOUYIN, account.platform)
        val directory = output()
        val runName = argument("runName")
        val beforeOrders = container.repository.getOrders()
        val beforeShipments = container.repository.getShipments()
        var lastSequence = -1
        var processedSteps = 0
        var finished = false
        var blocked = 0
        withContext(Dispatchers.Main.immediate) { DouyinCaptureController.startWithEvidence(account, runName) }
        try {
            launchOfficialApp()
            delay(2_000)
            File(directory, "ready.marker").writeText("ready")
            while (withContext(Dispatchers.Main.immediate) { DouyinCaptureController.activeAccount() } != null) {
                if (instructionFile.isFile) {
                    val instruction = json.decodeFromString<NativeEntryInstruction>(instructionFile.readText())
                    if (instruction.sequence > lastSequence) {
                        require(instruction.action in setOf("record", "click", "back", "finish"))
                        lastSequence = instruction.sequence
                        processedSteps++
                        val stepStartedAt = android.os.SystemClock.elapsedRealtime()
                        File(directory, "step-${lastSequence.toString().padStart(3, '0')}-started.json").writeText(buildJsonObject {
                            put("sequence", lastSequence); put("action", instruction.action); put("phase", instruction.phase)
                            put("startedAt", System.currentTimeMillis())
                        }.toString())
                        var actionResult = true
                        var actionReason = ""
                        withContext(Dispatchers.Main.immediate) {
                            DouyinCaptureController.markPhase(instruction.phase)
                            if (instruction.action == "click") {
                                val scan = connected.captureNow()
                                val anchors = scan.pages.flatMap { page -> page.nodes.filter {
                                    it.visible && it.enabled && (it.text == instruction.label || it.description == instruction.label) &&
                                        (instruction.path == null || it.path == instruction.path)
                                }.map { page to it } }
                                actionResult = if (anchors.size != 1) {
                                    actionReason = "当前目标文字与路径匹配数量为${anchors.size}"
                                    false
                                } else {
                                    val (page, anchor) = anchors.single()
                                    val target = page.nodes.filter { candidate ->
                                        candidate.visible && candidate.enabled && AccessibilityNodeInfo.ACTION_CLICK in candidate.actions &&
                                            anchor.path.take(candidate.path.size) == candidate.path
                                    }.maxByOrNull { it.path.size }
                                    if (target == null) {
                                        actionReason = "目标及父节点没有提供点击动作"
                                        false
                                    } else connected.performChecked(page, target, AccessibilityNodeInfo.ACTION_CLICK, anchor).let {
                                        actionReason = it.reason
                                        it.accepted
                                    }
                                }
                            } else if (instruction.action == "back") {
                                val scan = connected.captureNow()
                                actionResult = scan.pages.isNotEmpty() && connected.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                            } else if (instruction.action == "finish") finished = true
                        }
                        if (!actionResult) blocked++
                        if (instruction.action in setOf("click", "back")) delay(1_500)
                        val scans = withTimeout(30_000) { listOf(connected.captureNow(), connected.captureNow()) }
                        for (scan in scans) {
                            assertEquals(account.id, scan.accountId)
                            assertEquals(runName, scan.captureId)
                            for (page in scan.pages) {
                                assertEquals(DouyinCaptureController.PACKAGE, page.packageName)
                                assertEquals(page.nodes.size, page.readCount)
                            }
                        }
                        assertTrue(scans[1].sequence > scans[0].sequence)
                        File(directory, "step-${lastSequence.toString().padStart(3, '0')}.json").writeText(buildJsonObject {
                            put("sequence", lastSequence); put("action", instruction.action); put("phase", instruction.phase)
                            put("actionResult", actionResult); put("pages", scans.last().pages.size)
                            put("actionReason", actionReason)
                            put("durationMillis", android.os.SystemClock.elapsedRealtime() - stepStartedAt)
                            put("nodes", scans.last().pages.sumOf { it.readCount })
                            put("scans", JsonArray(scans.map { json.parseToJsonElement(json.encodeToString(it)) }))
                        }.toString())
                        export(directory, runName)
                        if (finished) break
                    }
                }
                delay(250)
            }
            assertEquals(beforeOrders, container.repository.getOrders())
            assertEquals(beforeShipments, container.repository.getShipments())
            File(directory, "result.json").writeText(buildJsonObject {
                put("steps", processedSteps); put("lastSequence", lastSequence); put("blockedActions", blocked); put("explicitFinish", finished)
                put("expired", !finished); put("businessPreserved", true)
            }.toString())
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                DouyinCaptureController.stop()
                assertNull(DouyinCaptureController.activeAccount())
                assertEquals(0, connected.serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE)
            }
            export(directory, runName)
        }
        if (arguments.getString("verifyLifecycle") == "true") {
            val deadlineId = "$runName-deadline"
            withContext(Dispatchers.Main.immediate) { DouyinCaptureController.startWithEvidence(account, deadlineId, 5_000) }
            try {
                delay(1_000)
                val scan = connected.captureNow()
                assertTrue("期限验证需要真实抖音页面", scan.pages.isNotEmpty())
                withTimeout(10_000) {
                    while (withContext(Dispatchers.Main.immediate) { DouyinCaptureController.activeAccount() } != null) delay(100)
                }
                assertEquals(0, connected.serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE)
                assertTrue(DouyinCaptureController.status.value.contains("时间已结束"))
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) { DouyinCaptureController.stop() }
            }
            val deadlineDirectory = File(directory, "deadline").apply { check(mkdirs()) }
            export(deadlineDirectory, deadlineId)
            File(deadlineDirectory, "result.json").writeText(buildJsonObject { put("deadlineReleased", true) }.toString())
            if (historicalFile != null) {
                val captures = json.decodeFromString<List<CapturedResponse>>(historicalFile.readText())
                    .filter { it.kind == "accessibility" }
                assertTrue(captures.isNotEmpty())
                for (capture in captures) {
                    val page = json.decodeFromString<NativePage>(capture.body)
                    assertEquals(DouyinCaptureController.PACKAGE, page.packageName)
                    assertTrue(page.nodes.isNotEmpty())
                    assertEquals(page.nodes.size, page.readCount)
                    assertEquals(page, json.decodeFromString<NativePage>(json.encodeToString(page)))
                }
                File(directory, "compatibility.json").writeText(buildJsonObject { put("pages", captures.size); put("compatible", true) }.toString())
            }
        }
    }

    @Test fun deadlineReleasesCaptureMode(): Unit = runBlocking {
        val connected = service()
        val account = checkNotNull(container.repository.getAccount(argument("accountId")))
        val directory = output()
        val captureId = argument("runName")
        withContext(Dispatchers.Main.immediate) {
            launchOfficialApp()
            DouyinCaptureController.startWithEvidence(account, captureId, 5_000)
        }
        try {
            delay(2_000)
            assertTrue(connected.captureNow().pages.isNotEmpty())
            withTimeout(10_000) {
                while (withContext(Dispatchers.Main.immediate) { DouyinCaptureController.activeAccount() } != null) delay(100)
            }
            withContext(Dispatchers.Main.immediate) {
                assertEquals(0, connected.serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE)
                assertTrue(DouyinCaptureController.status.value.contains("时间已结束"))
            }
            File(directory, "result.json").writeText(buildJsonObject { put("deadlineReleased", true) }.toString())
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { DouyinCaptureController.stop() }
            export(directory, captureId)
        }
    }

    @Test fun replayHistoricalNativePages(): Unit = runBlocking {
        container.ready.await()
        val captures = json.decodeFromString<List<CapturedResponse>>(input("capturesPath").readText()).filter { it.kind == "accessibility" }
        assertTrue("需要历史真实无障碍采集资料", captures.isNotEmpty())
        var nodes = 0
        for (capture in captures) {
            val page = json.decodeFromString<NativePage>(capture.body)
            assertEquals(DouyinCaptureController.PACKAGE, page.packageName)
            assertTrue(page.nodes.isNotEmpty())
            assertEquals(page.nodes.size, page.readCount)
            assertEquals(page, json.decodeFromString<NativePage>(json.encodeToString(page)))
            nodes += page.nodes.size
        }
        File(output(), "result.json").writeText(buildJsonObject { put("pages", captures.size); put("nodes", nodes); put("compatible", true) }.toString())
    }
}
