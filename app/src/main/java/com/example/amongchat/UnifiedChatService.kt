package com.example.amongchat

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.amongchat.BleMeshService
import com.example.amongchat.MeshPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.ArrayList

/**
 * UnifiedChatService: coordinates Classic RFCOMM chat (BluetoothChatService)
 * and BleMeshService. Provides a single API for UI and handles:
 *  - deduplication (UUID-based)
 *  - rebroadcast across transports with TTL
 *  - BLE fragment reassembly
 *  - message list observable by Compose
 */
class UnifiedChatService(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val nickname: String = "User-%04d".format((Math.random() * 9999).toInt()) // simple nickname
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Underlying transport services
    private val classic = BluetoothChatService(context, adapter) { from, text ->
        // when we receive on classic, treat text as a full message
        scope.launch { onClassicMessageReceived(from, text) }
    }

    private val ble = BleMeshService(context, adapter) { packet ->
        // handle mesh packet (fragment)
        scope.launch { handleBlePacket(packet) }
    }

    // Public observable message list for UI (Compose-friendly)
    val messages = mutableStateListOf<UnifiedMessage>()
    var isHostingClassic = mutableStateOf(false)
    var isMeshRunning = mutableStateOf(false)
    var isConnectedClassic = mutableStateOf(false)

    // Deduplication: bounded ordered set of seen UUIDs
    private val seenIds = LinkedHashSet<UUID>()
    private val seenMutex = Mutex()
    private val SEEN_MAX = 2000

    // Reassembly map for BLE fragments
    private val reassembly = ConcurrentHashMap<UUID, FragmentReassembler>()

    // Default TTL for rebroadcasting across transports
    private val DEFAULT_TTL = 3

    // small random jitter range (ms) before rebroadcasting
    private val REBROADCAST_MIN_JITTER = 20L
    private val REBROADCAST_MAX_JITTER = 200L

    // ----- Public API -----

    fun startBleMesh() {
        isMeshRunning.value = true
        ble.start() // start BLE scanning
    }

    fun startClassicHost() {
        isHostingClassic.value = true
        classic.startServer() // starts RFCOMM server to accept clients
    }

    fun stopClassicHost() {
        classic.stopAll() // stop RFCOMM server
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "Disconnected from host", Toast.LENGTH_SHORT).show()
        }
        isHostingClassic.value = false
    }

    fun stopClassicClient() {
        classic.stopAll()
        isConnectedClassic.value = false
    }

    fun stopBleMesh() {
        ble.stop() // stop BLE Mesh
        isMeshRunning.value = false
    }

    fun close() {
        scope.cancel()
        stopAll()
    }

    fun getAllConnectedClassicClients(): MutableList<String> {
        return classic.connectedClients
    }

    fun getPeers(): SnapshotStateList<String> {
        return ble.peers
    }

    suspend fun broadcastMessage(text: String, preferClassicWhenAvailable: Boolean = true) {
        // Create a messageId
        val messageId = UUID.randomUUID()
        val u = UnifiedMessage(
            messageId = messageId,
            text = text,
            senderId = adapter.address ?: "unknown",
            nickname = nickname,
            sourceTransport = Transport.LOCAL_SENT,
            receivedAt = System.currentTimeMillis()
        )
        // add locally (so sender sees their own message immediately)
        pushMessage(u)

        // Send on Classic immediately (best-effort)
        try {
            classic.broadcast(text)
        } catch (e: Exception) { Log.w(TAG, "classic send failed", e) }

        // Also flood via BLE mesh (allow other non-classic peers to receive)
        try {
            ble.sendText(adapter.address ?: "unknown", text, DEFAULT_TTL)
        } catch (e: Exception) { Log.w(TAG, "ble send failed", e) }

        // Mark messageId as seen so it won't be reprocessed when it loops back
        markSeen(messageId)
    }

    fun connectTo(device: BluetoothDevice) {
        classic.connectTo(device)
        isConnectedClassic.value = true
    }

    // ----- Internal handling -----

    private fun stopAll() {
        classic.stopAll()
        ble.stop()
        isMeshRunning.value = false
        isHostingClassic.value = false
    }

    private suspend fun onClassicMessageReceived(from: String, text: String) {
        // Classic messages arrive as plain text. We attach a synthetic messageId (hash based) to dedupe
        val messageId = uuidFromText(from, text)
        if (isSeen(messageId)) return
        markSeen(messageId)

        val u = UnifiedMessage(
            messageId = messageId,
            text = text,
            senderId = from,
            nickname = from, // Classic doesn't provide nickname; using address as fallback
            sourceTransport = Transport.CLASSIC,
            receivedAt = System.currentTimeMillis()
        )
        pushMessage(u)

        // Rebroadcast to BLE if TTL allows
        rebroadcastToBle(text, ttl = DEFAULT_TTL)
    }

    private suspend fun handleBlePacket(packet: MeshPacket) {
        // Use packet.messageId for dedupe
        val messageId = packet.messageId
        // get or create reassembler
        val r = reassembly.computeIfAbsent(messageId) { FragmentReassembler(messageId) }
        r.addFragment(packet.seq.toInt(), packet.payloadChunk, packet.isLast)
        if (r.isComplete()) {
            val text = r.assemble()
            reassembly.remove(messageId)

            if (isSeen(messageId)) return
            markSeen(messageId)

            val u = UnifiedMessage(
                messageId = messageId,
                text = text,
                senderId = packet.originId,
                nickname = packet.originId,
                sourceTransport = Transport.BLE,
                receivedAt = System.currentTimeMillis()
            )
            pushMessage(u)

            // Rebroadcast to Classic if TTL > 0
            if (packet.ttl > 0) {
                rebroadcastToClassic(text)
                // Also rebroadcast further on BLE through ble service; the BleMeshService already
                // re-broadcasts fragments with decremented TTL when scanning, so no need to call sendText here.
            }
        }
    }

    // Send text to Classic peers (server will push to connected clients)
    private fun rebroadcastToClassic(text: String) {
        scope.launch {
            try {
                // small jitter
                delay(randomJitter())
                classic.broadcast(text)
            } catch (e: Exception) { Log.w(TAG, "rebroadcast classic fail", e) }
        }
    }

    // Send text over BLE mesh (create fragments with messageId)
    private fun rebroadcastToBle(text: String, ttl: Int) {
        scope.launch {
            try {
                delay(randomJitter())
                ble.sendText(adapter.address ?: "unknown", text, ttl)
            } catch (e: Exception) { Log.w(TAG, "rebroadcast ble fail", e) }
        }
    }

    // ----- Helpers -----

    private fun pushMessage(m: UnifiedMessage) {
        // UI is Compose: add message to state list on main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            messages.add(m)
        } else {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.post { messages.add(m) }
        }
    }

    private suspend fun markSeen(id: UUID) {
        seenMutex.withLock {
            seenIds.add(id)
            while (seenIds.size > SEEN_MAX) {
                val iter = seenIds.iterator()
                if (!iter.hasNext()) break
                iter.next()
                iter.remove()
            }
        }
    }

    private suspend fun isSeen(id: UUID): Boolean {
        return seenMutex.withLock { seenIds.contains(id) }
    }

    private fun uuidFromText(from: String, text: String): UUID {
        // deterministic UUID so identical classic messages produce same id across nodes
        val src = (from + "::" + text).toByteArray(Charsets.UTF_8)
        return UUID.nameUUIDFromBytes(src)
    }

    private fun randomJitter() = (REBROADCAST_MIN_JITTER..REBROADCAST_MAX_JITTER).random()

    // ----- small fragment reassembler for BLE -----
    private class FragmentReassembler(private val id: UUID) {
        private val parts = TreeMap<Int, ByteArray>()
        @Volatile private var lastSeq: Int = -1
        @Volatile private var gotLast = false

        fun addFragment(seq: Int, data: ByteArray, isLast: Boolean) {
            parts[seq] = data
            if (isLast) { lastSeq = seq; gotLast = true }
        }

        fun isComplete(): Boolean {
            return gotLast && parts.size == (lastSeq + 1)
        }

        fun assemble(): String {
            val out = ArrayList<ByteArray>(parts.size)
            for (i in 0..lastSeq) out.add(parts[i] ?: ByteArray(0))
            val total = out.sumOf { it.size }
            val bb = ByteArray(total)
            var off = 0
            for (b in out) {
                System.arraycopy(b, 0, bb, off, b.size)
                off += b.size
            }
            return String(bb, Charsets.UTF_8)
        }
    }

    companion object {
        private const val TAG = "UnifiedChatService"
    }
}