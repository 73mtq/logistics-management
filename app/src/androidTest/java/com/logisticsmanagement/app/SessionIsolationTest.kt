package com.logisticsmanagement.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.platform.cookieValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionIsolationTest {
    @Test fun authenticatedPlatformsRetainSeparateProfiles(): Unit = runBlocking {
        val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LogisticsApplication).container
        val accounts = container.repository.getAccounts()
        val jd = accounts.firstOrNull { it.platform == Platform.JD }
        val pdd = accounts.firstOrNull { it.platform == Platform.PDD }
        assumeTrue("需要已经登录的真实京东及拼多多账号", jd != null && pdd != null)
        withContext(Dispatchers.Main.immediate) {
            val jdProfile = container.platforms.profiles.profile(checkNotNull(jd))
            val pddProfile = container.platforms.profiles.profile(checkNotNull(pdd))
            assertTrue("两个平台账号需要使用不同 profile", jd.profileName != pdd.profileName)
            val jdCookies = cookieValues(jdProfile, "https://www.jd.com/")
            val pddCookies = cookieValues(pddProfile, "https://mobile.yangkeduo.com/")
            assertTrue("京东真实认证 Cookie 应当存在", !jdCookies["pt_key"].isNullOrBlank() && !jdCookies["pt_pin"].isNullOrBlank())
            assertTrue("拼多多真实认证 Cookie 应当存在", !pddCookies["PDDAccessToken"].isNullOrBlank())
            val jdInPdd = cookieValues(pddProfile, "https://www.jd.com/")
            val pddInJd = cookieValues(jdProfile, "https://mobile.yangkeduo.com/")
            assertFalse("拼多多 profile 不应取得京东认证 Cookie", jdInPdd.containsKey("pt_key") || jdInPdd.containsKey("pt_pin"))
            assertFalse("京东 profile 不应取得拼多多认证 Cookie", pddInJd.containsKey("PDDAccessToken"))
        }
    }
    @Test fun authenticatedAndUnloggedProfilesUseSeparateCookies(): Unit = runBlocking {
        val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LogisticsApplication).container
        val authenticated = container.repository.getAccounts().firstOrNull { it.platform == Platform.JD }
        assumeTrue("需要已经登录的真实京东账号", authenticated != null)
        val account = checkNotNull(authenticated)
        val original = withContext(Dispatchers.Main.immediate) {
            cookieValues(container.platforms.profiles.profile(account), "https://www.jd.com/")
        }
        assertTrue("真实登录账号需要包含京东认证 Cookie", !original["pt_key"].isNullOrBlank() && !original["pt_pin"].isNullOrBlank())
        val unlogged = container.repository.createAccount(Platform.JD, "会话隔离检查")
        try {
            val separate = withContext(Dispatchers.Main.immediate) {
                cookieValues(container.platforms.profiles.profile(unlogged), "https://www.jd.com/")
            }
            assertFalse("新创建的未登录 profile 不应取得另一个 profile 的认证 Cookie", separate.containsKey("pt_key") || separate.containsKey("pt_pin"))
            container.syncCoordinator.removeAccount(unlogged.id)
            val retained = withContext(Dispatchers.Main.immediate) {
                cookieValues(container.platforms.profiles.profile(account), "https://www.jd.com/")
            }
            assertTrue("移除未登录 profile 后真实账号仍保留其认证 Cookie", original["pt_key"] == retained["pt_key"] && original["pt_pin"] == retained["pt_pin"])
        } finally {
            withContext(NonCancellable) {
                if (container.repository.getAccount(unlogged.id)?.deleted == false) container.syncCoordinator.removeAccount(unlogged.id)
            }
        }
    }
}
