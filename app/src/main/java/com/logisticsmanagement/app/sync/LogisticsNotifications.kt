package com.logisticsmanagement.app.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import com.logisticsmanagement.app.MainActivity
import com.logisticsmanagement.app.platform.PlatformBrowserActivity
import com.logisticsmanagement.app.platform.XiaomiAccountActivity
import com.logisticsmanagement.app.model.*

class LogisticsNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val sent = context.getSharedPreferences("notification_events", Context.MODE_PRIVATE)
    fun createChannels() {
        manager.createNotificationChannel(NotificationChannel("tracking", "物流变化与取件信息", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel("accounts", "账号登录与验证", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel("reports", "快递日报", NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun allowed(): Boolean = android.os.Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun tracking(shipment: Shipment, event: TrackingEvent?) {
        if (!allowed()) return
        val eventKey = "${shipment.id}:${shipment.status}:${event?.id.orEmpty()}:${shipment.pickupCode.orEmpty()}"
        if (sent.contains(eventKey)) return
        val intent = Intent(context, MainActivity::class.java).putExtra("shipmentId", shipment.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, shipment.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (shipment.status == ParcelStatus.PICKUP && !shipment.pickupCode.isNullOrBlank()) "取件码 ${shipment.pickupCode}" else event?.text ?: shipment.status.displayName
        manager.notify(shipment.id.hashCode(), NotificationCompat.Builder(context, "tracking")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("${shipment.title} · ${shipment.status.displayName}")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pending).setAutoCancel(true).build())
        check(sent.edit().putBoolean(eventKey, true).commit())
    }
    fun account(account: Account, message: String) {
        if (!allowed()) return
        val activity = if (account.platform == Platform.XIAOMI) XiaomiAccountActivity::class.java else PlatformBrowserActivity::class.java
        val pending = PendingIntent.getActivity(context, account.id.hashCode(), Intent(context, activity).putExtra("accountId", account.id).putExtra("mode", "login"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(account.id.hashCode(), NotificationCompat.Builder(context, "accounts").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("${account.platform.displayName} · ${account.label}").setContentText(message).setContentIntent(pending).setAutoCancel(true).build())
    }
    fun clearAccount(account: Account) { manager.cancel(account.id.hashCode()) }
    fun report(text: String) {
        if (!allowed()) return
        val pending = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).putExtra("destination", "ASSISTANT"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(100, NotificationCompat.Builder(context, "reports").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("快递日报")
            .setContentText(text.take(150)).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pending).setAutoCancel(true).build())
    }
}
