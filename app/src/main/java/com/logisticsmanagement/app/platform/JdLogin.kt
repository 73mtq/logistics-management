package com.logisticsmanagement.app.platform

import android.content.Context
import android.os.SystemClock
import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
internal data class JdLoginInput(val index: Int, val placeholder: String, val type: String, val filled: Boolean)

@Serializable
internal data class JdLoginCheckbox(val index: Int, val checked: Boolean, val label: String)

@Serializable
internal data class JdLoginPoint(val x: Float, val y: Float, val w: Float, val h: Float, val tag: String = "", val cls: String = "")

@Serializable
internal data class JdLoginProbe(
    val url: String,
    val text: String,
    val buttons: List<String>,
    val inputs: List<JdLoginInput>,
    val checkboxes: List<JdLoginCheckbox> = emptyList(),
    val agreeHtml: String = "",
) {
    fun challengeText(): String? = listOf("安全验证", "请完成验证", "滑动验证", "请拖动滑块", "访问过于频繁", "人机验证", "检测到安全风险")
        .firstOrNull { text.contains(it) }
    val quickEnter: String? get() = buttons.firstOrNull { it == "快速进入" || it == "一键登录" }
    val simEnter: String? get() = buttons.firstOrNull { listOf("本机号码一键登录", "本机号码登录", "本机号码免密登录").any { label -> it == label } }
    val entry: String? get() = buttons.firstOrNull { listOf("短信验证码登录", "短信登录", "验证码登录", "手机号登录").any { label -> it == label } }
    val sendCode: String? get() = buttons.firstOrNull { listOf("获取验证码", "发送验证码", "获取短信验证码", "重新发送", "重新获取").any { label -> it == label } }
    val loginLabel: String? get() = buttons.firstOrNull { it == "登录" || it == "立即登录" || it == "同意并登录" || it == "同意协议并登录" }
    val agreementPending: Boolean get() = text.contains("请阅读并同意") || text.contains("请先阅读") || text.contains("请勾选")
    val codeSent: Boolean get() = text.contains("验证码已发送") || text.contains("秒后重发") || text.contains("重新获取") || text.contains("重新发送")
    val phoneInput: JdLoginInput? get() = inputs.firstOrNull { it.placeholder.contains("手机号") || it.placeholder.contains("手机号码") }
        ?: inputs.firstOrNull { it.type == "tel" && !it.placeholder.contains("验证码") && !it.placeholder.contains("密码") }
    val codeInput: JdLoginInput? get() = inputs.firstOrNull { it.placeholder.contains("验证码") }
    val passwordInput: JdLoginInput? get() = inputs.firstOrNull { it.type == "password" || it.placeholder.contains("密码") }
    val smsPath: Boolean get() = entry != null || sendCode != null || codeInput != null || phoneInput != null
    val manualOnly: Boolean get() = !smsPath && (passwordInput != null || text.contains("扫码登录"))
}

@Serializable
internal data class JdLoginStep(val at: Long, val url: String, val action: String, val detail: String)

internal sealed interface JdLoginResult {
    data object Succeeded : JdLoginResult
    data class Blocked(val message: String, val kind: FailureKind) : JdLoginResult
}

internal class JdLoginFlow(
    private val browser: PlatformWebView,
    private val context: Context,
    private val account: Account,
    private val sessionReady: () -> Boolean,
    private val onStatus: (String) -> Unit,
) {
    private val steps = mutableListOf<JdLoginStep>()
    private var candidates: List<String> = emptyList()
    private var candidateIndex = 0
    private var phoneFilled = false
    private var entryClicked = false
    private var quickClicked = false
    private var simClicked = false
    private var codeRequestedAt = 0L
    private var codeFilled = false
    private var loginClicked = false
    private var attempts = 0
    private var agreementAttempts = 0
    private var rawProbeSaved = false
    private var lastRawProbe = ""
    private var challengeMessage = ""

    fun evidence(): List<JdLoginStep> = steps.toList()

    suspend fun run(deadlineMillis: Long): JdLoginResult {
        val deadline = SystemClock.elapsedRealtime() + deadlineMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (sessionReady()) return JdLoginResult.Succeeded
            val probe = probe()
            if (probe == null) {
                delay(1_500)
                continue
            }
            recordProbe(probe)
            val challenge = probe.challengeText()
            if (challenge != null) {
                if (challengeMessage.isBlank()) {
                    challengeMessage = "京东登录页要求完成安全验证（" + challenge + "）"
                    onStatus(challengeMessage)
                }
                step(probe.url, "challenge", challenge)
                delay(2_000)
                continue
            }
            if (probe.agreementPending && agreementAttempts < 5) {
                agreementAttempts++
                val result = withTimeoutOrNull(4_000) { browser.evaluateAwait(LoginPageScripts.agreeCircle()) } ?: "timeout"
                step(probe.url, "agree-circle", result)
                delay(800)
                continue
            }
            if (probe.checkboxes.any { !it.checked } && agreementAttempts < 5) {
                agreementAttempts++
                val result = withTimeoutOrNull(4_000) { browser.evaluateAwait(LoginPageScripts.agree) } ?: "timeout"
                step(probe.url, "agree", result)
                delay(600)
                continue
            }
            if (probe.buttons.contains("同意") && (probe.text.contains("协议") || probe.text.contains("隐私")) && agreementAttempts < 5) {
                agreementAttempts++
                if (click("同意", probe.url)) {
                    delay(1_000)
                    continue
                }
            }
            if (!quickClicked) {
                val quick = probe.quickEnter
                if (quick != null) {
                    if (click(quick, probe.url)) {
                        quickClicked = true
                        onStatus("正在通过“" + quick + "”恢复京东会话")
                        delay(2_500)
                        continue
                    }
                }
            }
            val sim = probe.simEnter
            if (!simClicked && sim != null) {
                if (click(sim, probe.url)) {
                    simClicked = true
                    onStatus("正在通过“" + sim + "”恢复京东会话")
                    delay(2_500)
                    continue
                }
            }
            if (!entryClicked && probe.phoneInput == null && probe.codeInput == null) {
                val entry = probe.entry
                if (entry != null) {
                    if (click(entry, probe.url)) {
                        entryClicked = true
                        step(probe.url, "entry", entry)
                        delay(1_500)
                        continue
                    }
                }
            }
            if (probe.manualOnly) return JdLoginResult.Blocked("京东登录页只提供密码或扫码登录", FailureKind.NEEDS_LOGIN)
            if (probe.smsPath || probe.loginLabel != null) {
                smsStep(probe)?.let { return it }
                delay(1_500)
                continue
            }
            delay(1_500)
        }
        if (challengeMessage.isNotBlank()) return JdLoginResult.Blocked(challengeMessage, FailureKind.NEEDS_VERIFICATION)
        return JdLoginResult.Blocked("京东登录页在期限内没有完成登录", FailureKind.NEEDS_LOGIN)
    }

    private suspend fun smsStep(probe: JdLoginProbe): JdLoginResult? {
        if (candidates.isEmpty()) {
            candidates = preferredNumbers()
            if (candidates.isEmpty()) {
                return JdLoginResult.Blocked("京东登录页要求输入手机号，设备没有提供本机号码", FailureKind.NEEDS_LOGIN)
            }
            step(probe.url, "numbers", candidates.joinToString(",") { PhoneNumbers.mask(it) })
        }
        if (probe.text.contains("手机号码格式不正确") || probe.text.contains("请输入正确的手机号")) {
            step(probe.url, "invalid-number", "京东不接受当前号码")
            return nextCandidate(probe)
        }
        if (probe.text.contains("验证码错误") || probe.text.contains("验证码不正确") || probe.text.contains("验证码已失效")) {
            codeFilled = false
            loginClicked = false
            codeRequestedAt = 0L
            step(probe.url, "code-rejected", "验证码没有通过，重新请求")
            return null
        }
        val phoneInput = probe.phoneInput
        if (phoneInput != null && !phoneFilled) {
            fill(phoneInput.index, candidates[candidateIndex], probe.url)
            phoneFilled = true
            onStatus("已填入本机号码，等待请求验证码")
            return null
        }
        val codeInput = probe.codeInput
        if (codeInput != null) {
            if (codeRequestedAt == 0L) {
                val send = probe.sendCode ?: probe.loginLabel ?: return null
                SmsCodeInbox.begin()
                if (click(send, probe.url)) {
                    codeRequestedAt = SystemClock.elapsedRealtime()
                    onStatus("已请求京东短信验证码，等待通知")
                }
                return null
            }
            if (!codeFilled) {
                if (SystemClock.elapsedRealtime() - codeRequestedAt > 60_000) return codeTimeout(probe)
                val incoming = withTimeoutOrNull(1_000) { SmsCodeInbox.code.first { it != null } } ?: return null
                fill(codeInput.index, incoming.code, probe.url)
                codeFilled = true
                onStatus("已填入京东短信验证码")
                return null
            }
            if (!loginClicked) {
                val label = probe.loginLabel ?: return null
                if (click(label, probe.url)) {
                    loginClicked = true
                    onStatus("已提交京东登录")
                }
            }
            return null
        }
        val sendCode = probe.sendCode
        if (sendCode != null) {
            if (codeRequestedAt == 0L) {
                SmsCodeInbox.begin()
                if (click(sendCode, probe.url)) {
                    codeRequestedAt = SystemClock.elapsedRealtime()
                    onStatus("已请求京东短信验证码，等待通知")
                }
                return null
            }
            if (!codeFilled && SystemClock.elapsedRealtime() - codeRequestedAt > 60_000) return codeTimeout(probe)
            return null
        }
        val loginLabel = probe.loginLabel
        if (loginLabel != null) {
            if (codeRequestedAt == 0L) {
                SmsCodeInbox.begin()
                if (click(loginLabel, probe.url)) {
                    codeRequestedAt = SystemClock.elapsedRealtime()
                    onStatus("已提交手机号，等待短信验证码")
                }
                return null
            }
            if (!codeFilled && SystemClock.elapsedRealtime() - codeRequestedAt > 60_000) return codeTimeout(probe)
            return null
        }
        return null
    }

    private fun codeTimeout(probe: JdLoginProbe): JdLoginResult? {
        attempts++
        step(probe.url, "code-timeout", "60 秒没有收到验证码，第 " + attempts + " 次")
        codeRequestedAt = 0L
        if (attempts >= 2) return nextCandidate(probe)
        return null
    }

    private fun nextCandidate(probe: JdLoginProbe): JdLoginResult? {
        if (candidateIndex + 1 >= candidates.size) {
            return JdLoginResult.Blocked("设备上的本机号码都没有完成京东登录", FailureKind.NEEDS_LOGIN)
        }
        candidateIndex++
        phoneFilled = false
        codeRequestedAt = 0L
        codeFilled = false
        loginClicked = false
        attempts = 0
        step(probe.url, "next-number", PhoneNumbers.mask(candidates[candidateIndex]))
        onStatus("当前号码没有完成登录，尝试下一个号码")
        return null
    }

    private fun preferredNumbers(): List<String> {
        val owned = PhoneNumbers.ownNumbers(context)
        val fromLabel = Regex("1[3-9][0-9]{9}").find(account.label)?.value
        return if (fromLabel == null) owned else listOf(fromLabel) + owned.filter { it != fromLabel }
    }

    private suspend fun probe(): JdLoginProbe? {
        val raw = withTimeoutOrNull(4_000) { browser.evaluateAwait(LoginPageScripts.probe) } ?: return null
        val inner = (platformJson.parseToJsonElement(raw) as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (inner.isBlank()) return null
        lastRawProbe = inner
        return platformJson.decodeFromString(inner)
    }

    private fun recordProbe(probe: JdLoginProbe) {
        val informative = probe.inputs.isNotEmpty() || probe.buttons.isNotEmpty()
        if (!rawProbeSaved && informative) {
            rawProbeSaved = true
            step(probe.url, "probe", lastRawProbe.take(4_000))
        } else {
            step(probe.url, "probe", probe.url + " :: " + probe.buttons.take(16).joinToString("|") +
                " inputs=" + probe.inputs.joinToString(",") { it.index.toString() + ":" + it.placeholder + ":" + it.type })
        }
    }

    private suspend fun click(label: String, url: String): Boolean {
        val raw = withTimeoutOrNull(4_000) { browser.evaluateAwait(LoginPageScripts.locate(label)) }
        if (raw == null) {
            step(url, "tap:" + label, "timeout")
            return false
        }
        val inner = (platformJson.parseToJsonElement(raw) as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (inner == "missing" || inner.isBlank()) {
            step(url, "tap:" + label, "missing")
            return false
        }
        val point = platformJson.decodeFromString<JdLoginPoint>(inner)
        browser.tapPage(point.x, point.y, point.w, point.h)
        step(url, "tap:" + label, point.tag + "|" + point.cls)
        return true
    }

    private suspend fun fill(index: Int, value: String, url: String) {
        val result = withTimeoutOrNull(4_000) { browser.evaluateAwait(LoginPageScripts.fill(index, value)) }
            ?: throw AppFailure("京东登录页没有响应输入操作", FailureKind.UNSUPPORTED_RESPONSE)
        step(url, "fill:" + index, if (result.contains("filled")) "filled:" + value.length else result)
    }

    private fun step(url: String, action: String, detail: String) {
        steps.add(JdLoginStep(System.currentTimeMillis(), url, action, detail.take(4_000)))
        android.util.Log.i("JdLogin", "action=" + action + " detail=" + detail.take(500))
    }
}
