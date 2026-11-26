package com.logisticsmanagement.app

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.logisticsmanagement.app.platform.DouyinCaptureController
import com.logisticsmanagement.app.platform.DouyinCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

internal object DouyinDeviceHarness {
    fun shell(automation: UiAutomation, command: String): String {
        val descriptor = automation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    fun topPackage(automation: UiAutomation): String {
        val output = shell(automation, "dumpsys activity activities | grep topResumedActivity")
        return Regex("ActivityRecord\\{[^}]*\\s([A-Za-z0-9_.]+)/").find(output)?.groupValues?.get(1).orEmpty()
    }

    suspend fun ensureAccessibility(automation: UiAutomation) {
        val component = "com.logisticsmanagement.app/com.logisticsmanagement.app.platform.DouyinCaptureService"
        val current = shell(automation, "settings get secure enabled_accessibility_services").trim()
        val normalized = if (current == "null") "" else current
        val others = normalized.split(':').filter { it.isNotBlank() && it != component }
        if (others.isEmpty()) shell(automation, "settings delete secure enabled_accessibility_services")
        else shell(automation, "settings put secure enabled_accessibility_services " + others.joinToString(":"))
        delay(500)
        val value = if (others.isEmpty()) component else others.joinToString(":") + ":" + component
        shell(automation, "settings put secure enabled_accessibility_services " + value)
        shell(automation, "settings put secure accessibility_enabled 1")
        delay(1_200)
    }

    suspend fun awaitService(automation: UiAutomation, context: Context, container: AppContainer): DouyinCaptureService {
        container.ready.await()
        repeat(3) {
            DouyinCaptureController.service?.let { return it }
            withContext(Dispatchers.Main.immediate) {
                val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            delay(1_000)
            ensureAccessibility(automation)
            withTimeoutOrNull(60_000) { while (DouyinCaptureController.service == null) delay(200) }
        }
        return checkNotNull(DouyinCaptureController.service) { "抖音采集服务未连接" }
    }

    suspend fun startDouyin(automation: UiAutomation) {
        shell(automation, "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.ss.android.ugc.aweme/.splash.SplashActivity")
        val launchDeadline = SystemClock.elapsedRealtime() + 20_000
        var top = topPackage(automation)
        while (top != DouyinCaptureController.PACKAGE && SystemClock.elapsedRealtime() < launchDeadline) {
            delay(500)
            top = topPackage(automation)
        }
        check(top == DouyinCaptureController.PACKAGE) { "抖音没有进入前台：${top.ifBlank { "未知" }}" }
        delay(1_500)
    }

    suspend fun launchDouyin(automation: UiAutomation, context: Context) {
        shell(automation, "am force-stop " + DouyinCaptureController.PACKAGE)
        delay(1_000)
        startDouyin(automation)
    }

    fun export(directory: File, captureId: String, automation: UiAutomation, context: Context, container: AppContainer) {
        val evidence = File(directory, "evidence").apply { check(mkdirs() || isDirectory) }
        val failures = mutableListOf<String>()
        File(context.filesDir, "native-entry-evidence/$captureId").listFiles().orEmpty()
            .filter { it.extension == "enc" }
            .forEach { file ->
                try {
                    File(evidence, file.name.removeSuffix(".enc") + ".json").writeText(container.credentials.decrypt(file.readText()))
                } catch (failure: Exception) {
                    failures.add(file.name + ": " + (failure.message ?: failure.javaClass.simpleName))
                }
            }
        check(failures.isEmpty()) { "采集资料无法解密：${failures.joinToString()}" }
        val ocr = File(directory, "ocr").apply { check(mkdirs() || isDirectory) }
        DouyinCaptureController.ocrEvidence().forEachIndexed { index, record ->
            val name = index.toString().padStart(3, '0')
            File(ocr, "$name.txt").writeText("capturedAt=${record.capturedAt}\n" + record.text)
            record.jpeg?.let { File(ocr, "$name.jpg").writeBytes(it) }
        }
        try {
            automation.takeScreenshot()?.let { bitmap ->
                File(directory, "screen.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } catch (failure: Exception) {
            File(directory, "screenshot-error.txt").writeText(failure.message ?: failure.javaClass.simpleName)
        }
    }
}
