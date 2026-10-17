package com.logisticsmanagement.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.platform.TaobaoAdapter
import com.logisticsmanagement.app.platform.cookieValues
import com.logisticsmanagement.app.platform.hasPlatformLoginSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaobaoLoginEvidenceTest {
    @Test fun recordSessionSignalsAndOrderQuery(): Unit = runBlocking {
        val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LogisticsApplication).container
        val account = container.repository.getAccounts().firstOrNull { it.platform == Platform.TAOBAO }
        assertNotNull("需要已经创建的真实淘宝账号", account)
        val current = checkNotNull(account)
        Log.i("TaobaoEvidence", "accountId=${current.id}, label=${current.label}, profileName=${current.profileName}, state=${current.state}, enabled=${current.enabled}, deleted=${current.deleted}")
        val signals = withContext(Dispatchers.Main.immediate) {
            listOf("https://h5.m.taobao.com/", "https://login.m.taobao.com/").associateWith { url ->
                cookieValues(container.platforms.profiles.profile(current), url)
            }
        }
        for ((url, cookies) in signals) {
            val keys = listOf("cookie2", "unb", "cookie1", "_m_h5_tk")
            Log.i("TaobaoEvidence", "sessionUrl=$url, present=${keys.filter { !cookies[it].isNullOrBlank() }.joinToString(",")}")
        }
        val hasSession = withContext(Dispatchers.Main.immediate) { hasPlatformLoginSession(container.platforms.profiles.profile(current), current.platform) }
        Log.i("TaobaoEvidence", "hasPlatformLoginSession=$hasSession")
        if (!hasSession) {
            Log.i("TaobaoEvidence", "orderQuery=skipped, reason=NEEDS_LOGIN")
            return@runBlocking
        }
        try {
            val adapter = container.platforms.adapter(current.platform) as TaobaoAdapter
            val root = adapter.diagnoseOrderQuery(current)
            val result = root["ret"]?.toString() ?: "missing"
            Log.i("TaobaoEvidence", "orderQuery=ret[$result]")
        } catch (failure: AppFailure) {
            Log.i("TaobaoEvidence", "orderQuery=failed, kind=${failure.kind}, message=${failure.message}")
        }
    }
}
