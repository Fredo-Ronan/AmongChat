package com.example.amongchat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.*
import kotlin.random.Random

/**
 * BLE flooding mesh service. Uses Advertise service data and Scan to exchange small fragments.
 * Conservative payload sizing and simple deduplication.
 */
class BleMeshService(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val onPacketReceived: (MeshPacket) -> Unit
) {
    private val advertiser: BluetoothLeAdvertiser? = adapter.bluetoothLeAdvertiser
    private val scanner by lazy { adapter.bluetoothLeScanner }
    private val seen = LinkedHashSet<UUID>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val peers = mutableStateListOf<String>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getServiceData(ParcelUuid(MeshConstants.SERVICE_UUID)) ?: return
            try {
                val packet = decodePacket(data)

                // Deduplication by messageId
                synchronized(seen) {
                    if (seen.contains(packet.messageId)) return
                    seen.add(packet.messageId)
                    if (seen.size > 500) { // bounded memory
                        val it = seen.iterator(); it.next(); it.remove()
                    }
                }

                // Track peers
                var peerId = packet.originId
                if(!peers.contains(peerId)) {
                    peers.add(peerId)
                    Toast.makeText(context, "New Peer Discovered : $peerId", Toast.LENGTH_SHORT).show()
                }

                // Deliver packet to app
                onPacketReceived(packet)

                // Re-broadcast if TTL > 0
                if (packet.ttl > 0) {
                    scope.launch {
                        delay(Random.nextLong(20L, 250L))
                        advertiseFragment(packet.copy(ttl = packet.ttl - 1))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "decode fail", e)
            }
        }

        override fun onScanFailed(errorCode: Int) { Log.w(TAG, "scan fail $errorCode") }
    }

    fun start() {
        try { startScan() } catch (e: Exception) { Log.w(TAG, "start scan fail", e) }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try { scanner.stopScan(scanCallback) } catch (e: Exception) { }
        peers.clear()
        scope.cancel()
    }

    fun sendText(originId: String, text: String, initialTtl: Int = 3) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val messageId = UUID.randomUUID()
        val chunkSize = MeshConstants.MAX_ADV_PAYLOAD - 16 - 1 - 8 // reserve bytes for headers
        var seq = 0L
        var offset = 0
        while (offset < bytes.size) {
            val end = (offset + chunkSize).coerceAtMost(bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            val packet = MeshPacket(messageId, originId, seq, initialTtl, chunk, isLast = end == bytes.size)
            advertiseFragment(packet)
            seq++
            offset = end
        }
    }

    @SuppressLint("MissingPermission")
    private fun advertiseFragment(packet: MeshPacket) {
        val data = encodePacket(packet)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()
        val advData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(MeshConstants.SERVICE_UUID))
            .addServiceData(ParcelUuid(MeshConstants.SERVICE_UUID), data)
            .setIncludeDeviceName(false)
            .build()

        advertiser?.startAdvertising(settings, advData, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                // schedule stop to conserve radio resources
                scope.launch {
                    delay(120L)
                    try { advertiser?.stopAdvertising(object : AdvertiseCallback() {  }) } catch (_: Exception) { }
                }
            }

            override fun onStartFailure(errorCode: Int) { Log.w(TAG, "adv fail $errorCode") }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(MeshConstants.SERVICE_UUID)).build())
        val opts = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, opts, scanCallback)
    }

    private fun encodePacket(p: MeshPacket): ByteArray {
        // format: [16 bytes UUID][1 byte ttl][8 bytes seq][1 byte flags][payload]
        val bb = ByteBuffer.allocate(16 + 1 + 8 + 1 + p.payloadChunk.size)
        bb.putLong(p.messageId.mostSignificantBits)
        bb.putLong(p.messageId.leastSignificantBits)
        bb.put(p.ttl.toByte())
        bb.putLong(p.seq)
        bb.put(if (p.isLast) 1 else 0)
        bb.put(p.payloadChunk)
        return bb.array()
    }

    private fun decodePacket(bytes: ByteArray): MeshPacket {
        val bb = ByteBuffer.wrap(bytes)
        val hi = bb.long
        val lo = bb.long
        val msgId = UUID(hi, lo)
        val ttl = bb.get().toInt()
        val seq = bb.long
        val isLast = bb.get().toInt() == 1
        val payload = ByteArray(bb.remaining())
        bb.get(payload)
        return MeshPacket(msgId, adapter.address ?: "unknown", seq, ttl, payload, isLast)
    }

    companion object { private const val TAG = "BleMeshService" }
}