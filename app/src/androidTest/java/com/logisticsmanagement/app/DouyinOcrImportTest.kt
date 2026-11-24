package com.logisticsmanagement.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.model.ImportedShipment
import com.logisticsmanagement.app.network.CarrierCodes
import com.logisticsmanagement.app.platform.DouyinCaptureController
import com.logisticsmanagement.app.platform.DouyinOcrLogistics
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DouyinOcrImportTest {
    @Test fun importCurrentLogistics(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LogisticsApplication
        app.container.ready.await()
        val arguments = InstrumentationRegistry.getArguments()
        val accountId = checkNotNull(arguments.getString("accountId"))
        val remoteId = checkNotNull(arguments.getString("remoteId"))
        val service = withTimeout(120_000) {
            while (DouyinCaptureController.service == null) delay(100)
            checkNotNull(DouyinCaptureController.service)
        }
        val screen = withTimeout(30_000) { service.captureScreen() } ?: error("截图识别失败")
        val parsed = checkNotNull(DouyinOcrLogistics.parse(screen.text, System.currentTimeMillis()))
        val account = checkNotNull(app.container.repository.getAccount(accountId))
        val identity = checkNotNull(account.externalIdentity)
        val code = CarrierCodes.resolve(parsed.carrier).orEmpty()
        val shipment = ImportedShipment("$code:${parsed.trackingNumber}", parsed.trackingNumber, parsed.carrier, code,
            parsed.status, events = parsed.events)
        app.container.repository.ingestNativeLogistics(accountId, identity, remoteId, shipment)
        val out = File(instrumentation.targetContext.filesDir, "douyin-ocr").apply { check(mkdirs() || isDirectory) }
        File(out, "import.txt").writeText("${parsed.carrier} ${parsed.trackingNumber} events=${parsed.events.size}")
    }
}
