package com.logisticsmanagement.app.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

internal object PhoneNumbers {
    fun ownNumbers(context: Context): List<String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val subscriptions = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return emptyList()
        val dataSubscription = SubscriptionManager.getDefaultDataSubscriptionId()
        val ordered = subscriptions.activeSubscriptionInfoList.orEmpty()
            .sortedByDescending { it.subscriptionId == dataSubscription }
        return ordered.mapNotNull { info ->
            @Suppress("DEPRECATION")
            val line = telephony.createForSubscriptionId(info.subscriptionId).line1Number
            line?.filter(Char::isDigit)?.removePrefix("86")?.takeIf { it.length == 11 }
        }.distinct()
    }

    fun mask(number: String): String = if (number.length < 7) "***" else number.take(3) + "****" + number.takeLast(4)
}
