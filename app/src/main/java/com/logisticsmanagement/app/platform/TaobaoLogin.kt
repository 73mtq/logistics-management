package com.logisticsmanagement.app.platform

import android.content.Context
import android.os.SystemClock
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
internal data class TaobaoLoginInput(val index: Int, val placeholder: String, val type: String, val filled: Boolean)

@Serializable
internal data class TaobaoLoginCheckbox(val index: Int, val checked: Boolean, val label: String)

@Serializable
internal data class TaobaoLoginPoint(val x: Float, val y: Float, val w: Float, val h: Float, val tag: String = "", val cls: String = "")

@Serializable
internal data class TaobaoLoginProbe(val url: String, val text: String, val buttons: List<String>, val inputs: List<TaobaoLoginInput>,
                                      val checkboxes: List<TaobaoLoginCheckbox> = emptyList(), val agreeHtml: String = "") {
    val codeSent: Boolean get() = text.contains("验证码已发送") || text.contains("已向") ||
        text.contains("秒后重发") || text.contains("重新获取") || text.contains("重新发送")
    val agreementPending: Boolean get() = text.contains("请阅读并同意") || text.contains("请先阅读") || text.contains("请勾选")
    fun challengeText(): String? = listOf("安全验证", "请完成验证", "滑动验证", "请拖动滑块", "访问过于频繁", "人机验证")
        .firstOrNull { text.contains(it) }
    val quickEnter: String? get() = buttons.firstOrNull { it == "快速进入" || it == "一键登录" }
    val simEnter: String? get() = buttons.firstOrNull { listOf("本机号码一键登录", "本机号码登录", "本机号码免密登录").any { label -> it == label } }
    val sendCode: String? get() = buttons.firstOrNull { listOf("获取验证码", "发送验证码", "获取短信验证码", "重新发送").any { label -> it == label } }
    val loginLabel: String? get() = buttons.firstOrNull { it == "登录" || it == "立即登录" || it == "同意协议并登录" }
    private val codeMode: Boolean get() = text.contains("已向")
    val phoneInput: TaobaoLoginInput? get() = if (codeMode) null else inputs.firstOrNull { input ->
        input.placeholder.contains("手机") || (!input.placeholder.contains("验证码") && input.type == "tel")
    }
    val codeInput: TaobaoLoginInput? get() = inputs.firstOrNull { it.placeholder.contains("验证码") }
        ?: if (codeMode && inputs.size == 1) inputs[0] else null
    val imageCodeInput: TaobaoLoginInput? get() = inputs.firstOrNull { it.placeholder.contains("图片") }
    fun onlyManual(): Boolean = text.contains("扫码登录") ||
        (text.contains("密码登录") && phoneInput == null && sendCode == null && codeInput == null && loginLabel == null)
}

@Serializable
internal data class TaobaoLoginStep(val at: Long, val url: String, val action: String, val detail: String)

internal sealed interface TaobaoLoginResult {
    data object Succeeded : TaobaoLoginResult
    data class Blocked(val message: String, val kind: FailureKind) : TaobaoLoginResult
}

internal object TaobaoLoginScripts {
    private const val VISIBLE = "(function(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none';})"

    val probe = """
        (function() {
          var visible = $VISIBLE;
          var inputs = Array.prototype.map.call(document.querySelectorAll('input'), function(e, index) {
            return {index: index, placeholder: e.placeholder || '', type: e.type || 'text', filled: !!e.value, visible: visible(e)};
          }).filter(function(e) { return e.visible; });
          var buttons = Array.prototype.map.call(document.querySelectorAll('a,button,div,span'), function(e) {
            return visible(e) ? (e.innerText || '').trim() : '';
          }).filter(function(t) { return t.length > 0 && t.length <= 12; });
          var checkboxes = Array.prototype.map.call(document.querySelectorAll('input[type=checkbox]'), function(e, index) {
            var holder = e.closest('label') || e.parentElement;
            return {index: index, checked: !!e.checked, label: holder ? (holder.innerText || '').trim().slice(0, 60) : '', visible: visible(e)};
          });
          var agree = '';
          var nodes = Array.prototype.filter.call(document.querySelectorAll('div,span,label'), function(e) {
            return visible(e) && (e.innerText || '').indexOf('已阅读并同意') >= 0;
          });
          if (nodes.length) {
            nodes.sort(function(a, b) { return (a.innerText || '').length - (b.innerText || '').length; });
            if (nodes[0].parentElement) agree = nodes[0].parentElement.outerHTML.slice(0, 800);
          }
          return JSON.stringify({url: location.href, text: document.body ? document.body.innerText : '', buttons: buttons.filter(function(t, i) { return buttons.indexOf(t) === i; }), inputs: inputs, checkboxes: checkboxes, agreeHtml: agree});
        })();
    """

    fun click(label: String): String {
        val safe = label.js()
        return """
        (function() {
          var visible = $VISIBLE;
          var matches = Array.prototype.filter.call(document.querySelectorAll('a,button,div,span'), function(e) {
            return visible(e) && (e.innerText || '').trim() === '$safe';
          });
          if (!matches.length) return 'missing';
          matches.sort(function(a, b) { var x = a.getBoundingClientRect(); var y = b.getBoundingClientRect(); return x.width * x.height - y.width * y.height; });
          var target = matches[0];
          var rect = target.getBoundingClientRect();
          var cx = rect.left + rect.width / 2;
          var cy = rect.top + rect.height / 2;
          ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function(type) {
            var event = type.indexOf('pointer') === 0
              ? new PointerEvent(type, {bubbles: true, cancelable: true, view: window, clientX: cx, clientY: cy, button: 0, pointerType: 'touch'})
              : new MouseEvent(type, {bubbles: true, cancelable: true, view: window, clientX: cx, clientY: cy, button: 0});
            target.dispatchEvent(event);
          });
          return target.tagName + '|' + String(target.className || '').slice(0, 40) + '|' + (target.innerText || '').trim().slice(0, 16);
        })();
    """
    }

    fun locate(label: String): String {
        val safe = label.js()
        return """
        (function() {
          var visible = $VISIBLE;
          var matches = Array.prototype.filter.call(document.querySelectorAll('a,button,div,span'), function(e) {
            return visible(e) && (e.innerText || '').trim() === '$safe';
          });
          if (!matches.length) return 'missing';
          matches.sort(function(a, b) { var x = a.getBoundingClientRect(); var y = b.getBoundingClientRect(); return x.width * x.height - y.width * y.height; });
          var target = matches[0];
          var rect = target.getBoundingClientRect();
          return JSON.stringify({x: rect.left + rect.width / 2, y: rect.top + rect.height / 2, w: document.documentElement.clientWidth, h: document.documentElement.clientHeight, tag: target.tagName, cls: String(target.className || '').slice(0, 40)});
        })();
    """
    }

    fun fill(index: Int, value: String): String {
        val safe = value.js()
        return """
        (function() {
          var input = document.querySelectorAll('input')[$index];
          if (!input) return 'missing';
          var proto = input.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
          var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
          setter.call(input, '$safe');
          input.dispatchEvent(new Event('input', {bubbles: true}));
          input.dispatchEvent(new Event('change', {bubbles: true}));
          input.blur();
          return 'filled';
        })();
    """
    }

    val agree = """
        (function() {
          var box = document.getElementById('fm-agreement-checkbox');
          if (!box) {
            box = Array.prototype.filter.call(document.querySelectorAll('input[type=checkbox]'), function(e) { return !e.checked; })[0];
          }
          if (box) {
            if (!box.checked) box.click();
            return box.checked ? 'checked' : 'clicked-unchecked';
          }
          return 'missing';
        })();
    """

    private fun String.js(): String = replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
}

internal class TaobaoLoginFlow(
    private val browser: PlatformWebView,
    private val context: Context,
    private val account: com.logisticsmanagement.app.model.Account,
    private val sessionReady: () -> Boolean,
    private val onStatus: (String) -> Unit,
) {
    private fun preferredNumbers(): List<String> {
        val owned = PhoneNumbers.ownNumbers(context)
        val fromLabel = Regex("1[3-9][0-9]{9}").find(account.label)?.value
        return if (fromLabel != null && fromLabel in owned) listOf(fromLabel) + owned.filter { it != fromLabel } else owned
    }
    private val steps = mutableListOf<TaobaoLoginStep>()
    private var candidates: List<String> = emptyList()
    private var candidateIndex = 0
    private var phoneFilled = false
    private var quickEnterSeenAt = 0L
    private var codeRequestedAt = 0L
    private var resendCount = 0
    private var codeFilled = false
    private var loginClicked = false

    fun evidence(): List<TaobaoLoginStep> = steps.toList()

    suspend fun run(deadlineMillis: Long): TaobaoLoginResult {
        val deadline = SystemClock.elapsedRealtime() + deadlineMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (sessionReady()) return TaobaoLoginResult.Succeeded
            val probe = probe()
            if (probe == null) {
                delay(1_500)
                continue
            }
            step(probe.url, "probe", probe.url + " :: " + probe.buttons.take(16).joinToString("|") +
                " inputs=" + probe.inputs.joinToString(",") { it.index.toString() + ":" + it.placeholder + ":" + it.type } +
                " agree=" + probe.agreeHtml.replace("\n", " "))
            probe.challengeText()?.let { text ->
                return TaobaoLoginResult.Blocked("淘宝登录页要求完成安全验证（$text）", FailureKind.NEEDS_VERIFICATION)
            }
            if (probe.text.contains("服务协议及隐私保护") && probe.buttons.any { it == "同意" }) {
                click("同意", probe.url)
                delay(1_200)
                continue
            }
            if (probe.text.contains("请补全本机号码")) {
                val switch = probe.buttons.firstOrNull { it == "换个登录方式" }
                if (switch != null) {
                    click(switch, probe.url)
                    delay(1_500)
                    continue
                }
                return TaobaoLoginResult.Blocked("淘宝要求补全本机号码，运营商认证没有返回完整号码", FailureKind.NEEDS_LOGIN)
            }
            val quickEnter = probe.quickEnter
            if (quickEnter != null) {
                if (quickEnterSeenAt == 0L) {
                    quickEnterSeenAt = SystemClock.elapsedRealtime()
                    onStatus("检测到已有淘宝会话，20 秒后自动进入，可在这段时间切换账号")
                    delay(20_000)
                    continue
                }
                onStatus("正在通过“$quickEnter”恢复淘宝会话")
                click(quickEnter, probe.url)
                delay(2_500)
                continue
            }
            val simEnter = probe.simEnter
            if (simEnter != null) {
                onStatus("正在通过“$simEnter”恢复淘宝会话")
                click(simEnter, probe.url)
                delay(2_500)
                continue
            }
            if (probe.phoneInput != null || probe.sendCode != null || probe.codeInput != null || probe.loginLabel != null) {
                smsStep(probe)?.let { return it }
                delay(1_500)
                continue
            }
            if (probe.onlyManual()) {
                return TaobaoLoginResult.Blocked("淘宝登录页只提供扫码或密码登录", FailureKind.NEEDS_LOGIN)
            }
            delay(1_500)
        }
        return TaobaoLoginResult.Blocked("淘宝登录页在期限内没有完成登录", FailureKind.NEEDS_LOGIN)
    }

    private suspend fun smsStep(probe: TaobaoLoginProbe): TaobaoLoginResult? {
        if (candidates.isEmpty()) {
            candidates = preferredNumbers()
            if (candidates.isEmpty()) {
                return TaobaoLoginResult.Blocked("淘宝登录页要求输入手机号，设备没有提供本机号码", FailureKind.NEEDS_LOGIN)
            }
            step(probe.url, "numbers", candidates.joinToString(",") { PhoneNumbers.mask(it) })
        }
        val confirmLabel = probe.loginLabel
        if (probe.text.contains("手机号码格式不正确")) {
            step(probe.url, "invalid-number", "淘宝不接受当前号码")
            return nextCandidate(probe)
        }
        if (probe.codeInput != null || probe.text.contains("已向")) {
            if (probe.imageCodeInput != null) {
                return TaobaoLoginResult.Blocked("淘宝登录页要求图片验证码", FailureKind.NEEDS_VERIFICATION)
            }
            if (!codeFilled) {
                val incoming = withTimeoutOrNull(1_000) { SmsCodeInbox.code.first { it != null } } ?: return null
                val input = probe.codeInput ?: return null
                fill(input.index, incoming.code, probe.url)
                codeFilled = true
                onStatus("已填入短信验证码")
                return null
            }
            if (!loginClicked && confirmLabel != null) {
                click(confirmLabel, probe.url)
                loginClicked = true
                onStatus("已提交淘宝登录")
            }
            return null
        }
        if (probe.phoneInput != null || probe.sendCode != null) {
            if (probe.checkboxes.any { !it.checked }) {
                val result = browser.evaluateAwait(TaobaoLoginScripts.agree)
                step(probe.url, "agree", result)
                delay(600)
                return null
            }
            if (!phoneFilled) {
                val input = probe.phoneInput
                    ?: return TaobaoLoginResult.Blocked("淘宝登录页要求手机号，但没有可填写的手机号输入框", FailureKind.UNSUPPORTED_RESPONSE)
                fill(input.index, candidates[candidateIndex], probe.url)
                phoneFilled = true
                onStatus("已填入本机号码，等待请求验证码")
                return null
            }
            val send = probe.sendCode ?: return null
            if (codeRequestedAt != 0L && !probe.codeSent && SystemClock.elapsedRealtime() - codeRequestedAt > 12_000) {
                codeRequestedAt = 0L
                resendCount++
                step(probe.url, "resend", "验证码没有发出，第 " + resendCount + " 次")
                if (resendCount >= 3) return nextCandidate(probe)
                return null
            }
            if (codeRequestedAt == 0L) {
                SmsCodeInbox.begin()
                click(send, probe.url)
                codeRequestedAt = SystemClock.elapsedRealtime()
                onStatus("已请求短信验证码，等待通知")
            }
            return null
        }
        if (confirmLabel != null) {
            if (!phoneFilled) {
                val switch = probe.buttons.firstOrNull { it == "换号" }
                if (switch != null) {
                    click(switch, probe.url)
                    step(probe.url, "switch-number", "确认页号码与候选不一致，换号")
                    delay(1_000)
                    return null
                }
            }
            if (codeRequestedAt == 0L) {
                SmsCodeInbox.begin()
                click(confirmLabel, probe.url)
                codeRequestedAt = SystemClock.elapsedRealtime()
                onStatus("已提交手机号，等待短信验证码")
                return null
            }
            if (!codeFilled && SystemClock.elapsedRealtime() - codeRequestedAt > 60_000) {
                step(probe.url, "code-timeout", "60 秒没有收到验证码")
                return nextCandidate(probe)
            }
            withTimeoutOrNull(1_000) { SmsCodeInbox.code.first { it != null } }
            return null
        }
        return null
    }

    private suspend fun nextCandidate(probe: TaobaoLoginProbe): TaobaoLoginResult? {
        if (candidateIndex + 1 >= candidates.size) {
            return TaobaoLoginResult.Blocked("设备上的本机号码都没有送达验证码或被淘宝拒绝", FailureKind.NEEDS_LOGIN)
        }
        probe.buttons.firstOrNull { it == "换号" }?.let { click(it, probe.url) }
        candidateIndex++
        phoneFilled = false
        codeRequestedAt = 0L
        resendCount = 0
        codeFilled = false
        loginClicked = false
        return null
    }

    private suspend fun probe(): TaobaoLoginProbe? {
        val raw = withTimeoutOrNull(4_000) { browser.evaluateAwait(TaobaoLoginScripts.probe) } ?: return null
        val inner = (platformJson.parseToJsonElement(raw) as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (inner.isBlank()) return null
        return platformJson.decodeFromString(inner)
    }

    private suspend fun click(label: String, url: String) {
        val raw = withTimeoutOrNull(4_000) { browser.evaluateAwait(TaobaoLoginScripts.locate(label)) }
        if (raw == null) {
            step(url, "tap:$label", "timeout")
            return
        }
        val inner = (platformJson.parseToJsonElement(raw) as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (inner == "missing" || inner.isBlank()) {
            step(url, "tap:$label", "missing")
            return
        }
        val point = platformJson.decodeFromString<TaobaoLoginPoint>(inner)
        browser.tapPage(point.x, point.y, point.w, point.h)
        step(url, "tap:$label", point.tag + "|" + point.cls)
    }

    private suspend fun fill(index: Int, value: String, url: String) {
        val result = withTimeoutOrNull(4_000) { browser.evaluateAwait(TaobaoLoginScripts.fill(index, value)) }
            ?: throw AppFailure("淘宝登录页没有响应输入操作", FailureKind.UNSUPPORTED_RESPONSE)
        step(url, "fill:$index", if (result.contains("filled")) "filled:" + value.length else result)
    }

    private fun step(url: String, action: String, detail: String) {
        steps.add(TaobaoLoginStep(System.currentTimeMillis(), url, action, detail.take(4_000)))
        android.util.Log.i("TaobaoLogin", "action=$action detail=" + detail.take(500))
    }
}
