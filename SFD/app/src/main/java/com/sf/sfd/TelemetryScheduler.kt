package com.sf.sfd

class TelemetryScheduler(
    private val store: SfdStore,
    private val apiClient: SfdApiClient,
    private val onStatus: (String) -> Unit
) {
    fun start() {
        onStatus("Telemetry is handled by SfdTelemetryService")
    }

    fun stop() = Unit

    fun sendOnce() {
        onStatus("Manual telemetry send is disabled; periodic service reporting is active")
    }
}
