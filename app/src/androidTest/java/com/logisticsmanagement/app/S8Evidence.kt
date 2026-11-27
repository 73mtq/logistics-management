package com.logisticsmanagement.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.JsonObject
import java.io.File

internal object S8Evidence {
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun argument(name: String): String =
        checkNotNull(InstrumentationRegistry.getArguments().getString(name)) { "缺少测试参数：$name" }.also { check(it.isNotBlank()) }

    fun directory(suffix: String): File {
        val runName = argument("runName")
        check(runName.matches(Regex("S8-[A-Za-z0-9_-]+"))) { "runName 格式不正确" }
        val root = checkNotNull(context.getExternalFilesDir(null)) { "应用外部文件目录不可用" }
        return File(root, "s8-evidence/$runName/$suffix").apply { check(mkdirs() || isDirectory) }
    }

    fun save(directory: File, name: String, value: JsonObject) {
        File(directory, name).writeText(value.toString())
    }
}
