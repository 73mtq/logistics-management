package com.logisticsmanagement.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class S8BackgroundExecutionTest {
    @Test fun recordBefore(): Unit = runBlocking {
        val context = S8Evidence.context
        val application = context.applicationContext as LogisticsApplication
        application.container.ready.await()
        val accountId = S8Evidence.argument("accountId")
        val info = workInfo(context, accountId)
        val account = application.container.repository.getAccount(accountId) ?: error("缺少目标账号")
        S8Evidence.save(S8Evidence.directory("background"), "before.json", buildJsonObject {
            put("workSpecId", info.id.toString())
            put("state", info.state.name)
            put("runAttemptCount", info.runAttemptCount)
            account.lastAttemptAt?.let { put("lastAttemptAt", it) }
        })
    }

    @Test fun assertAfter(): Unit = runBlocking {
        val context = S8Evidence.context
        val application = context.applicationContext as LogisticsApplication
        application.container.ready.await()
        val accountId = S8Evidence.argument("accountId")
        val directory = S8Evidence.directory("background")
        val beforeFile = File(directory, "before.json")
        check(beforeFile.isFile) { "缺少后台任务执行前记录" }
        val before = Json.parseToJsonElement(beforeFile.readText()).jsonObject
        val beforeCount = before.getValue("runAttemptCount").jsonPrimitive.int
        val beforeLast = before["lastAttemptAt"]?.jsonPrimitive?.long
        val info = waitForSettled(context, accountId)
        val account = application.container.repository.getAccount(accountId) ?: error("缺少目标账号")
        assertEquals("周期任务应回到排队状态", WorkInfo.State.ENQUEUED, info.state)
        assertNotNull("目标账号缺少 lastAttemptAt", account.lastAttemptAt)
        val currentLast = checkNotNull(account.lastAttemptAt)
        if (beforeLast == null || System.currentTimeMillis() - beforeLast > 15 * 60_000L) {
            assertTrue("目标账号 lastAttemptAt 没有更新", beforeLast == null || currentLast > beforeLast)
        }
        S8Evidence.save(directory, "after.json", buildJsonObject {
            put("workSpecId", info.id.toString())
            put("state", info.state.name)
            put("runAttemptCountBefore", beforeCount)
            put("runAttemptCountAfter", info.runAttemptCount)
            beforeLast?.let { put("lastAttemptAtBefore", it) }
            put("lastAttemptAtAfter", currentLast)
        })
    }

    private fun workInfo(context: android.content.Context, accountId: String): WorkInfo {
        val name = "account_$accountId"
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get(30, TimeUnit.SECONDS)
        return infos.maxByOrNull { it.runAttemptCount } ?: error("未登记后台任务：$name")
    }

    private suspend fun waitForSettled(context: android.content.Context, accountId: String): WorkInfo {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val info = workInfo(context, accountId)
            if (info.state != WorkInfo.State.RUNNING) return info
            delay(500)
        }
        error("后台任务仍在运行")
    }
}
