package com.logisticsmanagement.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.logisticsmanagement.app.AppContainer
import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.Shipment
import com.logisticsmanagement.app.model.SourceRef
import com.logisticsmanagement.app.model.TrackingEvent
import kotlinx.coroutines.flow.flowOf

@Composable
internal fun ShipmentDetailScreen(container: AppContainer, shipment: Shipment?, accounts: List<Account>, busy: Map<String, Boolean>, runAction: RunAction, copy: (String, String) -> Unit, modifier: Modifier, onBack: () -> Unit) {
    val events by remember(shipment?.id) { shipment?.let { container.repository.observeEvents(it.id) } ?: flowOf(emptyList<TrackingEvent>()) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val sources by remember(shipment?.id) { shipment?.let { container.repository.observeSources(it.id) } ?: flowOf(emptyList<SourceRef>()) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var notes by rememberSaveable(shipment?.id, shipment?.notes) { mutableStateOf(shipment?.notes.orEmpty()) }
    val context = LocalContext.current
    Scaffold(modifier = modifier, topBar = { DetailTopBar("包裹详情", onBack) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).testTag("shipmentDetailList"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (shipment == null) {
                item { EmptyState("没有找到该包裹", "返回包裹列表查看当前记录。") }
                return@LazyColumn
            }
            item {
                Text(shipment.title, style = MaterialTheme.typography.titleLarge)
                Text(shipment.status.displayName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
            item { ValueRow("快递公司", shipment.carrier.ifBlank { "暂未提供" }) }
            item {
                ValueRow("运单号", shipment.trackingNumber ?: "暂未提供")
                shipment.trackingNumber?.let { number -> TextButton(onClick = { copy("运单号", number) }, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("复制运单号") } }
            }
            shipment.pickupCode?.let { code ->
                item {
                    ValueRow("取件码", code)
                    TextButton(onClick = { copy("取件码", code) }, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("复制取件码") }
                }
            }
            shipment.pickupAddress?.let { address -> item { ValueRow("取件地址", address) } }
            item { ValueRow("预计送达", shipment.estimatedDeliveryAt?.let(::formatTimestamp) ?: "平台暂未提供预计送达日期") }
            item { ValueRow("最近更新", formatTimestamp(shipment.updatedAt)) }
            item { ActionButton("更新物流", busy["refresh_${shipment.id}"] == true) { runAction("refresh_${shipment.id}") { container.syncCoordinator.refreshShipment(shipment.id) } } }
            item { SectionTitle("来源账号") }
            items(sources, key = { it.id }) { source ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${source.platform.displayName} · ${source.accountLabel}", style = MaterialTheme.typography.bodyLarge)
                    if (source.orderUrl.isNotBlank()) {
                        val account = accounts.firstOrNull { it.id == source.accountId }
                        TextButton(onClick = { container.platforms.openSource(context, checkNotNull(account), source) }, enabled = account != null, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text(if (account == null) "来源账号已经移除" else "查看平台订单") }
                    }
                }
            }
            item { SectionTitle("备注") }
            item { FormField("包裹备注", notes, { notes = it }, singleLine = false) }
            item { ActionButton("保存备注", busy["notes_${shipment.id}"] == true, notes != shipment.notes) { runAction("notes_${shipment.id}") { container.repository.setNotes(shipment.id, notes) } } }
            item {
                OutlinedButton(onClick = { runAction("archive_${shipment.id}") { container.repository.setArchived(shipment.id, !shipment.archived) } }, enabled = busy["archive_${shipment.id}"] != true, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                    Text(if (shipment.archived) "恢复到包裹列表" else "归档包裹")
                }
            }
            item { SectionTitle("物流记录") }
            if (events.isEmpty()) item { Text("暂未获取到物流记录。更新物流后查看。", style = MaterialTheme.typography.bodyLarge) }
            items(events, key = { it.id }) { event ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(formatTimestamp(event.time), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(event.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private val ManualCarriers = listOf("顺丰速运" to "shunfeng", "中通快递" to "zhongtong", "圆通速递" to "yuantong", "韵达快递" to "yunda", "申通快递" to "shentong", "京东物流" to "jd", "中国邮政" to "youzhengguonei", "EMS" to "ems", "德邦快递" to "debangkuaidi", "极兔速递" to "jtexpress")

@Composable
internal fun AddShipmentScreen(container: AppContainer, busy: Map<String, Boolean>, runAction: RunAction, modifier: Modifier, onBack: () -> Unit, onAdded: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var carrierName by rememberSaveable { mutableStateOf("") }
    var carrierCode by rememberSaveable { mutableStateOf("") }
    var number by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var selectingCarrier by remember { mutableStateOf(false) }
    Scaffold(modifier = modifier, topBar = { DetailTopBar("添加包裹", onBack) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).testTag("addShipmentList"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { FormField("包裹名称（选填）", title, { title = it }) }
            item {
                Column {
                    OutlinedButton(onClick = { selectingCarrier = true }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) { Text(carrierName.ifBlank { "选择快递公司" }) }
                    DropdownMenu(expanded = selectingCarrier, onDismissRequest = { selectingCarrier = false }) {
                        ManualCarriers.forEach { (name, code) -> DropdownMenuItem(text = { Text(name) }, onClick = { carrierName = name; carrierCode = code; selectingCarrier = false }, modifier = Modifier.sizeIn(minHeight = 48.dp)) }
                    }
                }
            }
            item { FormField("运单号", number, { number = it }) }
            item { FormField(if (carrierCode in setOf("shunfeng", "zhongtong")) "收件手机号码（必填）" else "收件手机号码（选填）", phone, { phone = it }) }
            item { Text("物流查询使用设置中的快递100服务。手机号码会加密保存。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                ActionButton("添加包裹", busy["addShipment"] == true, carrierCode.isNotBlank() && number.isNotBlank() && (carrierCode !in setOf("shunfeng", "zhongtong") || phone.isNotBlank())) {
                    runAction("addShipment") {
                        val shipment = container.repository.createManualShipment(title.trim(), carrierName, carrierCode, number.trim(), phone.trim().takeIf { it.isNotEmpty() }?.let(container.credentials::encrypt))
                        container.syncCoordinator.updateSchedules()
                        onAdded(shipment.id)
                        container.syncCoordinator.refreshShipment(shipment.id)
                    }
                }
            }
        }
    }
}
