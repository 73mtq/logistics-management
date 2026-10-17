package com.logisticsmanagement.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.data.*
import com.logisticsmanagement.app.model.*
import com.logisticsmanagement.app.network.createHttpClient
import com.logisticsmanagement.app.network.readResponse
import com.logisticsmanagement.app.platform.*
import com.logisticsmanagement.app.sync.SyncCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import okhttp3.Request
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DouyinPaginationEvidenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val container get() = (context.applicationContext as LogisticsApplication).container
    private var runSuffix = ""
    private var capturesOverride: String? = null
    private fun argument(name: String): String = when (name) {
        "runName" -> checkNotNull(arguments.getString(name)) + runSuffix
        "capturesPath" -> capturesOverride ?: checkNotNull(arguments.getString(name))
        else -> checkNotNull(arguments.getString(name))
    }
    private fun output() = File(context.filesDir, "douyin-order-evidence/${argument("runName")}").apply { check(mkdirs()) }
    private fun input(name: String) = File(context.filesDir, argument(name)).apply { check(isFile) }
    private fun configureJevCredential(): Boolean {
        val hadKey = !container.credentials.getSecret(NativeSemanticJudge.CREDENTIAL_NAME).isNullOrBlank()
        if (arguments.getString("instructionsPath") != null) {
            val secret = input("instructionsPath")
            val value = secret.readText().trim()
            require(value.isNotBlank() && !value.contains('\n') && !value.contains('\r'))
            if (!hadKey) container.credentials.putSecret(NativeSemanticJudge.CREDENTIAL_NAME, value)
            check(secret.delete())
        }
        check(!container.credentials.getSecret(NativeSemanticJudge.CREDENTIAL_NAME).isNullOrBlank()) { "需要在设置中保存Jev凭据" }
        return hadKey
    }
    private fun write(directory: File, name: String, value: JsonElement) = File(directory, name).writeText(value.toString())
    private fun export(directory: File, captureId: String) {
        val source = File(context.filesDir, "native-entry-evidence/$captureId")
        for (file in checkNotNull(source.listFiles()).filter { it.extension == "enc" })
            File(directory, file.name.removeSuffix(".enc") + ".json").writeText(container.credentials.decrypt(file.readText()))
    }
    private suspend fun foregroundActivity(directory: File) {
        val id = argument("accountId")
        require(id.matches(Regex("[A-Za-z0-9-]+")))
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val descriptor = automation.executeShellCommand("am start -W -n com.logisticsmanagement.app/androidx.activity.ComponentActivity")
        File(directory, "foreground.txt").appendText(ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() })
        withTimeout(10_000) { while (automation.rootInActiveWindow?.packageName?.toString() != "com.logisticsmanagement.app") delay(25) }
        withContext(Dispatchers.Main.immediate) {
            context.startActivity(Intent(context, DouyinCaptureActivity::class.java).putExtra("accountId", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        fun hasEntry(node: AccessibilityNodeInfo): Boolean = node.text?.toString() == "开始新一轮" || (0 until node.childCount).any { index -> node.getChild(index)?.let(::hasEntry) == true }
        withTimeout(10_000) { while (automation.rootInActiveWindow?.let { it.packageName?.toString() == "com.logisticsmanagement.app" && hasEntry(it) } != true) delay(25) }
    }
    private suspend fun awaitService(directory: File): DouyinCaptureService {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        container.ready.await()
        assertFalse(container.syncCoordinator.hasActiveAccounts())
        if (DouyinCaptureController.service == null) {
            foregroundActivity(directory)
            val descriptor = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .executeShellCommand("am start -a android.settings.ACCESSIBILITY_SETTINGS")
            File(directory, "settings-foreground.txt").writeText(ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() })
        }
        File(directory, "waiting.marker").writeText("等待实际服务连接")
        val service = withTimeout(300_000) { while (DouyinCaptureController.service == null) delay(100); checkNotNull(DouyinCaptureController.service) }
        foregroundActivity(directory)
        return service
    }
    private suspend fun stopped(service: DouyinCaptureService) {
        service.awaitOrdersFinished()
        assertFalse(container.syncCoordinator.hasActiveAccounts())
        assertNull(withContext(Dispatchers.Main.immediate) { DouyinCaptureController.activeAccount() })
        assertEquals(0, service.serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE)
    }
    private suspend fun verifyProgressUi(directory: File, account: Account, service: DouyinCaptureService) {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        require(account.id.matches(Regex("[A-Za-z0-9-]+")))
        foregroundActivity(directory)
        val progress = checkNotNull(container.syncCoordinator.nativeProgressStore.read(account))
        fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(root) + (0 until root.childCount).flatMap { index -> root.getChild(index)?.let(::nodes).orEmpty() }
        val current = withTimeout(10_000) {
            while (true) {
                val root = automation.rootInActiveWindow
                if (root?.packageName?.toString() == "com.logisticsmanagement.app") {
                    val found = nodes(root)
                    if (found.any { it.text?.toString()?.contains("已核查 ${progress.orders.size} 笔") == true }) return@withTimeout found
                }
                delay(100)
            }
            error("页面等待已经结束")
        }
        val start = current.single { it.text?.toString() == "开始新一轮" }
        val resume = current.single { it.text?.toString() == "继续采集" }
        assertTrue(start.isVisibleToUser && start.isEnabled)
        assertTrue(resume.isVisibleToUser && resume.isEnabled)
        File(directory, "ui-progress-texts.json").writeText(buildJsonArray { current.mapNotNull { it.text?.toString() }.forEach { add(it) } }.toString())
        assertTrue(resume.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        withTimeout(5_000) { while (DouyinCaptureController.account == null || !container.syncCoordinator.hasActiveAccounts()) delay(25) }
        assertNotEquals(progress.captureId, DouyinCaptureController.captureId)
        withContext(Dispatchers.Main.immediate) { DouyinCaptureController.stop("界面继续采集验证，已主动停止") }
        stopped(service)
        val after = checkNotNull(container.syncCoordinator.nativeProgressStore.read(account))
        assertEquals(progress.cycleId, after.cycleId)
        write(directory, "ui-progress-after.json", platformJson.parseToJsonElement(platformJson.encodeToString(after)))
    }

    @Test fun replayRecovery(): Unit = runBlocking {
        container.ready.await()
        val directory = output()
        val fixtures = platformJson.parseToJsonElement(input("capturesPath").readText()).jsonArray.map { it.jsonObject }
        assertEquals(2, fixtures.size)
        val database = Room.databaseBuilder(context, LogisticsDatabase::class.java, "T13-replay-${System.nanoTime()}.db")
            .createFromFile(input("databasePath")).build()
        try {
            val repository = LogisticsRepository(database)
            val coordinator = SyncCoordinator(context, repository, container.platforms, container.settings, container.credentials)
            coordinator.nativeProgressStore = NativeSyncProgressStore(context, container.credentials, "native-sync-progress-T13-replay-${System.nanoTime()}")
            val id = argument("accountId")
            val account = checkNotNull(repository.getAccount(id))
            val beforeOrders = repository.getOrders()
            val beforeQueue = repository.pendingTracking(id)
            var previousCycle: String? = null
            var previousCapture: String? = null
            for ((index, fixture) in fixtures.withIndex()) {
                fun scan(name: String) = platformJson.decodeFromJsonElement<NativeScan>(fixture.getValue(name))
                val identity = scan("identity")
                val channel = Channel<NativeScan>(Channel.UNLIMITED)
                var session: NativeOrderImportSession? = null
                val stages = mutableListOf<String>()
                coordinator.nativeCheckpoint = { stage, _ -> stages.add(stage) }
                val worker = launch { coordinator.captureNativeAccount(id, identity.captureId, channel, 60_000,
                    onSession = { session = it }, onFinished = { File(directory, "session-${index + 1}.json").writeText(it.evidence()) },
                    pagination = true, resume = index > 0) }
                withTimeout(5_000) { while (session == null) delay(10) }
                suspend fun accept(value: NativeScan) {
                    channel.send(value)
                    withTimeout(10_000) { while (checkNotNull(session).acceptedSequence < value.sequence) delay(10) }
                }
                accept(identity)
                val listing = scan("list")
                val cards = NativePaginationRules.cards(listing)
                assertTrue("真实列表需要至少两个可定位商品卡片", cards.size >= 2)
                assertNotNull(NativePaginationRules.verticalList(NativePaginationRules.activePages(listing).single()))
                assertNull(NativePaginationRules.terminal(listing))
                val title = platformJson.decodeFromJsonElement<NativeNode>(fixture.getValue("titleAction").jsonObject.getValue("target"))
                checkNotNull(session).navigation(listing.pages.single { it.nodes.any { node -> node.path == title.path && node.text == title.text } }, title)
                accept(scan("collapsed"))
                val expanded = fixture.getValue("expanded").jsonArray.map { platformJson.decodeFromJsonElement<NativeScan>(it) }
                expanded.forEach { accept(it) }
                val tracking = platformJson.decodeFromJsonElement<NativeNode>(fixture.getValue("logisticsAction").jsonObject.getValue("target"))
                checkNotNull(session).navigation(expanded.last().pages.single { it.nodes.any { node -> node.path == tracking.path && node.text == tracking.text } }, tracking)
                fixture.getValue("logistics").jsonArray.forEach { accept(platformJson.decodeFromJsonElement(it)) }
                channel.close(); worker.join()
                val progress = checkNotNull(coordinator.nativeProgressStore.read(checkNotNull(repository.getAccount(id))))
                assertFalse(progress.running)
                assertFalse(progress.complete)
                assertEquals(1, progress.orders.size)
                assertTrue(progress.orders.values.single().finished)
                assertTrue(progress.orders.values.single().logisticsSaved)
                assertTrue(stages.indexOf("BUSINESS_COMMITTED") < stages.lastIndexOf("PROGRESS_SAVED"))
                if (previousCycle != null) {
                    assertEquals(previousCycle, progress.cycleId)
                    assertNotEquals(previousCapture, progress.captureId)
                }
                previousCycle = progress.cycleId; previousCapture = progress.captureId
                assertEquals(beforeOrders, repository.getOrders())
                assertEquals(beforeQueue, repository.pendingTracking(id))
                assertEquals(account.lastSyncedAt, repository.getAccount(id)?.lastSyncedAt)
                assertEquals(AccountState.PARTIAL, repository.getAccount(id)?.state)
                write(directory, "progress-${index + 1}.json", platformJson.parseToJsonElement(platformJson.encodeToString(progress)))
            }
            write(directory, "result.json", buildJsonObject {
                put("rounds", 2); put("sameCycle", true); put("differentGenuineCaptureIds", true); put("existingOrdersPreserved", true)
                put("pendingQueuePreserved", true); put("noTerminalDoesNotComplete", true); put("businessBeforeProgress", true)
            })
        } finally { database.close() }
    }

    @Test fun verifyEntryForeground(): Unit = runBlocking {
        container.ready.await()
        val directory = output()
        val before = DouyinOrderEvidenceTest().tables()
        foregroundActivity(directory)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val root = checkNotNull(automation.rootInActiveWindow)
        assertEquals("com.logisticsmanagement.app", root.packageName?.toString())
        fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) + (0 until node.childCount).flatMap { index -> node.getChild(index)?.let(::nodes).orEmpty() }
        val current = nodes(root)
        assertTrue(current.any { it.text?.toString() == "开始新一轮" && it.isEnabled })
        assertTrue(current.any { it.text?.toString() == "继续采集" && it.isEnabled })
        assertEquals(platformJson.parseToJsonElement(before), platformJson.parseToJsonElement(DouyinOrderEvidenceTest().tables()))
        write(directory, "result.json", buildJsonObject { put("productionActivityForeground", true); put("startAndResumeButtons", true); put("businessPreserved", true) })
    }

    @Test fun productionDeadline(): Unit = runBlocking {
        val directory = output()
        val service = awaitService(directory)
        val account = checkNotNull(container.repository.getAccount(argument("accountId")))
        val oldSettings = container.settings.get()
        File(directory, "before-tables.json").writeText(DouyinOrderEvidenceTest().tables())
        val started = SystemClock.elapsedRealtime()
        var first: NativeScan? = null
        var last: NativeScan? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(DouyinCaptureController.PACKAGE))
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                DouyinCaptureController.startPaginationWithEvidence(account, directory.name, true)
                assertTrue(service.serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0)
                DouyinCaptureController.normalNavigation()
            }
            first = withTimeout(15_000) {
                var scan = service.captureNow()
                while (NativePaginationRules.activePages(scan).isEmpty()) { delay(100); scan = service.captureNow() }
                scan
            }
            var nextRead = 0L
            withTimeout(8 * 60_000L + 30_000) {
                while (DouyinCaptureController.account != null) {
                    val elapsed = SystemClock.elapsedRealtime() - started
                    if (elapsed >= nextRead && elapsed < 470_000) {
                        assertTrue(container.syncCoordinator.hasActiveAccounts())
                        last = service.captureNow()
                        write(directory, "live-status.json", buildJsonObject {
                            put("elapsedMillis", elapsed); put("serviceConnected", DouyinCaptureController.service === service)
                            put("taskActive", container.syncCoordinator.hasActiveAccounts())
                            put("normalNavigation", DouyinCaptureController.navigationMode)
                            put("sequence", checkNotNull(last).sequence)
                        })
                        nextRead = elapsed + 30_000
                    }
                    delay(100)
                }
            }
            stopped(service)
            val elapsed = SystemClock.elapsedRealtime() - started
            assertTrue("需要实际经历八分钟生产期限", elapsed in 475_000..510_000)
            val progress = checkNotNull(container.syncCoordinator.nativeProgressStore.read(account))
            assertFalse(progress.running)
            assertTrue(progress.reason.contains("执行期限") || progress.reason.contains("时间已结束"))
            assertEquals(AccountState.PARTIAL, container.repository.getAccount(account.id)?.state)
            assertEquals(account.lastSyncedAt, container.repository.getAccount(account.id)?.lastSyncedAt)
            assertEquals(oldSettings, container.settings.get())
            export(directory, directory.name)
            File(directory, "after-tables.json").writeText(DouyinOrderEvidenceTest().tables())
            write(directory, "progress.json", platformJson.parseToJsonElement(platformJson.encodeToString(progress)))
            write(directory, "result.json", buildJsonObject {
                put("productionDefaultUsed", true); put("elapsedMillis", elapsed)
                put("firstSequence", checkNotNull(first).sequence); put("lastSequence", checkNotNull(last).sequence)
                put("deadlineReleased", true); put("touchRequestReleased", true); put("taskReleased", true)
            })
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { DouyinCaptureController.stop() }
            service.awaitOrdersFinished()
        }
    }

    @Test fun exportCaptureEvidence(): Unit = runBlocking {
        container.ready.await()
        val directory = output()
        val previous = argument("taskId")
        require(previous.matches(Regex("[A-Za-z0-9-]+")))
        export(directory, previous)
        write(directory, "result.json", buildJsonObject { put("captureId", previous); put("evidenceExported", true) })
    }

    @Test fun jevConnectionEvidence(): Unit = runBlocking {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        container.ready.await()
        assertFalse(container.syncCoordinator.hasActiveAccounts())
        val directory = output()
        val before = platformJson.parseToJsonElement(DouyinOrderEvidenceTest().tables())
        val hadKey = configureJevCredential()
        val key = checkNotNull(container.credentials.getSecret(NativeSemanticJudge.CREDENTIAL_NAME))
        val client = createHttpClient().newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
        val response = try { client.readResponse(Request.Builder().url("https://api.typesafe.ai/v1/models").header("Authorization", "Bearer $key").build()) }
            finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        File(directory, "service-response.txt").writeText(response.body.replace(key, "[credential]"))
        var failure: AppFailure? = null
        try { NativeSemanticJudge(key).use { it.checkConnection() } } catch (value: AppFailure) { failure = value }
        write(directory, "result.json", buildJsonObject {
            put("httpStatus", response.status); put("bodyLength", response.body.length)
            put("failureKind", failure?.kind?.name ?: "NONE")
            put("existingCredentialRead", hadKey)
            put("businessPreserved", before == platformJson.parseToJsonElement(DouyinOrderEvidenceTest().tables()))
        })
        if (response.status == 451) assertEquals(FailureKind.SERVICE_ERROR, checkNotNull(failure).kind)
        if (response.status == 200) assertNull(failure)
        assertEquals(before, platformJson.parseToJsonElement(DouyinOrderEvidenceTest().tables()))
        assertFalse(container.syncCoordinator.hasActiveAccounts())
    }

    @Test fun jevDeviceSession(): Unit = runBlocking {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        container.ready.await()
        assertFalse(container.syncCoordinator.hasActiveAccounts())
        val oldSettings = container.settings.get()
        val hadKey = configureJevCredential()
        container.settings.save(oldSettings.copy(nativeJevEnabled = true))
        NativeSemanticJudge(checkNotNull(container.credentials.getSecret(NativeSemanticJudge.CREDENTIAL_NAME))).use { it.checkConnection() }
        val baseName = argument("runName")
        val configuration = File(context.filesDir, "douyin-order-evidence/$baseName-configuration.json")
        configuration.parentFile?.mkdirs()
        configuration.writeText(buildJsonObject {
            put("backgroundEnabledBefore", oldSettings.backgroundEnabled)
            put("nativeJevEnabledBefore", oldSettings.nativeJevEnabled)
            put("existingKeyPreserved", hadKey)
            put("secretInputDeleted", !File(context.filesDir, argument("instructionsPath")).exists())
            put("connectionVerified", true)
        }.toString())
        try {
            runSuffix = "-replay"
            capturesOverride = argument("replayPath")
            replayRecovery()
        } finally { runSuffix = ""; capturesOverride = null }
        continuousJevRecovery()
        assertEquals(oldSettings.backgroundEnabled, container.settings.get().backgroundEnabled)
    }

    @Test fun continuousJevRecovery(): Unit = runBlocking {
        container.ready.await()
        assertTrue("本项需要在设置中启用Jev", container.settings.get().nativeJevEnabled)
        assertFalse("本项需要有效Jev凭据", container.credentials.getSecret(NativeSemanticJudge.CREDENTIAL_NAME).isNullOrBlank())
        continuousRecovery()
        val directory = File(context.filesDir, "douyin-order-evidence/${argument("runName")}")
        for (round in 1..2) {
            val records = checkNotNull(File(directory, "round-$round").listFiles()).filter { it.name.startsWith("pagination-") && it.extension == "json" }
                .map { platformJson.parseToJsonElement(it.readText()).jsonObject }
                .filter { it["action"]?.jsonPrimitive?.content == "JEV_DECISION" }
            assertTrue("每轮需要真实Jev判断记录", records.isNotEmpty())
            assertTrue(records.any { record ->
                val value = record.getValue("record").jsonObject
                val response = value["response"] as? JsonObject
                response?.get("model")?.jsonPrimitive?.content == NativeSemanticJudge.MODEL
            })
        }
    }

    @Test fun continuousRecovery(): Unit = runBlocking {
        val directory = output()
        val service = awaitService(directory)
        val account = checkNotNull(container.repository.getAccount(argument("accountId")))
        val target = platformJson.decodeFromString<FixedFlowTarget>(input("capturesPath").readText())
        File(directory, "before-tables.json").writeText(DouyinOrderEvidenceTest().tables())
        val settings = container.settings.get()
        var cycleId = ""
        try {
            for (round in 1..2) {
                val captureId = "${directory.name}-round-$round"
                val resultDirectory = File(directory, "round-$round").apply { check(mkdirs()) }
                var stoppedAtCommit = false
                container.syncCoordinator.nativeCheckpoint = { stage, remote ->
                    if (round == 1 && stage == "BUSINESS_COMMITTED" && remote == target.orderId && !stoppedAtCommit) {
                        stoppedAtCommit = true
                        withContext(Dispatchers.Main.immediate) { DouyinCaptureController.stop("采集已主动停止") }
                    }
                }
                withContext(Dispatchers.Main.immediate) { DouyinCaptureController.startPaginationWithEvidence(account, captureId, round > 1) }
                if (round == 1) {
                    withTimeout(8 * 60_000L + 15_000) { while (DouyinCaptureController.account != null) delay(100) }
                } else {
                    withTimeout(8 * 60_000L + 15_000) {
                        while (DouyinCaptureController.account != null) {
                            val progress = container.syncCoordinator.nativeProgressStore.read(account)
                            val sources = File(context.filesDir, "native-entry-evidence/$captureId").listFiles().orEmpty().filter { it.name.startsWith("pagination-") && it.extension == "enc" }
                            val moved = sources.any { file ->
                                val event = platformJson.parseToJsonElement(container.credentials.decrypt(file.readText())).jsonObject
                                event["action"]?.jsonPrimitive?.content == "SCROLL" && event["changed"]?.jsonPrimitive?.boolean == true
                            }
                            if (progress?.orders?.get(target.orderId)?.finished == true && progress.orders.size >= 2 && moved) {
                                withContext(Dispatchers.Main.immediate) { DouyinCaptureController.stop("分页及恢复验证完成，已主动停止") }
                                break
                            }
                            delay(200)
                        }
                    }
                }
                stopped(service)
                export(resultDirectory, captureId)
                if (round == 1) assertTrue("需要在真实目标业务事务之后停止", stoppedAtCommit)
                val progress = checkNotNull(container.syncCoordinator.nativeProgressStore.read(account))
                assertFalse(progress.running)
                assertTrue(progress.orders.containsKey(target.orderId))
                if (round == 1) {
                    cycleId = progress.cycleId
                    assertTrue(progress.orders.getValue(target.orderId).orderSaved)
                } else {
                    assertEquals(cycleId, progress.cycleId)
                    assertTrue(progress.orders.getValue(target.orderId).finished)
                    assertTrue(progress.orders.size >= 2)
                }
                write(resultDirectory, "progress.json", platformJson.parseToJsonElement(platformJson.encodeToString(progress)))
                File(resultDirectory, "tables.json").writeText(DouyinOrderEvidenceTest().tables())
            }
            container.syncCoordinator.nativeCheckpoint = { _, _ -> }
            val shortId = "${directory.name}-deadline"
            withContext(Dispatchers.Main.immediate) { DouyinCaptureController.startPaginationWithEvidence(account, shortId, true, 5_000) }
            var duplicateRejected = false
            try { withContext(Dispatchers.Main.immediate) { DouyinCaptureController.startPaginationWithEvidence(account, "$shortId-duplicate", true) } }
            catch (failure: AppFailure) { duplicateRejected = failure.kind == FailureKind.CONFIGURATION }
            assertTrue(duplicateRejected)
            withTimeout(2_000) { while (!container.syncCoordinator.hasActiveAccounts()) delay(25) }
            withContext(Dispatchers.Main.immediate) { DouyinCaptureController.normalNavigation() }
            assertEquals(0, service.serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE)
            withContext(Dispatchers.Main.immediate) { DouyinCaptureController.resumeCapture() }
            assertNull(service.orderSession?.identity)
            withTimeout(12_000) { while (DouyinCaptureController.account != null) delay(100) }
            stopped(service)
            val deadlineDirectory = File(directory, "deadline").apply { check(mkdirs()) }
            export(deadlineDirectory, shortId)
            val expired = checkNotNull(container.syncCoordinator.nativeProgressStore.read(account))
            assertTrue(expired.reason.contains("执行期限"))
            assertFalse(expired.running)
            assertEquals(cycleId, expired.cycleId)
            verifyProgressUi(directory, account, service)
            assertEquals(settings, container.settings.get())
            File(directory, "after-tables.json").writeText(DouyinOrderEvidenceTest().tables())
            write(directory, "progress.json", platformJson.parseToJsonElement(platformJson.encodeToString(expired)))
            write(directory, "result.json", buildJsonObject {
                put("rounds", 2); put("realBusinessStop", true); put("resumedSameCycle", true); put("multipleRealOrders", true)
                put("deadlineReleased", true); put("duplicateRejected", true); put("touchNavigationAndResume", true)
                put("productionProgressUi", true); put("resumeButtonStartsSameCycle", true)
            })
        } finally {
            container.syncCoordinator.nativeCheckpoint = { _, _ -> }
            withContext(NonCancellable + Dispatchers.Main.immediate) { DouyinCaptureController.stop() }
            service.awaitOrdersFinished()
        }
    }

    @Test fun checkpointWindow(): Unit = runBlocking {
        val directory = output()
        val service = awaitService(directory)
        val account = checkNotNull(container.repository.getAccount(argument("accountId")))
        val target = platformJson.decodeFromString<FixedFlowTarget>(input("capturesPath").readText())
        val wanted = argument("taskId")
        require(wanted in setOf("BUSINESS_COMMITTED", "PROGRESS_SAVED"))
        File(directory, "before-tables.json").writeText(DouyinOrderEvidenceTest().tables())
        var committed = false
        var marked = false
        container.syncCoordinator.nativeCheckpoint = { stage, remote ->
            if (stage == "BUSINESS_COMMITTED" && remote == target.orderId) committed = true
            if (committed && stage == wanted && !marked) {
                marked = true
                val progress = checkNotNull(container.syncCoordinator.nativeProgressStore.read(account))
                write(directory, "kill-ready.json", buildJsonObject {
                    put("stage", stage); put("captureId", directory.name); put("cycleId", progress.cycleId)
                    put("remoteOrderId", target.orderId); put("orderSaved", progress.orders[target.orderId]?.orderSaved == true)
                })
                delay(10_000)
            }
        }
        try {
            withContext(Dispatchers.Main.immediate) { DouyinCaptureController.startPaginationWithEvidence(account, directory.name, false) }
            withTimeout(8 * 60_000L + 15_000) { while (true) delay(100) }
        } finally {
            container.syncCoordinator.nativeCheckpoint = { _, _ -> }
            withContext(NonCancellable + Dispatchers.Main.immediate) { DouyinCaptureController.stop() }
            service.awaitOrdersFinished()
        }
    }

    @Test fun verifyRestart(): Unit = runBlocking {
        container.ready.await()
        val directory = output()
        val previous = argument("taskId")
        val marker = platformJson.parseToJsonElement(File(context.filesDir, "douyin-order-evidence/$previous/kill-ready.json").readText()).jsonObject
        val account = checkNotNull(container.repository.getAccount(argument("accountId")))
        val progress = checkNotNull(container.syncCoordinator.nativeProgressStore.read(account))
        assertEquals(marker.getValue("cycleId").jsonPrimitive.content, progress.cycleId)
        assertEquals(marker.getValue("captureId").jsonPrimitive.content, progress.captureId)
        assertFalse(progress.running)
        assertTrue(progress.reason.contains("上次采集已中断"))
        assertEquals(AccountState.PARTIAL, account.state)
        assertFalse(container.syncCoordinator.hasActiveAccounts())
        assertNull(DouyinCaptureController.account)
        write(directory, "progress.json", platformJson.parseToJsonElement(platformJson.encodeToString(progress)))
        File(directory, "tables.json").writeText(DouyinOrderEvidenceTest().tables())
        write(directory, "result.json", buildJsonObject { put("applicationRecovery", true); put("progressRetained", true); put("noAutomaticCapture", true) })
    }
}
