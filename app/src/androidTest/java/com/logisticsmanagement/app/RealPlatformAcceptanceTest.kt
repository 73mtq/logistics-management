package com.logisticsmanagement.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.model.AccountState
import com.logisticsmanagement.app.model.Platform
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealPlatformAcceptanceTest {
    @Test fun syncSelectedAccountAndRetainImportedIdentity(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val selected = InstrumentationRegistry.getArguments().getString("platform")
        assumeTrue("需要通过 platform 参数选择已经登录的真实平台", selected != null)
        val name = checkNotNull(selected)
        val platform = Platform.valueOf(name)
        val container = (instrumentation.targetContext.applicationContext as LogisticsApplication).container
        val account = container.repository.getAccounts().firstOrNull { it.platform == platform && it.enabled }
        assertNotNull("该平台尚未添加真实账号", account)
        val id = checkNotNull(account).id
        container.syncCoordinator.syncAccount(id)
        assertEquals(AccountState.READY, container.repository.getAccount(id)?.state)
        val firstOrders = container.repository.getOrders().filter { it.accountId == id }.map { it.id }.toSet()
        val firstSources = container.repository.getShipments().flatMap { container.repository.getSources(it.id) }
            .filter { it.accountId == id }.map { it.id }.toSet()
        container.syncCoordinator.syncAccount(id)
        assertEquals(AccountState.READY, container.repository.getAccount(id)?.state)
        val secondOrders = container.repository.getOrders().filter { it.accountId == id }.map { it.id }.toSet()
        val secondSources = container.repository.getShipments().flatMap { container.repository.getSources(it.id) }
            .filter { it.accountId == id }.map { it.id }.toSet()
        check(secondOrders.containsAll(firstOrders)) { "重复同步后已导入订单标识丢失" }
        check(secondSources.containsAll(firstSources)) { "重复同步后已导入来源标识丢失" }
        Log.i("ParcelAcceptance", "platform=${platform.name}, orders=${secondOrders.size}, sources=${secondSources.size}, repeatedSync=passed")
    }
}
