package com.logisticsmanagement.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DouyinEvidenceExportTest {
    @Test fun exportCapture(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val captureId = checkNotNull(InstrumentationRegistry.getArguments().getString("captureId"))
        val source = File(context.filesDir, "native-entry-evidence/$captureId")
        check(source.isDirectory) { "采集目录不存在：${source.path}" }
        val target = File(checkNotNull(context.getExternalFilesDir(null)), "export/$captureId").apply { check(mkdirs() || isDirectory) }
        val names = InstrumentationRegistry.getArguments().getString("names")?.split(',')?.filter { it.isNotBlank() }?.toSet()
        var count = 0
        source.listFiles().orEmpty().filter { it.extension == "enc" }.sortedBy { it.name }
            .filter { names == null || it.name.removeSuffix(".enc") in names }
            .forEach { file ->
                File(target, file.name.removeSuffix(".enc") + ".json").writeText(container.credentials.decrypt(file.readText()))
                count++
            }
        File(target, "count.txt").writeText(count.toString())
    }
}
