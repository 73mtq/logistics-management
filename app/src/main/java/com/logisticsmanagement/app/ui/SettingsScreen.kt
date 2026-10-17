package com.logisticsmanagement.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.logisticsmanagement.app.AppContainer
import com.logisticsmanagement.app.data.AppSettings
import com.logisticsmanagement.app.model.*
import com.logisticsmanagement.app.platform.NativeSemanticJudge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(container: AppContainer, settings: AppSettings, accounts: List<Account>, busy: Map<String, Boolean>, runAction: RunAction, modifier: Modifier) {
    val context = LocalContext.current
    val progress by remember(container) { container.syncCoordinator.progress }.collectAsStateWithLifecycle()
    var addingPlatform by remember { mutableStateOf<Platform?>(null) }
    var accountLabel by rememberSaveable { mutableStateOf("") }
    var removingAccount by remember { mutableStateOf<Account?>(null) }
    var jevKey by remember { mutableStateOf("") }
    var jevConfigured by remember { mutableStateOf(!container.credentials.getSecret(NativeSemanticJudge.CREDENTIAL_NAME).isNullOrBlank()) }
    var jevStatus by remember { mutableStateOf("") }
    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).testTag("settingsList"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SectionTitle("平台账号") }
            if (accounts.isEmpty()) item { Text("添加平台账号后可以获取订单和物流记录。") }
            items(accounts, key = { it.id }) { account ->
                Card(modifier = Modifier.fillMaxWidth().testTag("account_${account.id}")) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${account.platform.displayName} · ${account.label}", style = MaterialTheme.typography.titleMedium)
                        Text(if (account.enabled) accountStateLabel(account.state) else "账号更新已停用")
                        progress[account.id]?.let { current ->
                            Text(current.message, style = MaterialTheme.typography.bodyMedium)
                            current.trackingFailures.forEach { failure -> Text(failure.message, color = MaterialTheme.colorScheme.error) }
                        }
                        Text("最近成功更新 ${formatTimestamp(account.lastSyncedAt)}", style = MaterialTheme.typography.bodyMedium)
                        account.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        SettingsSwitch("启用账号更新", account.enabled, account.state != AccountState.SYNCING) { checked ->
                            runAction("enable_${account.id}") {
                                val current = checkNotNull(container.repository.getAccount(account.id))
                                container.repository.saveAccount(current.copy(enabled = checked))
                                container.syncCoordinator.updateSchedules()
                            }
                        }
                        if (account.platform == Platform.DOUYIN) {
                            SettingsSwitch("通过抖音 App 页面采集", account.captureMode == CaptureMode.APP_PAGE, account.state != AccountState.SYNCING) { checked ->
                                runAction("captureMode_${account.id}") {
                                    val current = checkNotNull(container.repository.getAccount(account.id))
                                    container.repository.saveAccount(current.copy(captureMode = if (checked) CaptureMode.APP_PAGE else CaptureMode.WEB, lastAttemptAt = null))
                                    container.syncCoordinator.updateSchedules()
                                }
                            }
                            if (account.captureMode == CaptureMode.APP_PAGE) Text("开启无障碍权限后，在抖音前台读取商城订单和物流页面。", style = MaterialTheme.typography.bodyMedium)
                        }
                        ActionButton(if (account.captureMode == CaptureMode.APP_PAGE) "采集商城订单" else "登录账号", false) { openAccountLogin(container, context, account) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (account.captureMode == CaptureMode.WEB) TextButton(onClick = {
                                runAction("account_${account.id}") { container.syncCoordinator.syncAccount(account.id) }
                            }, enabled = account.enabled && busy["account_${account.id}"] != true && account.state != AccountState.SYNCING, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("更新账号") }
                            TextButton(onClick = { removingAccount = account }, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("移除账号", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            item { SectionTitle("添加账号") }
            items(Platform.entries) { platform ->
                TextButton(onClick = { addingPlatform = platform; accountLabel = "" }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) { Text("添加${platform.displayName}账号") }
            }
            item {
                SectionTitle("抖音采集辅助判断")
                SettingsSwitch("使用 Jev 辅助识别页面与入口", settings.nativeJevEnabled, jevConfigured && !container.syncCoordinator.hasActiveAccounts()) { checked ->
                    runAction("jevEnabled") { container.settings.save(settings.copy(nativeJevEnabled = checked)) }
                }
                Text("Jev 接收公共标签和必要节点结构，需要联网并产生调用费用。账号、订单编号、运单、地址和商品全文保存在本机。", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = jevKey, onValueChange = { jevKey = it }, label = { Text(if (jevConfigured) "替换 Jev API key" else "Jev API key") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        runAction("jevSave") {
                            container.credentials.putSecret(NativeSemanticJudge.CREDENTIAL_NAME, jevKey.trim())
                            jevKey = ""; jevConfigured = true; jevStatus = "凭据已加密保存"
                        }
                    }, enabled = jevKey.isNotBlank() && !container.syncCoordinator.hasActiveAccounts()) { Text("保存凭据") }
                    TextButton(onClick = {
                        runAction("jevCheck") {
                            withContext(Dispatchers.IO) {
                                NativeSemanticJudge(checkNotNull(container.credentials.getSecret(NativeSemanticJudge.CREDENTIAL_NAME))).use { it.checkConnection() }
                            }
                            jevStatus = "Jev连接检查通过；页面识别效果以真实采集验证为准"
                        }
                    }, enabled = jevConfigured && busy["jevCheck"] != true) { Text("检查连接") }
                }
                if (jevStatus.isNotEmpty()) Text(jevStatus, style = MaterialTheme.typography.bodyMedium)
            }
            item { SectionTitle("自动更新") }
            item { Text("进入应用时自动刷新，更新间隔为15分钟。", style = MaterialTheme.typography.bodyMedium) }
            item {
                SettingsSwitch("每30分钟后台更新", settings.backgroundEnabled, busy["background"] != true) { checked ->
                    runAction("background") { container.settings.save(settings.copy(backgroundEnabled = checked)); container.syncCoordinator.updateSchedules() }
                }
                Text("Android 根据电量、网络和系统限制安排执行时间。抖音 App 页面采集需要页面处于前台。", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    addingPlatform?.let { platform ->
        AlertDialog(onDismissRequest = { addingPlatform = null }, title = { Text("添加${platform.displayName}账号") },
            text = { FormField("账号名称", accountLabel, { accountLabel = it }) }, confirmButton = {
                TextButton(onClick = {
                    runAction("createAccount") {
                        val account = container.repository.createAccount(platform, accountLabel.trim())
                        container.syncCoordinator.updateSchedules()
                        addingPlatform = null
                        openAccountLogin(container, context, account)
                    }
                }, enabled = busy["createAccount"] != true, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("创建并登录") }
            }, dismissButton = { TextButton(onClick = { addingPlatform = null }) { Text("取消添加") } })
    }
    removingAccount?.let { account ->
        AlertDialog(onDismissRequest = { removingAccount = null }, title = { Text("移除账号「${account.label}」") },
            text = { Text("移除后停止更新该账号，已经保存的包裹和物流记录保留。") }, confirmButton = {
                TextButton(onClick = { runAction("remove_${account.id}") { container.syncCoordinator.removeAccount(account.id); removingAccount = null } }, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("移除账号") }
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
