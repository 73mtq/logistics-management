package com.logisticsmanagement.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.logisticsmanagement.app.AppContainer
import com.logisticsmanagement.app.data.AppSettings
import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.AccountState
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.Platform
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(container: AppContainer, settings: AppSettings, accounts: List<Account>, busy: Map<String, Boolean>, runAction: RunAction, modifier: Modifier) {
    val context = LocalContext.current
    val progress by remember(container) { container.syncCoordinator.progress }.collectAsStateWithLifecycle()
    var addingPlatform by remember { mutableStateOf<Platform?>(null) }
    var accountLabel by rememberSaveable { mutableStateOf("") }
    var removingAccount by remember { mutableStateOf<Account?>(null) }
    var baseUrl by rememberSaveable(settings.aiBaseUrl) { mutableStateOf(settings.aiBaseUrl) }
    var model by rememberSaveable(settings.aiModel) { mutableStateOf(settings.aiModel) }
    var apiKey by remember { mutableStateOf(container.credentials.getSecret("ai.apiKey").orEmpty()) }
    var customer by remember { mutableStateOf(container.credentials.getSecret("kuaidi100.customer").orEmpty()) }
    var trackingKey by remember { mutableStateOf(container.credentials.getSecret("kuaidi100.key").orEmpty()) }
    var reportHour by rememberSaveable(settings.dailyReportHour) { mutableStateOf(settings.dailyReportHour.toString()) }
    var reportMinute by rememberSaveable(settings.dailyReportMinute) { mutableStateOf(settings.dailyReportMinute.toString()) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }
    var saveStatus by remember { mutableStateOf<String?>(null) }
    var notificationsEnabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).testTag("settingsList"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SectionTitle("平台账号") }
            if (accounts.isEmpty()) item { Text("添加平台账号后可以获取其订单和物流记录。", style = MaterialTheme.typography.bodyLarge) }
            items(accounts, key = { it.id }) { account ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${account.platform.displayName} · ${account.label}", style = MaterialTheme.typography.titleMedium)
                        Text(if (account.enabled) accountStateLabel(account.state) else "账号更新已停用", style = MaterialTheme.typography.bodyMedium)
                        if (account.state == AccountState.SYNCING) {
                            progress[account.id]?.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text("最近成功更新 ${formatTimestamp(account.lastSyncedAt)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        account.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                        SettingsSwitch("启用账号更新", account.enabled, busy["enable_${account.id}"] != true && account.state != AccountState.SYNCING) { checked ->
                            runAction("enable_${account.id}") {
                                val current = container.repository.getAccount(account.id) ?: throw AppFailure("账号已经移除", FailureKind.CONFIGURATION)
                                container.repository.saveAccount(current.copy(enabled = checked))
                                container.syncCoordinator.updateSchedules()
                            }
                        }
                        ActionButton(if (account.platform == Platform.XIAOMI) "配置小米账号" else if (account.state == AccountState.NEEDS_VERIFICATION) "完成验证" else "登录账号", false) { openAccountLogin(container, context, account) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { runAction("account_${account.id}") { container.syncCoordinator.syncAccount(account.id) } }, enabled = account.enabled && busy["account_${account.id}"] != true && account.state != AccountState.SYNCING,
                                modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text(if (busy["account_${account.id}"] == true || account.state == AccountState.SYNCING) "正在更新" else "更新账号") }
                            TextButton(onClick = { removingAccount = account }, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("移除账号", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            item { SectionTitle("添加账号") }
            items(Platform.entries.filter { it != Platform.MANUAL }) { platform ->
                TextButton(onClick = { addingPlatform = platform; accountLabel = "" }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) { Text("添加${platform.displayName}账号") }
            }
            item { SectionTitle("后台更新与通知") }
            item {
                SettingsSwitch("每30分钟更新在途包裹", settings.backgroundEnabled, busy["background"] != true) { checked ->
                    runAction("background") { container.settings.save(settings.copy(backgroundEnabled = checked)); container.syncCoordinator.updateSchedules() }
                }
                Text("Android 会根据电量、网络和系统限制安排执行时间。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                InfoBanner(if (notificationsEnabled) "物流通知已经开启。" else "通知权限未开启，物流变化会显示在应用内。") {
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }
                    }, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("管理通知权限") }
                }
            }
            item { SectionTitle("手动包裹物流查询") }
            item { FormField("快递100 customer", customer, { customer = it; saveStatus = null }) }
            item { SecretField("快递100 key", trackingKey, { trackingKey = it; saveStatus = null }) }
            item {
                ActionButton("保存物流服务", busy["saveTracking"] == true) {
                    runAction("saveTracking") {
                        if (customer.isBlank() || trackingKey.isBlank()) throw AppFailure("请填写快递100 customer 和 key", FailureKind.CONFIGURATION)
                        container.credentials.putSecret("kuaidi100.customer", customer.trim())
                        container.credentials.putSecret("kuaidi100.key", trackingKey.trim())
                        container.syncCoordinator.updateSchedules()
                        saveStatus = "物流服务配置已经保存。"
                    }
                }
            }
            item { SectionTitle("AI 服务") }
            item { InfoBanner("AI 会发送经过隐私处理的商品名称、快递公司、来源平台、状态与时间、是否有取件信息，以及你输入的问题。请勿在问题内填写登录凭据、完整手机号或详细地址。") }
            item {
                SettingsSwitch("启用 AI 问答", settings.aiEnabled, busy["aiEnabled"] != true) { checked ->
                    runAction("aiEnabled") {
                        if (checked && (settings.aiBaseUrl.isBlank() || settings.aiModel.isBlank() || container.credentials.getSecret("ai.apiKey").isNullOrBlank())) throw AppFailure("请填写并保存 AI 服务地址、API key 和 model", FailureKind.CONFIGURATION)
                        container.settings.save(settings.copy(aiEnabled = checked)); container.syncCoordinator.updateSchedules()
                    }
                }
            }
            item { FormField("API 服务地址（baseURL）", baseUrl, { baseUrl = it; connectionStatus = null; saveStatus = null }) }
            if (baseUrl.trim().toHttpUrlOrNull()?.scheme == "http") {
                item { InfoBanner("HTTP 连接会通过未加密连接发送 API key 和问答内容，请确认这是你信任的服务地址。", isError = true) }
            }
            item { SecretField("API key", apiKey, { apiKey = it; connectionStatus = null; saveStatus = null }) }
            item { FormField("model", model, { model = it; connectionStatus = null; saveStatus = null }) }
            item {
                ActionButton("保存 AI 配置", busy["saveAi"] == true) {
                    runAction("saveAi") {
                        if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) throw AppFailure("请填写 AI 服务地址、API key 和 model", FailureKind.CONFIGURATION)
                        container.credentials.putSecret("ai.apiKey", apiKey.trim())
                        container.settings.save(settings.copy(aiBaseUrl = baseUrl.trim(), aiModel = model.trim()))
                        container.syncCoordinator.updateSchedules()
                        saveStatus = "AI 服务配置已经保存。"
                    }
                }
            }
            item {
                ActionButton("测试已保存的连接", busy["testAi"] == true) {
                    connectionStatus = null
                    runAction("testAi") { container.aiService.testConnection(); connectionStatus = "API 连接测试成功。" }
                }
            }
            connectionStatus?.let { status -> item { InfoBanner(status) } }
            item { SectionTitle("物流日报") }
            item {
                SettingsSwitch("每天生成物流日报", settings.dailyReportEnabled, settings.aiEnabled && busy["dailyReport"] != true) { checked ->
                    runAction("dailyReport") { container.settings.save(settings.copy(dailyReportEnabled = checked)); container.syncCoordinator.updateSchedules() }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormField("小时（0–23）", reportHour, { reportHour = it }, modifier = Modifier.weight(1f))
                    FormField("分钟（0–59）", reportMinute, { reportMinute = it }, modifier = Modifier.weight(1f))
                }
            }
            item {
                ActionButton("保存日报时间", busy["reportTime"] == true, settings.aiEnabled) {
                    runAction("reportTime") {
                        val hour = reportHour.toIntOrNull()
                        val minute = reportMinute.toIntOrNull()
                        if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) throw AppFailure("日报时间需要填写0–23之间的小时和0–59之间的分钟", FailureKind.CONFIGURATION)
                        container.settings.save(settings.copy(dailyReportHour = hour, dailyReportMinute = minute)); container.syncCoordinator.updateSchedules()
                        saveStatus = "日报生成时间已经保存。"
                    }
                }
            }
            saveStatus?.let { status -> item { InfoBanner(status) } }
        }
    }
    addingPlatform?.let { platform ->
        AlertDialog(onDismissRequest = { if (busy["createAccount"] != true) addingPlatform = null }, title = { Text("添加${platform.displayName}账号") },
            text = { FormField("账号名称", accountLabel, { accountLabel = it }) }, confirmButton = {
                TextButton(onClick = { runAction("createAccount") { val account = container.repository.createAccount(platform, accountLabel.trim()); container.syncCoordinator.updateSchedules(); addingPlatform = null; openAccountLogin(container, context, account) } }, enabled = busy["createAccount"] != true, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text(if (busy["createAccount"] == true) "正在创建账号" else "创建并登录") }
            }, dismissButton = { TextButton(onClick = { addingPlatform = null }, enabled = busy["createAccount"] != true) { Text("取消添加") } })
    }
    removingAccount?.let { account ->
        AlertDialog(onDismissRequest = { removingAccount = null }, title = { Text("移除账号「${account.label}」") }, text = { Text("移除后停止更新该账号，已经保存的包裹和物流记录保留。") }, confirmButton = {
            TextButton(onClick = { runAction("remove_${account.id}") { container.syncCoordinator.removeAccount(account.id); removingAccount = null } }, enabled = busy["remove_${account.id}"] != true, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("移除账号", color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = { removingAccount = null }) { Text("保留账号") } })
    }
}

@Composable
private fun SettingsSwitch(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(top = 12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp))
    }
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), textStyle = MaterialTheme.typography.bodyLarge)
}
