package com.logisticsmanagement.app

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.ProfileStore
import com.logisticsmanagement.app.data.LogisticsDatabase
import com.logisticsmanagement.app.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class S10ProfileDeferTest {
    @Test fun inUseProfileDeletionIsDeferred(): Unit = runBlocking {
        val context = S10Evidence.context
        val application = context.applicationContext as LogisticsApplication
        application.container.ready.await()
        val databaseName = "s10-profile-" + S10Evidence.argument("runName").lowercase() + ".db"
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, LogisticsDatabase::class.java, databaseName).build()
        val container = AppContainer(context, database).apply { ready.complete(Unit) }
        val account = container.repository.createAccount(Platform.JD, "S10 延迟删除")
        val prefs = context.getSharedPreferences("webview_profiles", Context.MODE_PRIVATE)
        try {
            withContext(Dispatchers.Main.immediate) {
                ProfileStore.getInstance().getOrCreateProfile(account.profileName)
            }
            container.platforms.deleteAccountProfile(account)
            val pending = prefs.getStringSet("pending_deletions", emptySet()).orEmpty().toSet()
            assertTrue("待删除记录应包含目标 profile", account.profileName in pending)
            withContext(Dispatchers.Main.immediate) {
                assertNotNull("本进程创建且未加载的 profile 应保留到下次启动", ProfileStore.getInstance().getProfile(account.profileName))
            }
            S10Evidence.save(S10Evidence.directory("profile"), "result.json", buildJsonObject {
                put("profileName", account.profileName)
                put("pendingCount", pending.size)
                put("profileKept", true)
            })
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
