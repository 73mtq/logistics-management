package com.logisticsmanagement.app

import com.logisticsmanagement.app.platform.PddLoginInput
import com.logisticsmanagement.app.platform.PddLoginProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PddLoginProbeTest {
    @Test fun detectsSafetyChallenge() {
        val probe = PddLoginProbe("https://mobile.yangkeduo.com/login.html", "安全验证 请完成验证后继续操作", emptyList(), emptyList())
        assertEquals("安全验证", probe.challengeText)
    }

    @Test fun detectsSliderChallenge() {
        val probe = PddLoginProbe("https://mobile.yangkeduo.com/login.html", "请拖动滑块完成验证", emptyList(), emptyList())
        assertEquals("请拖动滑块", probe.challengeText)
    }

    @Test fun normalPhonePageHasNoChallenge() {
        val probe = PddLoginProbe("https://mobile.yangkeduo.com/login.html", "手机登录 请输入手机号码 发送验证码",
            listOf("发送验证码"), listOf(PddLoginInput(0, "请输入手机号码", "tel", false)))
        assertNull(probe.challengeText)
        assertFalse(probe.qrOnly)
        assertEquals(0, probe.phoneInput?.index)
        assertEquals("发送验证码", probe.sendCode)
    }

    @Test fun codePageUsesCodeInput() {
        val probe = PddLoginProbe("https://mobile.yangkeduo.com/login.html", "请输入验证码 重新发送(59)", emptyList(),
            listOf(PddLoginInput(0, "请输入验证码", "tel", false)))
        assertEquals(0, probe.codeInput?.index)
        assertTrue(probe.codeSent)
    }

    @Test fun scanOnlyPageBlocks() {
        val probe = PddLoginProbe("https://mobile.yangkeduo.com/login.html", "为了保障你的账号安全，请使用App扫码登录 打开拼多多APP",
            listOf("打开拼多多APP"), emptyList())
        assertTrue(probe.qrOnly)
    }

    @Test fun agreementPendingDetected() {
        val probe = PddLoginProbe("https://mobile.yangkeduo.com/login.html", "请先勾选同意服务协议与隐私政策，再进行登录", emptyList(), emptyList())
        assertTrue(probe.agreementPending)
    }
}
