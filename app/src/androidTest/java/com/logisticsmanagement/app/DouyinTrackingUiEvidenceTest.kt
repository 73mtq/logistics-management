package com.logisticsmanagement.app

import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DouyinTrackingUiEvidenceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun verifySavedLogistics(): Unit = runBlocking {
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
        val account = checkNotNull(container.repository.getAccount(checkNotNull(arguments.getString("accountId"))))
        val target = platformJson.decodeFromString<FixedFlowTarget>(File(context.filesDir, checkNotNull(arguments.getString("capturesPath"))).readText())
        val order = container.repository.getOrders().single { it.accountId == account.id && it.remoteId == target.orderId }
        val shipments = container.repository.getShipments()
        val sources = shipments.flatMap { container.repository.getSources(it.id) }
        val source = sources.single { it.orderId == order.id && it.accountId == account.id }
        val shipment = checkNotNull(container.repository.getShipment(source.shipmentId))
        assertTrue(container.repository.observeSources(shipment.id).first().any { it.id == source.id })
        assertEquals(account.label, source.accountLabel)
        val events = container.repository.getEvents(shipment.id)
        assertTrue(events.isNotEmpty())
        val before = DouyinOrderEvidenceTest().tables()
        File(directory, "before-tables.json").writeText(before)
        val accounts = platformJson.parseToJsonElement(before).jsonObject.getValue("Account").jsonArray.map { row ->
            checkNotNull(container.repository.getAccount(row.jsonObject.getValue("id").jsonPrimitive.content))
        }
        val active = accounts.filter { !it.deleted }
        val allOrders = container.repository.getOrders()
        val native = container.repository.getOrders().filter { candidate -> accounts.single { it.id == candidate.accountId }.platform == Platform.DOUYIN &&
            container.database.dao().orderSources(candidate.id).isEmpty() }
        assertTrue(native.none { it.id == order.id })
        val showDetail = mutableStateOf(false)
        ActivityScenario.launch<ComponentActivity>(Intent(context, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            scenario.onActivity { activity ->
                val action: RunAction = { _, block -> activity.lifecycleScope.launch { block() } }
                activity.setContent { LogisticsTheme {
                    if (showDetail.value) ShipmentDetailScreen(container, shipment, active, emptyMap(), action, { _, _ -> }, Modifier) { showDetail.value = false }
                    else ShipmentsScreen(container, shipments, allOrders.filter { it.status == ParcelStatus.WAITING_SHIPMENT }, active, accounts, sources,
                        emptyMap(), action, { _, _ -> }, Modifier, { showDetail.value = true }, native)
                } }
            }
            compose.onNodeWithTag("shipmentsSearch").performTextInput(checkNotNull(shipment.trackingNumber))
            compose.onNodeWithTag("shipmentsList").performScrollToNode(hasText(order.title))
            compose.onAllNodesWithText(order.title).assertCountEquals(1)
            compose.onNodeWithText("已签收 · 物流待采集").assertDoesNotExist()
            compose.onNodeWithText(order.title).performClick()
            compose.onNodeWithTag("shipmentDetailList").performScrollToNode(hasText(shipment.carrier))
            compose.onNodeWithText(shipment.carrier).assertIsDisplayed()
            compose.onNodeWithTag("shipmentDetailList").performScrollToNode(hasText(checkNotNull(shipment.trackingNumber)))
            compose.onNodeWithText(checkNotNull(shipment.trackingNumber)).assertIsDisplayed()
            val sourceText = "${source.platform.displayName} · ${source.accountLabel}"
            compose.onNodeWithTag("shipmentDetailList").performScrollToNode(hasText("来源账号"))
            compose.waitUntil(10_000) { compose.onAllNodesWithText(sourceText).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(sourceText).assertIsDisplayed()
            for (event in events) {
                compose.onNodeWithTag("shipmentDetailList").performScrollToNode(hasText(event.text))
                compose.onNodeWithText(event.text).assertIsDisplayed()
            }
        }
        val after = DouyinOrderEvidenceTest().tables()
        File(directory, "after-tables.json").writeText(after)
        assertEquals(platformJson.parseToJsonElement(before), platformJson.parseToJsonElement(after))
        File(directory, "result.json").writeText(buildJsonObject {
            put("singleLinkedCard", true); put("carrierDisplayed", true); put("trackingNumberDisplayed", true)
            put("sourceDisplayed", true); put("eventsDisplayed", true); put("businessPreserved", true)
        }.toString())
    }
}
