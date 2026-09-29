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
        // Android 12+ can reject FGS starts from the alarm-restart path; a throw here
        // would crash the receiver before promoteToForeground() gets a chance to run.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            store.appendLog("Telemetry service restart requested: ${intent?.action ?: "unknown"}")
        }.onFailure {
            store.appendLog("Telemetry restart rejected: ${it.message ?: it.javaClass.simpleName}")
        }
    }
}
