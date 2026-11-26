package com.logisticsmanagement.app.platform

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SmsCodeEvent(val code: String, val at: Long, val source: String)

internal object SmsCodeInbox {
    private val mutableCode = MutableStateFlow<SmsCodeEvent?>(null)
    val code = mutableCode.asStateFlow()
    @Volatile var expected: Boolean = false
        private set

    fun begin() { mutableCode.value = null; expected = true }
    fun end() { expected = false; mutableCode.value = null }
    internal fun offer(event: SmsCodeEvent) { if (expected) mutableCode.value = event }
}

class SmsCodeListener : NotificationListenerService() {
    override fun onNotificationPosted(notification: StatusBarNotification) {
        if (!SmsCodeInbox.expected) return
        if (notification.packageName != SMS_PACKAGE) {
            android.util.Log.i("SmsCodeListener", "ignored package=" + notification.packageName)
            return
        }
        val extras = notification.notification.extras
        val text = listOf(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
        ).filterNotNull().joinToString(" ")
        val code = CODE.find(text)?.groupValues?.get(1)
        android.util.Log.i("SmsCodeListener", "sms notification matched=" + (code != null))
        if (code == null) return
        SmsCodeInbox.offer(SmsCodeEvent(code, System.currentTimeMillis(), notification.packageName))
    }

    private companion object {
        const val SMS_PACKAGE = "com.android.mms"
        val CODE = Regex("(?:验证码|校验码|动态码|确认码)[^0-9]{0,20}([0-9]{4,8})")
    }
}
