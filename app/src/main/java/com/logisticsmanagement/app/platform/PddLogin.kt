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
internal data class PddLoginInput(val index: Int, val placeholder: String, val type: String, val filled: Boolean)

@Serializable
internal data class PddLoginCheckbox(val index: Int, val checked: Boolean, val label: String)

@Serializable
internal data class PddLoginPoint(val x: Float, val y: Float, val w: Float, val h: Float, val tag: String = "", val cls: String = "")

@Serializable
internal data class PddLoginProbe(
    val url: String,
    val text: String,
    val buttons: List<String>,
    val inputs: List<PddLoginInput>,
    val checkboxes: List<PddLoginCheckbox> = emptyList(),
    val agreeHtml: String = "",
) {
    val challengeText: String? get() = listOf("安全验证", "请完成验证", "滑动验证", "请拖动滑块", "访问过于频繁", "人机验证")
        .firstOrNull { text.contains(it) }
    val sendCode: String? get() = buttons.firstOrNull { listOf("发送验证码", "获取验证码", "重新发送", "重新获取").any { label -> it == label } }
    val loginLabel: String? get() = buttons.firstOrNull { listOf("登录", "同意协议并登录", "直接登录", "继续登录").any { label -> it == label } }
    val agreementPending: Boolean get() = text.contains("请先勾选同意服务协议与隐私政策") || text.contains("请阅读并同意")
    val codeSent: Boolean get() = text.contains("验证码已发送") || text.contains("已发送") ||
        text.contains("重新发送") || text.contains("秒后重发")
    val phoneInput: PddLoginInput? get() = inputs.firstOrNull { it.placeholder.contains("手机号") || it.placeholder.contains("手机号码") }
        ?: inputs.firstOrNull { it.type == "tel" && !it.placeholder.contains("验证码") }
    val codeInput: PddLoginInput? get() = inputs.firstOrNull { it.placeholder.contains("验证码") }
    val qrOnly: Boolean get() = (text.contains("扫码登录") || text.contains("打开拼多多APP") || text.contains("请使用App扫码登录")) &&
        phoneInput == null && codeInput == null && sendCode == null
}

@Serializable
internal data class PddLoginStep(val at: Long, val url: String, val action: String, val detail: String)

internal sealed interface PddLoginResult {
    data object Succeeded : PddLoginResult
    data class Blocked(val message: String, val kind: FailureKind) : PddLoginResult
}

internal class PddLoginFlow(
    private val browser: PlatformWebView,
    private val context: Context,
    private val account: Account,
    private val sessionReady: () -> Boolean,
    private val onStatus: (String) -> Unit,
) {
    private val steps = mutableListOf<PddLoginStep>()
    private var candidates: List<String> = emptyList()
    private var candidateIndex = 0
    private var phoneFilled = false
    private var codeRequestedAt = 0L
    private var codeFilled = false
    private var loginClicked = false
    private var attempts = 0
    private var entryClicked = false
    private var agreementClicked = false

    fun evidence(): List<PddLoginStep> = steps.toList()

    suspend fun run(deadlineMillis: Long): PddLoginResult {
        val deadline = SystemClock.elapsedRealtime() + deadlineMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (sessionReady()) return PddLoginResult.Succeeded
            val probe = probe()
            if (probe == null) {
                delay(1_500)
                continue
            }
            step(probe.url, "probe", probe.url + " :: " + probe.buttons.take(16).joinToString("|") +
                " inputs=" + probe.inputs.joinToString(",") { it.index.toString() + ":" + it.placeholder + ":" + it.type } +
                " text=" + probe.text.take(160).replace("\n", " "))
            probe.challengeText?.let { text ->
                return PddLoginResult.Blocked("拼多多登录页要求完成安全验证（$text）", FailureKind.NEEDS_VERIFICATION)
            }
            if (probe.agreementPending) {
                val result = withTimeoutOrNull(4_000) { browser.evaluateAwait(LoginPageScripts.agreeCircle()) } ?: "timeout"
                step(probe.url, "agree-circle", result)
                agreementClicked = true
                loginClicked = false
                delay(800)
                continue
            }
            if (probe.checkboxes.any { !it.checked }) {
                val result = browser.evaluateAwait(LoginPageScripts.agree)
                step(probe.url, "agree", result)
                delay(600)
                continue
            }
            if (!entryClicked) {
                val entry = probe.buttons.firstOrNull { it.contains("手机登录") }
                if (entry != null) {
                    click(entry, probe.url)
                    entryClicked = true
                    step(probe.url, "entry", entry)
                    delay(1_500)
                    continue
                }
            }
            if (probe.phoneInput == null && probe.codeInput == null) {
                if (probe.qrOnly) {
                    return PddLoginResult.Blocked("拼多多登录页只提供扫码或打开 App 的入口", FailureKind.NEEDS_LOGIN)
                }
            }
            if (!agreementClicked && probe.phoneInput != null) {
                val result = withTimeoutOrNull(4_000) { browser.evaluateAwait(LoginPageScripts.agreeCircle()) } ?: "timeout"
                step(probe.url, "agree-circle", result)
                agreementClicked = true
                if (!result.contains("missing")) {
                    delay(600)
                    continue
                }
            }
            if (codeRequestedAt == 0L && probe.phoneInput != null) {
                val outcome = phoneStep(probe)
                if (outcome != null) return outcome
                delay(1_000)
                continue
            }
            if (probe.codeInput != null || probe.codeSent || codeRequestedAt != 0L) {
                val outcome = codeStep(probe)
                if (outcome != null) return outcome
                delay(1_000)
                continue
            }
            if (probe.phoneInput != null || probe.sendCode != null) {
                val outcome = phoneStep(probe)
                if (outcome != null) return outcome
                delay(1_000)
                continue
            }
            val confirm = probe.loginLabel
            if (confirm != null) {
                if (codeRequestedAt == 0L) {
                    SmsCodeInbox.begin()
                    click(confirm, probe.url)
                    codeRequestedAt = SystemClock.elapsedRealtime()
                    onStatus("已提交拼多多手机号，等待短信验证码")
                } else if (!codeFilled && SystemClock.elapsedRealtime() - codeRequestedAt > 60_000) {
                    nextCandidate(probe)?.let { return it }
                }
                delay(1_000)
                continue
            }
            delay(1_500)
        }
        return PddLoginResult.Blocked("拼多多登录页在期限内没有完成登录", FailureKind.NEEDS_LOGIN)
    }

    private suspend fun phoneStep(probe: PddLoginProbe): PddLoginResult? {
        if (candidates.isEmpty()) {
            candidates = preferredNumbers()
            if (candidates.isEmpty()) {
                return PddLoginResult.Blocked("拼多多登录页要求手机号，设备没有提供本机号码", FailureKind.NEEDS_LOGIN)
            }
            step(probe.url, "numbers", candidates.joinToString(",") { PhoneNumbers.mask(it) })
        }
        if (probe.text.contains("手机号码格式不正确") || probe.text.contains("请输入正确的手机号码")) {
            step(probe.url, "invalid-number", "拼多多不接受当前号码")
            return nextCandidate(probe)
        }
        if (!phoneFilled) {
            val input = probe.phoneInput
                ?: return PddLoginResult.Blocked("拼多多登录页要求手机号，但没有可填写的手机号输入框", FailureKind.UNSUPPORTED_RESPONSE)
            fill(input.index, candidates[candidateIndex], probe.url)
            phoneFilled = true
            onStatus("已填入本机号码，等待请求验证码")
            return null
        }
        val send = probe.sendCode ?: probe.loginLabel ?: return null
        if (codeRequestedAt == 0L) {
            SmsCodeInbox.begin()
            click(send, probe.url)
            codeRequestedAt = SystemClock.elapsedRealtime()
            onStatus("已请求拼多多短信验证码，等待通知")
            return null
        }
        if (SystemClock.elapsedRealtime() - codeRequestedAt > 60_000 && !probe.codeSent) {
            attempts++
            step(probe.url, "code-timeout", "60 秒没有收到验证码，第 " + attempts + " 次")
            if (attempts >= 2) return nextCandidate(probe)
            codeRequestedAt = 0L
        }
        return null
    }

    private suspend fun codeStep(probe: PddLoginProbe): PddLoginResult? {
        if (codeRequestedAt == 0L) {
            val send = probe.sendCode ?: probe.loginLabel ?: return null
            SmsCodeInbox.begin()
            click(send, probe.url)
            codeRequestedAt = SystemClock.elapsedRealtime()
            onStatus("已请求拼多多短信验证码，等待通知")
            return null
        }
        if (probe.text.contains("验证码错误") || probe.text.contains("验证码位数不正确") || probe.text.contains("验证码不正确")) {
            codeFilled = false
            loginClicked = false
            channelResend(probe)
            return null
        }
        if (!codeFilled && SystemClock.elapsedRealtime() - codeRequestedAt > 60_000) {
            attempts++
            step(probe.url, "code-timeout", "60 秒没有收到验证码，第 " + attempts + " 次")
            if (attempts >= 2) return nextCandidate(probe)
            codeRequestedAt = 0L
            SmsCodeInbox.begin()
            return null
        }
        if (!codeFilled) {
            val incoming = withTimeoutOrNull(1_000) { SmsCodeInbox.code.first { it != null } } ?: return null
            val input = probe.codeInput ?: return null
            fill(input.index, incoming.code, probe.url)
            codeFilled = true
            onStatus("已填入拼多多短信验证码")
            return null
        }
        if (!loginClicked && !agreementClicked) {
            val result = withTimeoutOrNull(4_000) { browser.evaluateAwait(LoginPageScripts.agreeText("同意")) } ?: "timeout"
            step(probe.url, "agree-text", result)
            agreementClicked = true
            if (!result.contains("missing")) {
                delay(800)
                return null
            }
        }
        val label = probe.loginLabel
        if (!loginClicked && label != null) {
            click(label, probe.url)
            loginClicked = true
            onStatus("已提交拼多多登录")
        }
        return null
    }

    private suspend fun channelResend(probe: PddLoginProbe) {
        val send = probe.sendCode ?: probe.loginLabel ?: return
        SmsCodeInbox.begin()
        click(send, probe.url)
        codeRequestedAt = SystemClock.elapsedRealtime()
        onStatus("验证码没有通过，已重新请求")
    }

    private suspend fun nextCandidate(probe: PddLoginProbe): PddLoginResult? {
        if (candidateIndex + 1 >= candidates.size) {
            return PddLoginResult.Blocked("设备上的本机号码都没有完成拼多多登录", FailureKind.NEEDS_LOGIN)
        }
        candidateIndex++
        phoneFilled = false
        codeRequestedAt = 0L
        codeFilled = false
        loginClicked = false
        attempts = 0
        onStatus("当前号码没有完成登录，尝试下一个号码")
        return null
    }

    private fun preferredNumbers(): List<String> {
        val owned = PhoneNumbers.ownNumbers(context)
        val fromLabel = Regex("1[3-9][0-9]{9}").find(account.label)?.value
        return if (fromLabel == null) owned else listOf(fromLabel) + owned.filter { it != fromLabel }
    }

    private suspend fun probe(): PddLoginProbe? {
        val raw = withTimeoutOrNull(4_000) { browser.evaluateAwait(LoginPageScripts.probe) } ?: return null
        val inner = (platformJson.parseToJsonElement(raw) as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (inner.isBlank()) return null
        return platformJson.decodeFromString(inner)
    }

    private suspend fun click(label: String, url: String) {
        val raw = withTimeoutOrNull(4_000) { browser.evaluateAwait(LoginPageScripts.locate(label)) }
        if (raw == null) {
            step(url, "tap:" + label, "timeout")
            return
        }
        val inner = (platformJson.parseToJsonElement(raw) as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (inner == "missing" || inner.isBlank()) {
            step(url, "tap:" + label, "missing")
            return
        }
        val point = platformJson.decodeFromString<PddLoginPoint>(inner)
        browser.tapPage(point.x, point.y, point.w, point.h)
        step(url, "tap:" + label, point.tag + "|" + point.cls)
    }

    private suspend fun fill(index: Int, value: String, url: String) {
        val result = withTimeoutOrNull(4_000) { browser.evaluateAwait(LoginPageScripts.fill(index, value)) }
            ?: throw AppFailure("拼多多登录页没有响应输入操作", FailureKind.UNSUPPORTED_RESPONSE)
        step(url, "fill:" + index, if (result.contains("filled")) "filled:" + value.length else result)
    }

    private fun step(url: String, action: String, detail: String) {
        steps.add(PddLoginStep(System.currentTimeMillis(), url, action, detail.take(4_000)))
        android.util.Log.i("PddLogin", "action=" + action + " detail=" + detail.take(500))
    }
}
