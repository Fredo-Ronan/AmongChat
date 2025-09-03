package com.example.amongchat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.os.ParcelUuid
import java.util.UUID

/**
 * Optional BLE Advertise/Scan helpers you can use for presence/peer discovery,
 * while still using Classic RFCOMM for the actual chat sockets.
 */
object BlePresence {
    val SERVICE_UUID: UUID = UUID.fromString("5a7e2f41-15d3-4f3e-b4c4-7b1e7da7e2a1")

    private var advertiser: BluetoothLeAdvertiser? = null

    @SuppressLint("MissingPermission")
    fun startAdvertising(adapter: BluetoothAdapter) {
        advertiser = adapter.bluetoothLeAdvertiser
        val setting = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(true)
            .build()

        advertiser?.startAdvertising(setting, data, object : AdvertiseCallback() {})
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        advertiser?.stopAdvertising(object : AdvertiseCallback() {})
        advertiser = null
    }

    @SuppressLint("MissingPermission")
    fun startScan(adapter: BluetoothAdapter, onPeer: (ScanResult) -> Unit) {
        val scanner = adapter.bluetoothLeScanner
        scanner.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onPeer(result)
            }
        })
    }
}