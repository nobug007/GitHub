package com.sf.sfw

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Ported from SFD's SfdRestartReceiver: restarts the telemetry loop on boot,
// package update, or the service's own restart alarm — only when provisioned.
class SfwRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val store = SfwStore(context)
        if (!store.isConfigured()) return
        runCatching {
            context.startForegroundService(Intent(context, SfwTelemetryService::class.java))
            store.appendLog("Telemetry service restart requested: ${intent?.action ?: "unknown"}")
        }.onFailure {
            store.appendLog("Telemetry restart failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }
}
