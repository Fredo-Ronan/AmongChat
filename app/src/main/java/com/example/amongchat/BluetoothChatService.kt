package com.example.amongchat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import java.util.*
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.json.*

private const val TAG = "AmongChat"

class BluetoothChatService(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val onMessage: (from: String, text: String) -> Unit
) {
    companion object {
        val APP_UUID: UUID = UUID.fromString("3a233f2b-7e65-4e8d-8eaf-2f2e0cd08a1d")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var serverThread: ServerThread? = null
    private val clientThreads = CopyOnWriteArrayList<ClientConn>()

    // Public accessible variables
    val connectedClients = mutableStateListOf<String>()

    fun startServer() {
        stopAll()
        serverThread = ServerThread().also { it.start() }
    }

    @SuppressLint("MissingPermission")
    fun connectTo(device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
        thread(name = "bt-classic-client-connector") {
            try {
                adapter.cancelDiscovery()
                socket.connect()
                val conn = ClientConn(socket)
                clientThreads.add(conn)
                conn.start()
                Log.i(TAG, "[Bluetooth Classic] - Connected to ${device.address}")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Connected to ${device.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                Log.e(TAG, "[Bluetooth Classic] - Connect failed", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "FAILED to connect", Toast.LENGTH_SHORT).show()
                }
                try { socket.close() } catch (_: IOException) {}
            }
        }
    }

    /**
     * Broadcast message with sender info preserved
     */
    fun broadcastMessage(from: String, text: String, exclude: String? = null) {
        val json = JSONObject()
            .put("from", from)
            .put("text", text)
            .toString()

        val payload = (json + "\n").toByteArray(Charsets.UTF_8)

        serverThread?.broadcast(payload, exclude)
        clientThreads.forEach { it.send(payload) }
    }

    fun stopAll() {
        serverThread?.cancel(); serverThread = null
        clientThreads.forEach { it.cancel() }; clientThreads.clear()
    }

    // ------- Server (Host) -------
    @SuppressLint("MissingPermission")
    private inner class ServerThread : Thread("bt-classic-server") {
        private var serverSocket: BluetoothServerSocket? = null
        private val connections = CopyOnWriteArrayList<ClientConn>()

        init {
            run {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED
                ) return@run
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("AmongChat", APP_UUID)
            }
        }

        override fun run() {
            Log.i(TAG, "[Bluetooth Classic] - Server listening....")
            while (!isInterrupted) {
                try {
                    val sock = serverSocket?.accept() ?: break
                    val conn = ClientConn(sock, onClosed = {
                        connections.remove(it)
                        val name = sock.remoteDevice.name ?: sock.remoteDevice.address
                        mainHandler.post {
                            connectedClients.remove(name)
                            Log.i(TAG, "[Bluetooth Classic] - Client disconnected $name")
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(context, "Disconnected from $name", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { from, msg ->
                        // Forward incoming messages to UnifiedChatService (UI)
                        onMessage(from, msg)
                        Log.i(TAG, "Got Message from: $from - $msg -> going to broadcast to others")
                        // ✅ Re-broadcast raw payload to other clients (exclude sender)
                        val payload = (msg + "\n").toByteArray(Charsets.UTF_8)
                        broadcastToOthers(payload, exclude = sock.remoteDevice.address)
                    }
                    connections.add(conn)
                    conn.start()
                    Log.i(TAG, "[Bluetooth Classic] - Client connected ${sock.remoteDevice.address}")
                    connectedClients.add(sock.remoteDevice.name)
                    Log.i(TAG, "Devices $connectedClients")
                } catch (e: IOException) {
                    Log.e(TAG, "accept() failed", e); break
                }
            }
        }

        fun broadcastToOthers(payload: ByteArray, exclude: String) {
            connections.forEach { conn ->
                if (conn.socket.remoteDevice.address != exclude) {
                    conn.send(payload)
                }
            }
        }

        fun broadcast(bytes: ByteArray, exclude: String? = null) {
            connections.forEach { conn ->
                val addr = conn.socket.remoteDevice.address
                if (exclude == null || addr != exclude) {
                    conn.send(bytes)
                }
            }
        }

        fun cancel() {
            try { serverSocket?.close() } catch (_: IOException) {}
            connections.forEach { it.cancel() }
            connections.clear()
            interrupt()
        }
    }

    // ------- ClientConn -------
    @SuppressLint("MissingPermission")
    private inner class ClientConn(
        val socket: BluetoothSocket,
        private val onClosed: (ClientConn) -> Unit = {},
        private val onMessageReceived: (from: String, text: String) -> Unit = { _, _ -> }
    ) : Thread("bt-classic-conn-${socket.remoteDevice.address}") {
        private val input: InputStream = socket.inputStream
        private val output: OutputStream = socket.outputStream
        @Volatile private var running = true

        override fun run() {
            val buf = ByteArray(1024)
            while (running) {
                try {
                    val n = input.read(buf)
                    if (n == -1) break
                    val raw = String(buf, 0, n, Charsets.UTF_8).trim()
                    Log.i(TAG, "RAW: $raw")
                    if (raw.isNotEmpty()) {
                        try {
                            val obj = JSONObject(raw)
                            val from = obj.optString("from", socket.remoteDevice.name ?: socket.remoteDevice.address)
                            val text = obj.optString("text", raw)
                            onMessage(from, text)
                            onMessageReceived(from, text)
                        } catch (e: Exception) {
                            // fallback for plain text
                            val from = socket.remoteDevice.name ?: socket.remoteDevice.address
                            onMessage(from, raw)
                            onMessageReceived(from, raw)
                        }
                    }
                } catch (e: IOException) {
                    break
                }
            }
            cancel()
        }

        fun send(bytes: ByteArray) {
            try {
                output.write(bytes)
                output.flush()
            } catch (_: IOException) {
                cancel()
            }
        }

        fun cancel() {
            running = false
            try { socket.close() } catch (_: IOException) {}
            onClosed(this)
        }
    }
}
