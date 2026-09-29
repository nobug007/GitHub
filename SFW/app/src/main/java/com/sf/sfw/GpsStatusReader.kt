package com.sf.sfw

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

// Ported from SFD's GpsStatusReader (minSdk 30, so the pre-M permission branch is gone).
class GpsStatusReader(private val context: Context) {
    private var lastObservedLocation: Location? = null
    private var updatesRequested = false

    @SuppressLint("MissingPermission")
    fun read(): GpsStatus {
        if (!hasLocationPermission()) return GpsStatus(null, null, null, null)
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        requestLocationUpdates(manager)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val lastKnown = providers
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull(Location::getTime)
        // Only trust a fix recent enough; a stale getLastKnownLocation() (e.g. the home coordinate
        // cached at registration) must not be reported as the current position after moving.
        val now = System.currentTimeMillis()
        val location = listOfNotNull(lastObservedLocation, lastKnown)
            .filter { now - it.time <= SfwConfig.GPS_MAX_FIX_AGE_MS }
            .maxByOrNull(Location::getTime)
        return GpsStatus(
            latitude = location?.latitude,
            longitude = location?.longitude,
            accuracy = location?.accuracy,
            timestampMs = location?.time
        )
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates(manager: LocationManager) {
        if (updatesRequested) return
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastObservedLocation = location
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
            .forEach { provider ->
                runCatching {
                    manager.requestLocationUpdates(
                        provider,
                        10_000L,
                        0f,
                        listener,
                        Looper.getMainLooper()
                    )
                    updatesRequested = true
                }
            }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }
}
