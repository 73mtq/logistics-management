package com.logisticsmanagement.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.logisticsmanagement.app.AppContainer
import com.logisticsmanagement.app.data.AppSettings
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.SourceRef
import com.logisticsmanagement.app.model.Shipment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.logisticsmanagement.app.model.CaptureMode
import com.logisticsmanagement.app.platform.DouyinCaptureActivity
import java.io.IOException

internal typealias RunAction = (String, suspend () -> Unit) -> Unit

private enum class Destination(val title: String) {
    SHIPMENTS("包裹"), CALENDAR("日历"), SETTINGS("设置")
}

@Composable
fun LogisticsApp(container: AppContainer, initialShipmentId: String? = null, initialDestination: String? = null, navigationRequestId: Long = 0L) {
    val shipments by remember(container) { container.repository.observeShipments() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by remember(container) { container.repository.observeAccounts() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val storedAccounts by remember(container) { container.repository.observeAllAccounts() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val unshippedOrders by remember(container) { container.repository.observeUnshippedOrders() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val nativeUnlinkedOrders by remember(container) { container.repository.observeNativeUnlinkedOrders() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val sourceFlow = remember(container, shipments.map { it.id }) {
        if (shipments.isEmpty()) flowOf(emptyList<SourceRef>())
        else combine(shipments.map { container.repository.observeSources(it.id) }) { it.toList().flatten() }
    }
    val sources by sourceFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by remember(container) { container.settings.observe() }.collectAsStateWithLifecycle(initialValue = AppSettings())
    var destination by rememberSaveable { mutableStateOf(when (initialDestination) {
        Destination.SETTINGS.name -> Destination.SETTINGS.name
        else -> Destination.SHIPMENTS.name
    }) }
    var detailId by rememberSaveable { mutableStateOf(initialShipmentId) }
    var consumedNavigationRequestId by rememberSaveable { mutableStateOf(navigationRequestId) }
    LaunchedEffect(initialShipmentId, initialDestination, navigationRequestId) {
        if (consumedNavigationRequestId == navigationRequestId) return@LaunchedEffect
        consumedNavigationRequestId = navigationRequestId
        if (initialShipmentId != null) {
            destination = Destination.SHIPMENTS.name
            detailId = initialShipmentId
        } else if (initialDestination != null) {
            detailId = null
            destination = Destination.entries.firstOrNull { it.name == initialDestination }?.name ?: Destination.SHIPMENTS.name
        }
    }
    val snackbar = remember { SnackbarHostState() }
    var snackbarHeight by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(container, lifecycleOwner) {
        fun refresh() { scope.launch {
            container.ready.await()
            try { if (container.automaticUpdatesEnabled) container.syncCoordinator.syncAll(automatic = true) }
            catch (failure: AppFailure) { snackbar.showSnackbar(checkNotNull(failure.message)) }
        } }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) refresh()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val screenState = rememberSaveableStateHolder()
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val runAction: RunAction = { key, block -> runAction(scope, snackbar, busy, key, block) }
    val context = LocalContext.current
    val copy: (String, String) -> Unit = { label, value ->
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
        scope.launch { snackbar.showSnackbar("${label}已复制") }
    }
    val onOpenShipment: (String) -> Unit = { detailId = it }
    BackHandler(enabled = detailId != null || destination != Destination.SHIPMENTS.name) {
        when {
            detailId != null -> detailId = null
            else -> destination = Destination.SHIPMENTS.name
        }
    }
    LogisticsTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar, Modifier.onSizeChanged { snackbarHeight = it.height }) },
            bottomBar = {
                if (detailId == null) {
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
            val modifier = Modifier.fillMaxSize().padding(padding).padding(bottom = with(LocalDensity.current) { snackbarHeight.toDp() })
            val screenKey = detailId?.let { "DETAIL_$it" } ?: destination
            screenState.SaveableStateProvider(screenKey) {
                when {
                    detailId != null -> key(detailId) {
                        ShipmentDetailRoute(container, checkNotNull(detailId), accounts, busy, runAction, copy, modifier) { detailId = null }
                    }
                    destination == Destination.SHIPMENTS.name -> ShipmentsScreen(container, shipments, unshippedOrders, accounts, storedAccounts, sources, busy, runAction, copy, modifier, onOpenShipment, nativeUnlinkedOrders)
                    destination == Destination.CALENDAR.name -> CalendarScreen(shipments, modifier, onOpenShipment)
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
    if (account.captureMode == CaptureMode.APP_PAGE) {
        context.startActivity(Intent(context, DouyinCaptureActivity::class.java).putExtra("accountId", account.id))
    } else container.platforms.openLogin(context, account)
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
