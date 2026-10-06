package com.logisticsmanagement.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.logisticsmanagement.app.AppContainer
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.model.SourceRef
import com.logisticsmanagement.app.model.Shipment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

internal typealias RunAction = (String, suspend () -> Unit) -> Unit

private enum class Destination(val title: String) {
    SHIPMENTS("包裹"), CALENDAR("日历"), ASSISTANT("助手"), SETTINGS("设置")
}

@Composable
fun LogisticsApp(container: AppContainer, initialShipmentId: String? = null, initialDestination: String? = null, navigationRequestId: Long = 0L) {
    val shipments by remember(container) { container.repository.observeShipments() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by remember(container) { container.repository.observeAccounts() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val storedAccounts by remember(container) { container.repository.observeAllAccounts() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val unshippedOrders by remember(container) { container.repository.observeUnshippedOrders() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val sourceFlow = remember(container, shipments.map { it.id }) {
        if (shipments.isEmpty()) flowOf(emptyList<SourceRef>())
        else combine(shipments.map { container.repository.observeSources(it.id) }) { it.toList().flatten() }
    }
    val sources by sourceFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by remember(container) { container.settings.observe() }.collectAsStateWithLifecycle(initialValue = com.logisticsmanagement.app.data.AppSettings())
    var destination by rememberSaveable { mutableStateOf(when (initialDestination) {
        Destination.ASSISTANT.name -> Destination.ASSISTANT.name
        Destination.SETTINGS.name -> Destination.SETTINGS.name
        else -> Destination.SHIPMENTS.name
    }) }
    var detailId by rememberSaveable { mutableStateOf(initialShipmentId) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var consumedNavigationRequestId by rememberSaveable { mutableStateOf(navigationRequestId) }
    LaunchedEffect(initialShipmentId, initialDestination, navigationRequestId) {
        if (consumedNavigationRequestId == navigationRequestId) return@LaunchedEffect
        consumedNavigationRequestId = navigationRequestId
        if (initialShipmentId != null) {
            showAdd = false
            destination = Destination.SHIPMENTS.name
            detailId = initialShipmentId
        } else if (initialDestination != null) {
            showAdd = false
            detailId = null
            destination = Destination.entries.firstOrNull { it.name == initialDestination }?.name ?: Destination.SHIPMENTS.name
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val screenState = rememberSaveableStateHolder()
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val runAction: RunAction = { key, block -> runAction(scope, snackbar, busy, key, block) }
    val context = LocalContext.current
    val copy: (String, String) -> Unit = { label, value ->
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
        scope.launch { snackbar.showSnackbar("${label}已复制") }
    }
    val onOpenShipment: (String) -> Unit = { detailId = it }
    BackHandler(enabled = detailId != null || showAdd || destination != Destination.SHIPMENTS.name) {
        when {
            showAdd -> showAdd = false
            detailId != null -> detailId = null
            else -> destination = Destination.SHIPMENTS.name
        }
    }
    LogisticsTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (detailId == null && !showAdd) {
                    NavigationBar {
                        Destination.entries.forEach { item ->
                            NavigationBarItem(
                                modifier = Modifier.testTag("navigation_${item.name.lowercase()}"),
                                selected = destination == item.name,
                                onClick = { destination = item.name },
                                label = { Text(item.title) },
                                icon = {
                                    Icon(
                                        when (item) {
                                            Destination.SHIPMENTS -> Icons.Outlined.Inventory2
                                            Destination.CALENDAR -> Icons.Outlined.CalendarMonth
                                            Destination.ASSISTANT -> Icons.Outlined.QuestionAnswer
                                            Destination.SETTINGS -> Icons.Outlined.Settings
                                        },
                                        contentDescription = null,
                                    )
                                },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            val modifier = Modifier.fillMaxSize().padding(padding)
            val screenKey = if (showAdd) "ADD_SHIPMENT" else detailId?.let { "DETAIL_$it" } ?: destination
            screenState.SaveableStateProvider(screenKey) {
                when {
                    showAdd -> AddShipmentScreen(container, busy, runAction, modifier, onBack = { showAdd = false }, onAdded = { showAdd = false; detailId = it })
                    detailId != null -> key(detailId) {
                        ShipmentDetailRoute(container, checkNotNull(detailId), accounts, busy, runAction, copy, modifier) { detailId = null }
                    }
                    destination == Destination.SHIPMENTS.name -> ShipmentsScreen(container, shipments, unshippedOrders, accounts, storedAccounts, sources, busy, runAction, copy, modifier, onOpenShipment) { showAdd = true }
                    destination == Destination.CALENDAR.name -> CalendarScreen(shipments, modifier, onOpenShipment)
                    destination == Destination.ASSISTANT.name -> AssistantScreen(container, settings, shipments, busy, runAction, modifier, onOpenShipment) { destination = Destination.SETTINGS.name }
                    else -> SettingsScreen(container, settings, accounts, busy, runAction, modifier)
                }
            }
        }
    }
}

private data class ShipmentSnapshot(val shipment: Shipment?)

@Composable
private fun ShipmentDetailRoute(container: AppContainer, shipmentId: String, accounts: List<Account>, busy: Map<String, Boolean>, runAction: RunAction, copy: (String, String) -> Unit, modifier: Modifier, onBack: () -> Unit) {
    val snapshot by remember(container, shipmentId) {
        container.repository.observeShipment(shipmentId).map { ShipmentSnapshot(it) }
    }.collectAsStateWithLifecycle(initialValue = null)
    val current = snapshot
    if (current == null) {
        Scaffold(modifier = modifier, topBar = { DetailTopBar("包裹详情", onBack) }) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("正在读取包裹")
            }
        }
    } else {
        ShipmentDetailScreen(container, current.shipment, accounts, busy, runAction, copy, modifier, onBack)
    }
}

internal fun openAccountLogin(container: AppContainer, context: Context, account: Account) {
    if (account.platform == Platform.XIAOMI) container.platforms.openXiaomiConfiguration(context, account)
    else container.platforms.openLogin(context, account)
}

private fun runAction(scope: CoroutineScope, snackbar: SnackbarHostState, busy: MutableMap<String, Boolean>, key: String, block: suspend () -> Unit) {
    if (busy[key] == true) return
    busy[key] = true
    scope.launch {
        try {
            block()
        } catch (failure: AppFailure) {
            snackbar.showSnackbar(checkNotNull(failure.message))
        } catch (failure: IOException) {
            snackbar.showSnackbar("网络请求失败：${failure.message ?: failure.javaClass.simpleName}")
        } finally {
            busy.remove(key)
        }
    }
}
