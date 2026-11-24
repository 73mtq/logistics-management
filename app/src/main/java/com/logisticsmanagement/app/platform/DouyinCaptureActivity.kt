package com.logisticsmanagement.app.platform

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.logisticsmanagement.app.LogisticsApplication
import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.CaptureMode
import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.Platform
import kotlinx.coroutines.launch
import java.util.UUID

class DouyinCaptureActivity : ComponentActivity() {
    private val container get() = (application as LogisticsApplication).container
    private var account: Account? = null
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        content.addView(TextView(this).apply { text = "抖音商城订单采集"; textSize = 20f })
        content.addView(TextView(this).apply {
            text = "开启无障碍权限后读取抖音的商城订单和物流。采集期间单击选择、双击打开；结束采集后恢复正常触摸。首次采集需要核对抖音账号。"
            textSize = 16f
        })
        status = TextView(this).apply { textSize = 16f; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val connection = TextView(this).apply { textSize = 14f }
        intent.getStringExtra("remoteOrderId")?.let { content.addView(TextView(this).apply { text = "目标订单编号：$it"; textSize = 16f }) }
        content.addView(status)
        content.addView(connection)
        fun action(label: String, block: () -> Unit) {
            content.addView(Button(this).apply { text = label; minHeight = (48 * resources.displayMetrics.density).toInt(); setOnClickListener { block() } })
        }
        action("打开无障碍设置") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        fun start(resume: Boolean) { lifecycleScope.launch {
            try {
                val selected = account?.id?.let { container.repository.getAccount(it) } ?: throw AppFailure("账号尚未读取", FailureKind.CONFIGURATION)
                val intent = packageManager.getLaunchIntentForPackage(DouyinCaptureController.PACKAGE)
                    ?: throw AppFailure("请安装抖音官方 App", FailureKind.CONFIGURATION)
                require(selected.captureMode == CaptureMode.APP_PAGE)
                DouyinCaptureController.startPaginationWithEvidence(selected, UUID.randomUUID().toString(), resume)
                startActivity(intent)
            } catch (failure: AppFailure) { status.text = failure.message }
        } }
        action("开始新一轮") { start(false) }
        action("继续采集") { start(true) }
        action("结束采集") { DouyinCaptureController.stop() }
        action("正常触摸导航") { if (DouyinCaptureController.activeAccount() != null) DouyinCaptureController.normalNavigation() }
        action("恢复采集") { if (DouyinCaptureController.activeAccount() != null) DouyinCaptureController.resumeCapture() }
        action("返回") { finish() }
        val root = ScrollView(this).apply { addView(content) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val padding = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        lifecycleScope.launch {
            while (true) {
                connection.text = DouyinCaptureController.statusText(this@DouyinCaptureActivity)
                kotlinx.coroutines.delay(1_000)
            }
        }
        lifecycleScope.launch {
            container.ready.await()
            val id = intent.getStringExtra("accountId") ?: throw AppFailure("没有提供抖音账号", FailureKind.CONFIGURATION)
            account = container.repository.getAccount(id)?.takeUnless { it.deleted }
                ?: throw AppFailure("抖音账号已移除", FailureKind.CONFIGURATION)
            require(account?.platform == Platform.DOUYIN)
            try {
                container.syncCoordinator.nativeProgressStore.read(checkNotNull(account))?.let { status.text = it.message() }
            } catch (failure: AppFailure) { status.text = failure.message }
            DouyinCaptureController.status.collect { value ->
                try {
                    val saved = if (DouyinCaptureController.activeAccount() == null) container.syncCoordinator.nativeProgressStore.read(checkNotNull(account)) else null
                    status.text = saved?.message() ?: value
                } catch (failure: AppFailure) { status.text = failure.message }
            }
        }
    }
}
