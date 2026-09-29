package com.sf.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.util.Log

/**
 * Wraps BLE peripheral advertising for the hotspot-trigger beacon. The tablet
 * advertises the shared service UUID; the phone's hotSpot app scans for it and
 * turns Mobile Hotspot on/off based on presence.
 */
class BleAdvertiser(private val context: Context) {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertising = false

    var onState: ((advertising: Boolean, message: String) -> Unit)? = null

    fun isAdvertising(): Boolean = advertising

    @SuppressLint("MissingPermission")
    fun start() {
        if (advertising) return
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            onState?.invoke(false, "블루투스가 꺼져 있습니다. 켠 뒤 다시 시도하세요.")
            return
        }
        if (!adapter.isMultipleAdvertisementSupported) {
            onState?.invoke(false, "이 기기는 BLE 광고를 지원하지 않습니다.")
            return
        }
        val le = adapter.bluetoothLeAdvertiser
        if (le == null) {
            onState?.invoke(false, "BLE 광고자를 가져올 수 없습니다.")
            return
        }
        advertiser = le

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        // Only the 128-bit service UUID (16B + 2B header) plus flags fit in the
        // 31-byte legacy PDU, so no device name is included here.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(BleConstants.HOTSPOT_TRIGGER_PARCEL_UUID)
            .build()

        runCatching { le.startAdvertising(settings, data, advertiseCallback) }
            .onFailure { onState?.invoke(false, "광고 시작 실패: ${it.message ?: it.javaClass.simpleName}") }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!advertising && advertiser == null) {
            onState?.invoke(false, "광고 중이 아닙니다.")
            return
        }
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
        advertiser = null
        onState?.invoke(false, "BLE 광고를 중지했습니다.")
        Log.d(TAG, "advertising stopped")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            onState?.invoke(true, "BLE 광고 중 (UUID …${BleConstants.HOTSPOT_TRIGGER_UUID.takeLast(8)})")
            Log.d(TAG, "advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            advertising = false
            val reason = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "광고 데이터가 너무 큽니다"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "광고자가 너무 많습니다"
                ADVERTISE_FAILED_ALREADY_STARTED -> "이미 광고 중"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "내부 오류"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "지원되지 않는 기능"
                else -> "알 수 없는 오류($errorCode)"
            }
            onState?.invoke(false, "광고 시작 실패: $reason")
            Log.w(TAG, "advertising failed: $reason")
        }
    }

    companion object {
        private const val TAG = "BleAdvertiser"
    }
}
