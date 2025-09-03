package com.example.amongchat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Space
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.amongchat.UnifiedChatService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var chatService: UnifiedChatService
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }
        if (denied.isNotEmpty()) {
            Toast.makeText(this, "Permissions denied: $denied", Toast.LENGTH_LONG).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
            pendingBtAction?.invoke()
            pendingBtAction = null
        } else {
            Toast.makeText(this, "Bluetooth required to continue", Toast.LENGTH_LONG).show()
        }
    }

    // launcher to enable discoverable mode
    private val enableDiscoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_CANCELED) {
            Toast.makeText(this, "Discoverable mode denied, cannot host", Toast.LENGTH_LONG).show()
        } else {
            val duration = result.resultCode // discoverable seconds
            Toast.makeText(this, "Device discoverable for $duration seconds", Toast.LENGTH_SHORT).show()
            chatService?.startClassicHost()
        }
    }

    private var pendingBtAction: (() -> Unit)? = null

    private fun ensureBluetoothEnabled(then: () -> Unit) {
        if (bluetoothAdapter?.isEnabled == true) {
            then()
        } else {
            pendingBtAction = then
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableIntent)
        }
    }

    // Discovery state
    var discoveredDevices = mutableStateListOf<BluetoothDevice>()
    private var isScanning by mutableStateOf(false)

    @SuppressLint("MissingPermission")
    val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i("AmongChat", "ON RECEIVE??")
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

                    Log.i("AmongChat", "Discovered: ${device?.name} address - ${device?.address}")

                    device?.let {
                        // update your device list immediately
                        if (!discoveredDevices.any { d -> d.address == it.address }) {
                            discoveredDevices.add(it) // ✅ direct mutation, Compose will recompose
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Toast.makeText(context, "Scan finished", Toast.LENGTH_SHORT).show()
                    isScanning = false
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false) // ✅ important

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        chatService = UnifiedChatService(this, bluetoothAdapter)

        // Register receiver for discovery
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(discoveryReceiver, filter)

        requestBtPermissions()

        setContent {
            ChatScreen(
                bluetoothAdapter,
                chatService = chatService,
                discoveredDevices = discoveredDevices,
                onDisconnectClient = {
                     discoveredDevices.clear()
                },
                isScanning = isScanning,
                onStartScan = { startDiscovery() },
                onStopScan = { stopDiscovery() },
                onConnectToDevice = { device -> connectToHost(device) },
                ensureBluetoothEnabled = { action -> ensureBluetoothEnabled(action) },
                onEnableDiscoverable = {
                    val discoverableIntent =
                        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300) // 5 min
                        }
                    enableDiscoverableLauncher.launch(discoverableIntent)
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(discoveryReceiver)
        chatService.close()
    }

    private fun requestBtPermissions() {
        val perms = mutableListOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.addAll(
                listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            )
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        discoveredDevices.clear()
        isScanning = true
        bluetoothAdapter?.let {
            if (it.isDiscovering) it.cancelDiscovery()
            it.startDiscovery()
            Toast.makeText(this, "Scanning for hosts…", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopDiscovery() {
        bluetoothAdapter?.let {
            if (it.isDiscovering) {
                it.cancelDiscovery()
                isScanning = false
                Toast.makeText(this, "Scan stopped", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToHost(device: BluetoothDevice) {
        bluetoothAdapter?.cancelDiscovery()
        chatService.connectTo(device)
        Toast.makeText(this, "Connecting to ${device.name ?: device.address}", Toast.LENGTH_SHORT).show()
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    bluetoothAdapter: BluetoothAdapter,
    chatService: UnifiedChatService,
    discoveredDevices: List<BluetoothDevice>,
    onDisconnectClient: (() -> Unit),
    isScanning: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectToDevice: (BluetoothDevice) -> Unit,
    ensureBluetoothEnabled: ((() -> Unit) -> Unit),
    onEnableDiscoverable: (() -> Unit) // 🔥 new
) {
    val messages = chatService.messages
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isHostMode by remember { mutableStateOf(false) }
    var isClientJoinMode by remember { mutableStateOf(false) }
    var connectedHost by remember { mutableStateOf<String>("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        // --- Top row: Host, Join, Mesh ---
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Host
                if(!isClientJoinMode) {
                    if (!chatService.isHostingClassic.value) {
                        Button(
                            modifier = Modifier
                                .weight(1f)
                                .padding(4.dp),
                            onClick = {
                                isHostMode = true
                                ensureBluetoothEnabled {
                                    onEnableDiscoverable()
                                    chatService.startClassicHost()
                                }
                            }
                        ) {
                            Text("Host Classic", textAlign = TextAlign.Center)
                        }
                    } else {
                        Button(
                            modifier = Modifier
                                .weight(1f)
                                .padding(4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Red
                            ),
                            onClick = {
                                isHostMode = false
                                chatService.stopClassicHost()
                                val cancelled = forceCancelDiscoverable(bluetoothAdapter)
                                Toast.makeText(
                                    context,
                                    if (cancelled) "Discoverable cancelled" else "Failed to cancel discoverable",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        ) { Text("Cancel Host", textAlign = TextAlign.Center) }
                    }
                }

                // Join
                if (!isHostMode) {
                    if (!isClientJoinMode) {
                        Button(
                            modifier = Modifier
                                .weight(1f)
                                .padding(4.dp),
                            onClick = {
                                isClientJoinMode = true
                            }
                        ) {
                            Text("Join Classic", textAlign = TextAlign.Center)
                        }
                    } else {
                        Button(
                            modifier = Modifier
                                .weight(1f)
                                .padding(4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Red
                            ),
                            onClick = { isClientJoinMode = false }
                        ) {
                            Text("Cancel Join", textAlign = TextAlign.Center)
                        }
                    }
                }


                // Mesh
                if(!isHostMode){
                    if (!chatService.isMeshRunning.value) {
                        Button(
                            modifier = Modifier
                                .weight(1f)
                                .padding(4.dp),
                            onClick = { chatService.startBleMesh() }
                        ) {
                            Text("Start Mesh", textAlign = TextAlign.Center)
                        }
                    } else {
                        Button(
                            modifier = Modifier
                                .weight(1f)
                                .padding(4.dp),
                            onClick = { chatService.stopBleMesh() }
                        ) {
                            Text("Stop Mesh", textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(6.dp))
        
        if (!chatService.isConnectedClassic.value && isClientJoinMode){
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    ensureBluetoothEnabled {
                        if (!isScanning) onStartScan() else onStopScan()
                    }
                }) {
                Text(text = if (!isScanning) "Scan for hosts" else "Scanning...")
            }
        } else if (chatService.isConnectedClassic.value && isClientJoinMode) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Red
                ),
                onClick = {
                    chatService.stopClassicClient()
                    onDisconnectClient()
                    connectedHost = ""
                }) {
                Text(text = "Disconnect")
            }
        }
        

        Spacer(Modifier.height(16.dp))

        if(isScanning){
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(20.dp)     // small size
                        .padding(end = 8.dp),
                    strokeWidth = 2.dp
                )
                Text("Scanning for devices...")
            }
        }

        if(isHostMode){
            Text(text = "Connected Devices", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }

        if(isClientJoinMode && connectedHost == ""){
            Text(text = "List of Available Hosts", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        
        if(isClientJoinMode && connectedHost != "") {
            Text(text = "Connected with Host: $connectedHost", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        
        // --- Devices list (full-width buttons) ---
        LazyColumn(modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)) {
            // Discovered devices (Join mode)
            if (!chatService.isConnectedClassic.value) {
                items(discoveredDevices) { device ->
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        onClick = { onConnectToDevice(device); connectedHost = device.name }
                    ) {
                        Text(device.name ?: device.address, textAlign = TextAlign.Center)
                    }
                }
            }
            // Connected clients (Host mode)
            if (chatService.isHostingClassic.value) {

                items(chatService.getAllConnectedClassicClients()) { client ->
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        onClick = { /* maybe kick client? */ }
                    ) {
                        Text(client, textAlign = TextAlign.Center)
                    }
                }
            }
            // Mesh peers
            if (chatService.isMeshRunning.value) {
                items(chatService.getPeers()) { peer ->
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        onClick = { /* maybe show peer details */ }
                    ) {
                        Text(peer, textAlign = TextAlign.Center)
                    }
                }
            }
        }

        Spacer(Modifier.height(2.dp))

        // --- Messages ---
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            items(messages) { msg ->
                Text("[${msg.sourceTransport}] ${msg.nickname}: ${msg.text}")
            }
        }

        // --- Input ---
        Row(modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(bottom = 4.dp)) {
            TextField(
                modifier = Modifier.weight(1f),
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Type a message") }
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                scope.launch {
                    if (input.isNotBlank()) {
                        chatService.broadcastMessage(input)
                        input = ""
                    }
                }
            }) {
                Text("Send")
            }
        }
    }
}

@SuppressLint("MissingPermission", "DiscouragedPrivateApi")
fun forceCancelDiscoverable(bluetoothAdapter: BluetoothAdapter): Boolean {
    return try {
        val method = BluetoothAdapter::class.java.getMethod(
            "setScanMode",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        // SCAN_MODE_CONNECTABLE = 21 (device visible to paired devices only)
        // duration = 0
        method.invoke(bluetoothAdapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE, 0)
        true
    } catch (e: Exception) {
        e.printStackTrace()
        try {
            bluetoothAdapter.disable()
            bluetoothAdapter.enable()
            true
        }catch(e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
