package com.logisticsmanagement.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.logisticsmanagement.app.ui.LogisticsApp

class MainActivity : ComponentActivity() {
    private val notificationsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var shipmentId by mutableStateOf<String?>(null)
    private var destination by mutableStateOf<String?>(null)
    private var navigationRequestId by mutableStateOf(0L)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigationRequestId = savedInstanceState?.getLong("navigationRequestId") ?: 0L
        readNavigation(intent, savedInstanceState == null)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { LogisticsApp((application as LogisticsApplication).container, shipmentId, destination, navigationRequestId) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readNavigation(intent)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("navigationRequestId", navigationRequestId)
        super.onSaveInstanceState(outState)
    }
    private fun readNavigation(intent: Intent, increment: Boolean = true) {
        shipmentId = intent.getStringExtra("shipmentId")
        destination = intent.getStringExtra("destination")
        if (increment) navigationRequestId++
    }
}
