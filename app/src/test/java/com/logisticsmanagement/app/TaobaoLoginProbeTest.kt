package com.logisticsmanagement.app

import com.logisticsmanagement.app.platform.TaobaoLoginInput
import com.logisticsmanagement.app.platform.TaobaoLoginProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaobaoLoginProbeTest {
    @Test fun detectsSafetyChallenge() {
        val probe = TaobaoLoginProbe("https://login.m.taobao.com/", "请完成验证后继续操作", emptyList(), emptyList())
        assertEquals("请完成验证", probe.challengeText())
    }

    @Test fun detectsSliderChallenge() {
        val probe = TaobaoLoginProbe("https://login.m.taobao.com/", "请拖动滑块完成验证", emptyList(), emptyList())
        assertEquals("请拖动滑块", probe.challengeText())
    }

    @Test fun normalLoginPageHasNoChallenge() {
        val probe = TaobaoLoginProbe("https://login.m.taobao.com/", "请输入手机号 获取验证码", emptyList(), emptyList())
        assertNull(probe.challengeText())
    }

    @Test fun codeModeUsesSingleTelInput() {
        val probe = TaobaoLoginProbe("https://login.m.taobao.com/", "已向 +86 13800000000 发送验证码",
            emptyList(), listOf(TaobaoLoginInput(0, "", "tel", false)))
        assertNull(probe.phoneInput)
        assertEquals(0, probe.codeInput?.index)
    }

    @Test fun phonePageUsesTelInputAsMobile() {
        val probe = TaobaoLoginProbe("https://login.m.taobao.com/", "请输入手机号",
            listOf("获取验证码"), listOf(TaobaoLoginInput(1, "", "tel", false)))
        assertEquals(1, probe.phoneInput?.index)
        assertTrue(probe.codeInput == null)
    }
}
