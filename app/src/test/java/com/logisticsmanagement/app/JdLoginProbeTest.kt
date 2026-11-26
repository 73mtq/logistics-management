package com.logisticsmanagement.app

import com.logisticsmanagement.app.platform.JdLoginInput
import com.logisticsmanagement.app.platform.JdLoginProbe
import com.logisticsmanagement.app.platform.platformJson
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JdLoginProbeTest {
    private fun realProbe(): JdLoginProbe {
        val raw = checkNotNull(javaClass.classLoader?.getResourceAsStream("jd-login-probe.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        return platformJson.decodeFromString(raw)
    }

    @Test fun readsRealLoginPage() {
        val probe = realProbe()
        assertEquals("https://plogin.m.jd.com/login/login?appid=300&returnurl=https%3A%2F%2Fhome.m.jd.com%2FmyJd%2Fhome.action", probe.url)
        assertEquals("请输入手机号", probe.phoneInput?.placeholder)
        assertEquals("请输入收到的验证码", probe.codeInput?.placeholder)
        assertEquals("获取验证码", probe.sendCode)
        assertEquals("同意协议并登录", probe.loginLabel)
        assertNull(probe.challengeText())
        assertTrue(probe.smsPath)
        assertFalse(probe.manualOnly)
    }

    @Test fun detectsChallenge() {
        val probe = realProbe().copy(text = "平台要求完成安全验证 拖动箭头填充拼图")
        assertEquals("安全验证", probe.challengeText())
    }

    @Test fun blocksPasswordOnlyPage() {
        val probe = JdLoginProbe("https://plogin.m.jd.com/login/login", "账号密码登录", listOf("登录"),
            listOf(JdLoginInput(0, "请输入密码", "password", false)))
        assertTrue(probe.manualOnly)
        assertFalse(probe.smsPath)
    }
}
