package com.logisticsmanagement.app.platform

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.logisticsmanagement.app.LogisticsApplication
import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import kotlinx.coroutines.launch

class XiaomiAccountActivity : ComponentActivity() {
    private val registry get() = (application as LogisticsApplication).container.platforms
    private lateinit var account: Account
    private lateinit var adapter: XiaomiAdapter
    private lateinit var status: TextView
    private lateinit var phones: TextView
    private lateinit var phone: EditText
    private lateinit var code: EditText
    private lateinit var oaid: EditText
    private lateinit var vaid: EditText
    private val actions = mutableListOf<Button>()
    private val sessionActions = mutableListOf<Button>()
    private var configurationLoaded = false
    private var sessionReady = false
    private var working = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { text = "小米物流账号配置"; textSize = 16f; accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        phones = TextView(this).apply { textSize = 16f }
        content.addView(status)
        content.addView(phones)
        fun input(label: String, type: Int): EditText {
            val title = TextView(this).apply { text = label; textSize = 14f }
            val field = EditText(this).apply { hint = label; textSize = 16f; inputType = type; setSingleLine(); contentDescription = label; minHeight = (48 * resources.displayMetrics.density).toInt() }
            content.addView(title); content.addView(field)
            return field
        }
        phone = input("用于物流查询的手机号", InputType.TYPE_CLASS_PHONE)
        code = input("短信验证码", InputType.TYPE_CLASS_NUMBER)
        oaid = input("OAID（可选，填写设备提供的真实值）", InputType.TYPE_CLASS_TEXT)
        vaid = input("VAID（可选，填写设备提供的真实值）", InputType.TYPE_CLASS_TEXT)
        fun button(label: String, requiresSession: Boolean = false, action: suspend () -> Unit) {
            val button = Button(this).apply { text = label; minHeight = (48 * resources.displayMetrics.density).toInt(); isEnabled = false; setOnClickListener { runAction(action) } }
            if (requiresSession) sessionActions.add(button)
            actions.add(button); content.addView(button)
        }
        button("登录小米账号") { registry.openLogin(this@XiaomiAccountActivity, account) }
        button("保存设备标识") {
            val current = adapter.configuration(account)
            adapter.saveConfiguration(account, current.copy(oaid = oaid.text.toString().trim(), vaid = vaid.text.toString().trim()))
            status.text = "设备标识配置已经保存"
        }
        button("读取设备标识") {
            val current = adapter.readDeviceIdentifiers(account)
            oaid.setText(current.oaid)
            vaid.setText(current.vaid)
            status.text = "设备提供的 OAID 和 VAID 已经加密保存"
        }
        button("发送短信验证码", requiresSession = true) {
            saveIdentifiers()
            adapter.sendPhoneCode(account, phone.text.toString().trim())
            status.text = "验证码发送请求成功，请查看短信"
        }
        button("验证并绑定手机号", requiresSession = true) {
            saveIdentifiers()
            adapter.bindPhone(account, phone.text.toString().trim(), code.text.toString().trim())
            refreshPhones()
            status.text = "手机号已经绑定"
        }
        button("返回") { finish() }
        val root = ScrollView(this).apply { addView(content) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val padding = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        lifecycleScope.launch {
            try {
                val id = intent.getStringExtra("accountId") ?: throw AppFailure("没有提供小米账号", FailureKind.CONFIGURATION)
                account = registry.repository.getAccount(id)?.takeIf { !it.deleted }
                    ?: throw AppFailure("小米账号已移除", FailureKind.CONFIGURATION)
                adapter = registry.adapter(account.platform) as XiaomiAdapter
                val configuration = adapter.configuration(account)
                oaid.setText(configuration.oaid); vaid.setText(configuration.vaid)
                refreshPhones()
                configurationLoaded = true
                refreshLoginState()
                updateActions()
            } catch (failure: AppFailure) { status.text = failure.message }
        }
    }

    private fun runAction(action: suspend () -> Unit) = lifecycleScope.launch {
        working = true
        updateActions()
        try { action() }
        catch (failure: AppFailure) {
            status.text = failure.message
            if (failure.kind == FailureKind.NEEDS_LOGIN) sessionReady = false
        }
        finally { working = false; updateActions() }
    }

    private fun updateActions() {
        actions.forEach { it.isEnabled = configurationLoaded && !working && (it !in sessionActions || sessionReady) }
    }

    private suspend fun refreshLoginState() {
        sessionReady = try { adapter.session(account); true }
        catch (failure: AppFailure) {
            if (failure.kind != FailureKind.NEEDS_LOGIN) throw failure
            status.text = "请登录小米账号，取得智能助理会话后绑定物流手机号"
            false
        }
    }

    private suspend fun saveIdentifiers() {
        val current = adapter.configuration(account)
        adapter.saveConfiguration(account, current.copy(oaid = oaid.text.toString().trim(), vaid = vaid.text.toString().trim()))
    }

    private suspend fun refreshPhones() {
        val configuration = adapter.configuration(account)
        phones.text = if (configuration.phones.isEmpty()) "尚未绑定物流手机号" else "已绑定手机号：" + configuration.phones.joinToString("、") { "${it.take(3)}****${it.takeLast(4)}" }
    }

    override fun onResume() {
        super.onResume()
        if (configurationLoaded) lifecycleScope.launch {
            try { refreshPhones(); refreshLoginState(); updateActions() }
            catch (failure: AppFailure) { status.text = failure.message; sessionReady = false; updateActions() }
        }
    }
}
