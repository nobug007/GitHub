package com.sf.sfc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Tiny always-on HTTP server so devices on the guardian's Mobile Hotspot (SFA / SFD / SFW) can
 * fetch the phone's current time + GPS location:
 *
 *   GET /gps  ->  {"time":"...","lat":37.44,"lng":126.89,"accuracy":12.3,"fixAgeMs":8412,"provider":"gps"}
 *
 * When a device is on the hotspot it is physically next to the phone, so the phone's fix is the
 * device's location — this lets a headless device (SFA) report a position without its own GPS, which
 * is the only way it gets coordinates indoors where GNSS has no sky.
 *
 * The server keeps its own location updates running. Reading getLastKnownLocation() alone was not
 * enough: that returns whatever fix the phone happened to take last, and on a phone where nothing
 * else is using GPS it simply ages. SFA was observed rejecting fixes 14 minutes old — correctly, but
 * it meant the whole path delivered nothing. Subscribing keeps a recent fix on hand and lets /gps
 * answer immediately, which matters because the caller's HTTP timeout is only a few seconds.
 *
 * Runs in the always-on foreground monitor service; binds on all interfaces so a hotspot client can
 * reach it at the phone's gateway IP.
 */
class GpsHttpServer(private val context: Context, private val port: Int = PORT) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var server: ServerSocket? = null

    /** Newest fix delivered by our own subscription. */
    @Volatile private var live: Location? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val cur = live
            if (cur == null || location.time >= cur.time) live = location
        }
        // Required on older platform versions, where these were abstract.
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun start() {
        if (running) return
        running = true
        startUpdates()
        thread = Thread {
            runCatching {
                server = ServerSocket(port)
                Log.d(TAG, "GPS HTTP server listening on :$port")
                while (running) {
                    val sock = runCatching { server?.accept() }.getOrNull() ?: continue
                    runCatching { handle(sock) }
                    runCatching { sock.close() }
                }
            }.onFailure { if (running) Log.w(TAG, "server error: ${it.message}") }
        }.apply { isDaemon = true; name = "sfc-gps-http"; start() }
    }

    fun stop() {
        running = false
        stopUpdates()
        runCatching { server?.close() }
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "no location permission — /gps can only serve whatever fix already exists")
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        // Both providers: GPS is accurate outdoors, network positioning still answers indoors where
        // GPS cannot. Whichever reports a newer fix wins.
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(
                        provider, UPDATE_INTERVAL_MS, UPDATE_DISTANCE_M, listener, Looper.getMainLooper()
                    )
                    Log.d(TAG, "location updates requested from $provider")
                }
            }.onFailure { Log.w(TAG, "requestLocationUpdates($provider): ${it.message}") }
        }
    }

    private fun stopUpdates() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        runCatching { lm.removeUpdates(listener) }
    }

    private fun handle(sock: Socket) {
        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
        val requestLine = reader.readLine() ?: return          // e.g. "GET /gps HTTP/1.1"
        val out = sock.getOutputStream()
        if (requestLine.startsWith("GET /gps")) {
            val loc = bestLocation()
            val time = ISO.format(Instant.now())
            // "time" is when this reply was written, NOT when the fix was taken. A caller reading
            // only that would treat an hours-old position as current and send a searcher to the
            // wrong place, so the fix's own age travels with it and the caller decides.
            val body = if (loc != null) {
                val ageMs = (System.currentTimeMillis() - loc.time).coerceAtLeast(0L)
                val provider = loc.provider ?: "unknown"
                """{"time":"$time","lat":${loc.latitude},"lng":${loc.longitude},""" +
                    """"accuracy":${loc.accuracy},"fixAgeMs":$ageMs,"provider":"$provider"}"""
            } else {
                """{"time":"$time","lat":null,"lng":null}"""
            }
            write(out, 200, "OK", body)
        } else {
            write(out, 404, "Not Found", """{"error":"not found"}""")
        }
    }

    /** Newest of our subscription's fix and each provider's last known one. */
    @SuppressLint("MissingPermission")
    private fun bestLocation(): Location? {
        if (!hasLocationPermission()) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val candidates = mutableListOf<Location>()
        live?.let { candidates += it }
        if (lm != null) {
            for (provider in listOf(
                LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER
            )) {
                runCatching { lm.getLastKnownLocation(provider) }.getOrNull()?.let { candidates += it }
            }
        }
        return candidates.maxByOrNull { it.time }
    }

    private fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun write(out: OutputStream, code: Int, reason: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n" +
            "Content-Length: ${bytes.size}\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    companion object {
        const val PORT = 8765
        private const val TAG = "GpsHttpServer"
        // Fast enough that a fix is never stale by the time a device asks (SFA polls every 60 s and
        // discards anything older than 5 minutes), slow enough not to sit on the GPS continuously.
        private const val UPDATE_INTERVAL_MS = 60_000L
        private const val UPDATE_DISTANCE_M = 10f
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(ZoneOffset.ofHours(9))
    }
}
