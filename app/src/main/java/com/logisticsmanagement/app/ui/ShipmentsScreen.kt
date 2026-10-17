package com.logisticsmanagement.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.logisticsmanagement.app.AppContainer
import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.AccountState
import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.model.Order
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.model.Shipment
import com.logisticsmanagement.app.model.SourceRef

private val StatusOrder = listOf(ParcelStatus.PICKUP, ParcelStatus.EXCEPTION, ParcelStatus.DELIVERING, ParcelStatus.IN_TRANSIT, ParcelStatus.WAITING_SHIPMENT, ParcelStatus.UNKNOWN, ParcelStatus.SIGNED)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShipmentsScreen(container: AppContainer, shipments: List<Shipment>, unshippedOrders: List<Order>, accounts: List<Account>, storedAccounts: List<Account>, sources: List<SourceRef>, busy: Map<String, Boolean>, runAction: RunAction, copy: (String, String) -> Unit, modifier: Modifier, onOpen: (String) -> Unit, nativeUnlinkedOrders: List<Order> = emptyList()) {
    var query by rememberSaveable { mutableStateOf("") }
    var statusFilter by rememberSaveable { mutableStateOf("全部") }
    var platformFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var accountFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var archived by rememberSaveable { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val sourcesById = remember(sources) { sources.groupBy { it.shipmentId } }
    val accountsById = remember(storedAccounts) { storedAccounts.associateBy { it.id } }
    val context = LocalContext.current
    val results = shipments.filter { shipment ->
        val shipmentSources = sourcesById[shipment.id].orEmpty()
        shipment.archived == archived &&
            (platformFilter == null || shipmentSources.any { it.platform.name == platformFilter }) &&
            (accountFilter == null || shipmentSources.any { it.accountId == accountFilter }) &&
            when (statusFilter) {
                "待取件" -> shipment.status == ParcelStatus.PICKUP
                "在途" -> shipment.status != ParcelStatus.SIGNED && shipment.status != ParcelStatus.PICKUP
                "已签收" -> shipment.status == ParcelStatus.SIGNED
                "全部" -> true
                else -> shipment.status.displayName == statusFilter
            } &&
            (query.isBlank() || listOf(shipment.title, shipment.carrier, shipment.trackingNumber.orEmpty(), shipment.notes,
                shipmentSources.joinToString(" ") { "${it.platform.displayName} ${it.accountLabel}" }).any { it.contains(query.trim(), ignoreCase = true) })
    }.sortedWith(compareBy<Shipment> { StatusOrder.indexOf(it.status) }.thenByDescending { it.updatedAt }.thenBy { it.id })
    val orderResults = unshippedOrders.filter { order ->
        val account = accountsById[order.accountId]
        !archived && statusFilter in setOf("全部", "在途", "待发货") &&
            (platformFilter == null || account?.platform?.name == platformFilter) &&
            (accountFilter == null || order.accountId == accountFilter) &&
            (query.isBlank() || listOf(order.title, order.remoteId, account?.label.orEmpty(), account?.platform?.displayName.orEmpty()).any { it.contains(query.trim(), ignoreCase = true) })
    }.sortedByDescending { it.createdAt }
    val nativeResults = nativeUnlinkedOrders.filter { order ->
        val account = accountsById[order.accountId]
        !archived && (platformFilter == null || platformFilter == Platform.DOUYIN.name) &&
            (accountFilter == null || order.accountId == accountFilter) &&
            (statusFilter == "全部" || statusFilter == order.status.displayName || statusFilter == "在途" && order.status != ParcelStatus.SIGNED) &&
            (query.isBlank() || listOf(order.title, order.remoteId, account?.label.orEmpty()).any { it.contains(query.trim(), true) })
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text("包裹") }, actions = {
                IconButton(onClick = { showFilters = true }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(Icons.Outlined.FilterList, contentDescription = "筛选平台和账号")
                }
                IconButton(onClick = { runAction("syncAll") { container.syncCoordinator.syncAll() } }, enabled = busy["syncAll"] != true,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = if (busy["syncAll"] == true) "正在更新包裹" else "更新全部包裹")
                }
            })
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).testTag("shipmentsList"), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { FormField("搜索名称、运单号、备注或账号", query, { query = it }, modifier = Modifier.testTag("shipmentsSearch")) }
            item {
                Text("待取件 ${shipments.count { !it.archived && it.status == ParcelStatus.PICKUP }} 件 · 在途 ${shipments.count { !it.archived && it.status != ParcelStatus.SIGNED && it.status != ParcelStatus.PICKUP } + unshippedOrders.size + nativeUnlinkedOrders.count { it.status != ParcelStatus.SIGNED && it.status != ParcelStatus.PICKUP }} 件",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                LazyRow(modifier = Modifier.testTag("statusFilters"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("全部", "待取件", "物流异常", "在途", "待发货", "运输中", "派送中", "已签收", "状态未提供")) { status ->
                        FilterChip(selected = statusFilter == status, onClick = { statusFilter = status }, label = { Text(status) }, modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("statusFilter_$status"))
                    }
                    item { FilterChip(selected = archived, onClick = { archived = !archived }, label = { Text("已归档") }, modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("archivedFilter")) }
                }
            }
            if (busy["syncAll"] == true || accounts.any { it.state == AccountState.SYNCING }) {
                item { InfoBanner("正在更新订单和在途包裹，已保存的记录可以继续查看。") }
            }
            if (platformFilter != null || accountFilter != null) {
                item { TextButton(onClick = { platformFilter = null; accountFilter = null }, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("清除平台和账号筛选") } }
            }
            accounts.filter { it.enabled && it.state in setOf(AccountState.NEEDS_LOGIN, AccountState.NEEDS_VERIFICATION, AccountState.FAILED) }.forEach { account ->
                item(key = "account_${account.id}") {
                    InfoBanner(account.error ?: "${account.platform.displayName}账号「${account.label}」${accountStateLabel(account.state)}。", isError = true) {
                        TextButton(onClick = {
                            if (account.state == AccountState.FAILED) runAction("account_${account.id}") { container.syncCoordinator.syncAccount(account.id) }
                            else openAccountLogin(container, context, account)
                        }, enabled = busy["account_${account.id}"] != true, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                            Text(when (account.state) {
                                AccountState.NEEDS_VERIFICATION -> "完成验证"
                                AccountState.FAILED -> if (busy["account_${account.id}"] == true) "正在更新" else "重新更新"
                                else -> "重新登录"
                            })
                        }
                    }
                }
            }
            if (results.isEmpty() && orderResults.isEmpty() && nativeResults.isEmpty()) {
                item {
                    EmptyState(if (query.isNotBlank()) "没有找到包含「${query}」的包裹" else "当前条件下没有包裹",
                        if (accounts.isEmpty() && shipments.isEmpty() && unshippedOrders.isEmpty()) "在设置中添加平台账号后，可以查看订单和物流记录。" else "更新包裹或调整筛选条件后查看。") {
                        if (query.isNotBlank() || platformFilter != null || accountFilter != null || archived || statusFilter != "全部") {
                            TextButton(onClick = { query = ""; platformFilter = null; accountFilter = null; archived = false; statusFilter = "全部" }) { Text("清除筛选") }
                        }
                    }
                }
            }
            StatusOrder.forEach { status ->
                val nativeGroup = nativeResults.filter { it.status == status }
                if (nativeGroup.isNotEmpty()) {
                    item(key = "native_orders_${status.name}") { SectionTitle("${status.displayName}订单 · ${nativeGroup.size} 笔") }
                    items(nativeGroup, key = { "order_${it.id}" }) { order ->
                        val account = accountsById[order.accountId]
                        UnshippedOrderCard(order, account) { container.platforms.openOrder(context, checkNotNull(account), order) }
                    }
                }
                if (status == ParcelStatus.WAITING_SHIPMENT && orderResults.isNotEmpty()) {
                    item { SectionTitle("待发货订单 · ${orderResults.size} 件") }
                    items(orderResults, key = { "order_${it.id}" }) { order ->
                        val account = accountsById[order.accountId]
                        UnshippedOrderCard(order, account) { container.platforms.openOrder(context, checkNotNull(account), order) }
                    }
                }
                val group = results.filter { it.status == status }
                if (group.isNotEmpty()) {
                    item(key = "status_${status.name}") { SectionTitle("${status.displayName} · ${group.size} 件") }
                    items(group, key = { it.id }) { shipment ->
                        val events by remember(shipment.id) { container.repository.observeEvents(shipment.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
                        ShipmentCard(shipment, sourcesById[shipment.id].orEmpty(), events.firstOrNull()?.text, onOpen = { onOpen(shipment.id) }, copy = copy)
                    }
                }
            }
        }
    }
    if (showFilters) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }, dragHandle = {
            BottomSheetDefaults.DragHandle(modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp))
        }) {
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { SectionTitle("选择平台") }
                items(Platform.entries) { platform ->
                    FilterChip(selected = platformFilter == platform.name, onClick = { platformFilter = if (platformFilter == platform.name) null else platform.name; accountFilter = null },
                        label = { Text(platform.displayName) }, modifier = Modifier.sizeIn(minHeight = 48.dp).fillMaxWidth())
                }
                item { SectionTitle("选择账号") }
                items(storedAccounts.filter { platformFilter == null || it.platform.name == platformFilter }) { account ->
                    FilterChip(selected = accountFilter == account.id, onClick = { accountFilter = if (accountFilter == account.id) null else account.id },
                        label = { Text("${account.platform.displayName} · ${account.label}${if (account.deleted) " · 已移除" else ""}") }, modifier = Modifier.sizeIn(minHeight = 48.dp).fillMaxWidth())
                }
                item { ActionButton("查看包裹", false) { showFilters = false } }
            }
        }
    }
}

@Composable
private fun UnshippedOrderCard(order: Order, account: Account?, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag("order_${order.id}")) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(order.title, style = MaterialTheme.typography.titleMedium)
            Text(if (account?.platform == Platform.DOUYIN) "${order.status.displayName} · 物流待采集" else "待发货 · 平台尚未提供运单", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text(account?.let { "${it.platform.displayName} · ${it.label}" } ?: "来源账号记录未提供", style = MaterialTheme.typography.bodyMedium)
            order.createdAt?.let { Text("下单时间 ${formatTimestamp(it)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (order.orderUrl.isNotBlank() || account?.platform == Platform.DOUYIN && account.captureMode == com.logisticsmanagement.app.model.CaptureMode.APP_PAGE) {
                TextButton(onClick = onOpen, enabled = account != null && !account.deleted, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                    Text(if (account?.deleted == true) "来源账号已经移除" else "查看平台订单")
                }
            }
        }
    }
}

@Composable
internal fun ShipmentCard(shipment: Shipment, sources: List<SourceRef>, latestText: String?, onOpen: () -> Unit, copy: (String, String) -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("shipment_${shipment.id}"), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(shipment.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(shipment.status.displayName, style = MaterialTheme.typography.labelLarge, color = if (shipment.status == ParcelStatus.EXCEPTION) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            Text("${shipment.carrier.ifBlank { "快递公司未提供" }} · ${shipment.trackingNumber?.let { "尾号 ${it.takeLast(4)}" } ?: "运单号未提供"}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (sources.isNotEmpty()) Text(sources.distinctBy { it.accountId }.joinToString(" · ") { "${it.platform.displayName} ${it.accountLabel}" },
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (shipment.status == ParcelStatus.PICKUP) {
                shipment.pickupCode?.takeIf { it.isNotBlank() }?.let { code ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("取件码 $code", style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), modifier = Modifier.weight(1f))
                        TextButton(onClick = { copy("取件码", code) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("复制") }
                    }
                }
                shipment.pickupAddress?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            }
            if (latestText != null) Text(latestText, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("最近更新 ${formatTimestamp(shipment.updatedAt)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun accountStateLabel(state: AccountState): String = when (state) {
    AccountState.NEEDS_LOGIN -> "需要登录"
    AccountState.READY -> "可以更新"
    AccountState.SYNCING -> "正在更新"
    AccountState.NEEDS_VERIFICATION -> "需要完成验证"
    AccountState.PARTIAL -> "部分包裹等待更新"
    AccountState.FAILED -> "更新失败"
    AccountState.REMOVED -> "已经移除"
}
