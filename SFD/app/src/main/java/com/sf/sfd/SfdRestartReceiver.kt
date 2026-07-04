package com.sf.sfd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class SfdRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val store = SfdStore(context)
        if (!store.isConfigured()) return
        val serviceIntent = Intent(context, SfdTelemetryService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        store.appendLog("Telemetry service restart requested: ${intent?.action ?: "unknown"}")
    }
}
