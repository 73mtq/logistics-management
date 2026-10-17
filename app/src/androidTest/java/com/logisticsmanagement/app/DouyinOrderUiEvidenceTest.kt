package com.logisticsmanagement.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.model.*
import com.logisticsmanagement.app.platform.*
import com.logisticsmanagement.app.ui.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DouyinOrderUiEvidenceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun verifySavedOrder(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val directory = File(context.filesDir, "douyin-order-evidence/${checkNotNull(arguments.getString("runName"))}").apply { check(mkdirs()) }
        val descriptor = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand("am start -W -n com.logisticsmanagement.app/androidx.activity.ComponentActivity")
        File(directory, "foreground.txt").writeText(ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() })
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        assertFalse(container.syncCoordinator.hasActiveAccounts())
        val id = checkNotNull(arguments.getString("accountId"))
        val target = platformJson.decodeFromString<FixedFlowTarget>(File(context.filesDir, checkNotNull(arguments.getString("capturesPath"))).readText())
        val account = checkNotNull(container.repository.getAccount(id))
        val order = container.repository.getOrders().single { it.accountId == id && it.remoteId == target.orderId }
        assertEquals(ParcelStatus.SIGNED, order.status)
        assertEquals(DouyinNativeOrders.orderTime(target.expectedCreatedAtText), order.createdAt)
        val snapshot = DouyinOrderEvidenceTest()
        val before = snapshot.tables()
        File(directory, "before-tables.json").writeText(before)
        val shipments = container.repository.getShipments()
        val allOrders = container.repository.getOrders()
        val stored = platformJson.parseToJsonElement(before).jsonObject.getValue("Account").jsonArray.map { row ->
            checkNotNull(container.repository.getAccount(row.jsonObject.getValue("id").jsonPrimitive.content))
        }
        val accounts = stored.filter { !it.deleted }
        val unshipped = allOrders.filter { it.status == ParcelStatus.WAITING_SHIPMENT && container.database.dao().orderSources(it.id).isEmpty() }
        val nativeOrders = allOrders.filter { order -> order.status != ParcelStatus.WAITING_SHIPMENT &&
            stored.single { it.id == order.accountId }.platform == Platform.DOUYIN && container.database.dao().orderSources(order.id).isEmpty() }
        val sources = shipments.flatMap { container.database.dao().sources(it.id) }
        val settings = container.settings.get()
        val showSettings = mutableStateOf(false)
        val intent = Intent(context, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<ComponentActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val action: RunAction = { _, block -> activity.lifecycleScope.launch { block() } }
                activity.setContent {
                    LogisticsTheme {
                        if (showSettings.value) SettingsScreen(container, settings, accounts, emptyMap(), action, Modifier)
                        else ShipmentsScreen(container, shipments, unshipped, accounts, stored, sources, emptyMap(), action,
                            { label, text -> context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text)) },
                            Modifier, { shipment -> context.startActivity(Intent(context, MainActivity::class.java).putExtra("shipmentId", shipment).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, nativeOrders)
                    }
                }
            }
            compose.onNodeWithTag("shipmentsSearch").performTextInput(order.remoteId)
            compose.onNodeWithTag("shipmentsList").performScrollToNode(hasText(order.title))
            compose.onNodeWithText(order.title).assertIsDisplayed()
            compose.onNodeWithText("已签收 · 物流待采集").assertIsDisplayed()
            val horizontal = hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
            compose.onNode(horizontal).performScrollToNode(hasText("已签收"))
            compose.onNodeWithText("已签收").performClick()
            compose.onNodeWithTag("shipmentsList").performScrollToNode(hasText(order.title))
            compose.onNodeWithText(order.title).assertIsDisplayed()
            compose.onNode(horizontal).performScrollToNode(hasText("在途"))
            compose.onNodeWithText("在途").performClick()
            compose.onNodeWithText(order.title).assertDoesNotExist()
            compose.runOnUiThread { showSettings.value = true }
            val card = hasTestTag("account_$id")
            compose.onNodeWithTag("settingsList").performScrollToNode(card)
            compose.onNode(card).assertIsDisplayed()
            compose.onNode(card).assert(hasAnyDescendant(hasText(accountStateLabel(account.state))))
            account.error?.let { message -> compose.onNode(card).assert(hasAnyDescendant(hasText(message))) }
        }
        val after = snapshot.tables()
        File(directory, "after-tables.json").writeText(after)
        assertEquals(platformJson.parseToJsonElement(before), platformJson.parseToJsonElement(after))
        assertEquals(settings, container.settings.get())
        File(directory, "result.json").writeText(buildJsonObject {
            put("savedOrderCard", true); put("signedFilter", true); put("inTransitExcludesSigned", true)
            put("accountProgress", true); put("businessPreserved", true); put("renderedProductionComponents", true)
        }.toString())
    }
}
