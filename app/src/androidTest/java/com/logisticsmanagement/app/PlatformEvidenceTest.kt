package com.logisticsmanagement.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.platform.CapturedResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlatformEvidenceTest {
    @Test fun exportLocalEvidence(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("exportEvidence") == "true")
        val context = instrumentation.targetContext
        val container = (context.applicationContext as LogisticsApplication).container
        val directory = File(context.filesDir, "platform-evidence").apply { mkdirs() }
        val json = Json { ignoreUnknownKeys = true }
        for (account in container.repository.getAccounts()) {
            val captures = container.credentials.getSecret("account.${account.id}.captures")
                ?.let { json.decodeFromString<List<CapturedResponse>>(it) }.orEmpty()
            File(directory, "${account.platform.name}-${account.id}.json").writeText(json.encodeToString(captures))
            Log.i("ParcelEvidence", "platform=${account.platform.name}, captures=${captures.size}, state=${account.state}")
        }
        assertTrue(directory.isDirectory)
    }
}
