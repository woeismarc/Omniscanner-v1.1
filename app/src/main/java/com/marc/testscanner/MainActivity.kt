package com.marc.testscanner

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanResult as BleScanResult
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.ScanResult as WifiScanResult
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.marc.testscanner.ui.theme.TestScannerTheme
import kotlinx.coroutines.*
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.pow
import kotlin.random.Random

data class ScanEntry(
    val type: String,
    val name: String,
    val address: String,
    val signal: String = "",
    val extra: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val detailedData: Map<String, String> = emptyMap()
)

class MainActivity : ComponentActivity() {

    private var wifiManager: WifiManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var nfcAdapter: NfcAdapter? = null
    private var irManager: ConsumerIrManager? = null
    private var connectivityManager: ConnectivityManager? = null
    private lateinit var macResolver: MacResolver

    private val _scanResults = mutableStateListOf<ScanEntry>()
    private val _history = mutableStateListOf<ScanEntry>()
    private val isScanning = mutableStateOf(false)
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @Suppress("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (BluetoothDevice.ACTION_FOUND == intent?.action) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                
                val rssi: Short = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                
                device?.let { dev ->
                    val distance = if (rssi != Short.MIN_VALUE) calculateDistance(rssi.toInt()) else "Unknown"
                    val manufacturer = macResolver.resolveMacBlocking(dev.address)
                    val name = try { dev.name ?: "Unknown Device" } catch (e: SecurityException) { "Restricted" }
                    val details = mutableMapOf(
                        "Hardware ID" to dev.address,
                        "Manufacturer" to manufacturer,
                        "Est. Distance" to "$distance ft",
                        "Bond State" to when(dev.bondState) {
                            BluetoothDevice.BOND_BONDED -> "Bonded"
                            BluetoothDevice.BOND_BONDING -> "Bonding"
                            else -> "Not Bonded"
                        },
                        "Type" to when(dev.type) {
                            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
                            BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
                            BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
                            else -> "Unknown"
                        },
                        "Signal" to if (rssi != Short.MIN_VALUE) "$rssi dBm" else "N/A"
                    )
                    addResult(ScanEntry("Bluetooth", name, dev.address, signal = if (rssi != Short.MIN_VALUE) "$rssi dBm" else "", detailedData = details))
                }
            }
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: BleScanResult) {
            val device = result.device
            val rssi = result.rssi
            
            val distance = calculateDistance(rssi)
            val manufacturer = macResolver.resolveMacBlocking(device.address)
            val name = try { 
                result.scanRecord?.deviceName ?: device.name ?: "BLE Node" 
            } catch (e: SecurityException) { 
                "Restricted BLE" 
            }
            
            val details = mutableMapOf(
                "Hardware ID" to device.address,
                "Manufacturer" to manufacturer,
                "Est. Distance" to "$distance ft",
                "Signal Strength" to "$rssi dBm",
                "Connectable" to (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.isConnectable.toString() else "Unknown")
            )
            
            addResult(ScanEntry("Bluetooth", name, device.address, signal = "$rssi dBm", extra = "BLE Detected", detailedData = details))
        }
        
        override fun onScanFailed(errorCode: Int) {
            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "BLE scan already in progress"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Failed to register BLE scan callback"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal BLE scan error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scanning not supported"
                else -> "Unknown BLE scan error: $errorCode"
            }
            Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_SHORT).show()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = connectivityManager?.getNetworkCapabilities(network)
            val linkProps = connectivityManager?.getLinkProperties(network)
            val type = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) "WiFi" 
                       else if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) "Cellular"
                       else "Network"
            
            val name = linkProps?.interfaceName ?: "Unknown"
            
            val details = mutableMapOf(
                "Interface" to name,
                "Link Speed" to "${caps?.linkDownstreamBandwidthKbps ?: 0} kbps",
                "DNS" to (linkProps?.dnsServers?.joinToString(", ") { it.hostAddress ?: "" } ?: "N/A"),
                "Transports" to (caps?.let { c ->
                    listOfNotNull(
                        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "WIFI" else null,
                        if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) "CELLULAR" else null,
                        if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) "ETHERNET" else null,
                        if (c.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) "BLUETOOTH" else null
                    ).joinToString(", ")
                } ?: "Unknown")
            )
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                details["MTU"] = linkProps?.mtu?.toString() ?: "N/A"
            }

            runOnUiThread {
                addResult(ScanEntry("Network", "Active: $type", name, extra = "Connected", detailedData = details))
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        macResolver = MacResolver(this)
        wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager?.adapter
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        irManager = getSystemService(ConsumerIrManager::class.java)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitApp()
            }
        })

        setContent {
            TestScannerTheme {
                MainScreen(
                    results = _scanResults,
                    isScanning = isScanning.value,
                    onScanToggle = { if (isScanning.value) stopScan() else startScan() },
                    hasIr = irManager?.hasIrEmitter() == true,
                    btAvailable = bluetoothAdapter != null,
                    nfcAvailable = nfcAdapter != null,
                    onIrTest = { testIr() },
                    onExportLogs = { exportLogs() },
                    onExportSingle = { entry -> exportSingleLog(entry) },
                    onDonate = { openDonate() }
                )
            }
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(bluetoothReceiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(bluetoothReceiver, filter)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager?.registerNetworkCallback(networkRequest, networkCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action) {
            
            val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
            
            tag?.let { t ->
                val id = t.id.joinToString(":") { "%02X".format(it) }
                val techs = t.techList.joinToString(", ")
                val details = mapOf(
                    "Tag ID" to id,
                    "Technologies" to techs,
                    "Action" to (intent.action ?: "Unknown")
                )
                addResult(ScanEntry("NFC", "NFC Tag Detected", id, extra = "Physical Proximity", detailedData = details))
                Toast.makeText(this, "NFC Tag Captured!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openDonate() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://cash.app/\$woeismarc"))
        startActivity(intent)
    }

    private fun exitApp() {
        stopScan()
        finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun addResult(entry: ScanEntry) {
        synchronized(_scanResults) {
            val existingIndex = _scanResults.indexOfFirst { it.address == entry.address && it.type == entry.type }
            if (existingIndex != -1) {
                _scanResults[existingIndex] = entry
            } else {
                _scanResults.add(0, entry)
            }
        }
        synchronized(_history) {
            _history.add(entry)
        }
    }

    private fun calculateDistance(rssi: Int): String {
        val n = 2.2
        val p0 = -45.0
        val distMeters = 10.0.pow((p0 - rssi) / (10 * n))
        val distFeet = distMeters * 3.28084
        return "%.1f".format(distFeet)
    }

    private fun exportSingleLog(entry: ScanEntry) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(entry.timestamp))
        val fileName = "Omniscanner_Single_${entry.type}_$timestamp.csv"
        
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Omniscanner/Individual")
            }
        }

        saveToStorage(contentValues, listOf(entry))
    }

    private fun exportLogs() {
        if (_history.isEmpty()) {
            Toast.makeText(this, "No logs to export", Toast.LENGTH_SHORT).show()
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "Omniscanner_Full_Log_$timestamp.csv"
        
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Omniscanner")
            }
        }

        saveToStorage(contentValues, _history.toList())
    }

    private fun saveToStorage(contentValues: ContentValues, entries: List<ScanEntry>) {
        val resolver = contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Files.getContentUri("external")
        }
        
        val uri = resolver.insert(collection, contentValues)

        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    OutputStreamWriter(outputStream).use { writer ->
                        writer.write("Timestamp,Type,Name,Address,Signal,Extra,DetailedData\n")
                        entries.forEach { entry ->
                            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
                            val detailed = entry.detailedData.map { "${it.key}=${it.value}" }.joinToString("|")
                            writer.write("\"$time\",\"${entry.type}\",\"${entry.name}\",\"${entry.address}\",\"${entry.signal}\",\"${entry.extra}\",\"$detailed\"\n")
                        }
                    }
                }
                Toast.makeText(this, "Log saved to Downloads/Omniscanner", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startScan() {
        isScanning.value = true
        _scanResults.clear()
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                @Suppress("DEPRECATION")
                wifiManager?.startScan()
                wifiManager?.scanResults?.forEach { res ->
                    val dist = calculateDistance(res.level)
                    val manufacturer = macResolver.resolveMacBlocking(res.BSSID)
                    val details = mutableMapOf(
                        "BSSID (MAC)" to res.BSSID,
                        "Manufacturer" to manufacturer,
                        "Est. Distance" to "$dist ft",
                        "Frequency" to "${res.frequency} MHz",
                        "Capabilities" to res.capabilities,
                        "Signal Level" to "${res.level} dBm"
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        details["WiFi Std"] = when(res.wifiStandard) {
                            WifiScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6 (ax)"
                            WifiScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (ac)"
                            WifiScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (n)"
                            else -> "Legacy"
                        }
                    }
                    @Suppress("DEPRECATION")
                    val ssid = res.SSID.ifEmpty { "[Hidden AP]" }
                    addResult(ScanEntry("WiFi", ssid, res.BSSID, "${res.level} dBm", "Dist: $dist ft", detailedData = details))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (bluetoothAdapter?.isEnabled == true) {
            val hasBluetoothScanPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || 
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            
            if (hasBluetoothScanPermission) {
                try {
                    bluetoothAdapter?.startDiscovery()
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to start classic Bluetooth scan: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                
                try {
                    val bleScanner = bluetoothAdapter?.bluetoothLeScanner
                    if (bleScanner != null) {
                        val settings = ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .build()
                        bleScanner.startScan(null, settings, bleScanCallback)
                    } else {
                        Toast.makeText(this, "BLE Scanner unavailable", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to start BLE scan: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Bluetooth scan permission denied", Toast.LENGTH_SHORT).show()
            }
        } else if (bluetoothAdapter != null) {
            Toast.makeText(this, "Enable Bluetooth for full sniffing", Toast.LENGTH_SHORT).show()
        }

        scope.launch(Dispatchers.IO) {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces != null && interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isUp) {
                        val hwAddr = iface.hardwareAddress?.joinToString(":") { "%02X".format(it) } ?: "N/A"
                        val addrs = iface.inetAddresses
                        while (addrs != null && addrs.hasMoreElements()) {
                            val addr = addrs.nextElement()
                            val details = mapOf(
                                "Iface Name" to iface.name,
                                "MAC Address" to hwAddr,
                                "IP" to (addr.hostAddress ?: "N/A")
                            )
                            withContext(Dispatchers.Main) {
                                addResult(ScanEntry("System", "Iface: ${iface.name}", addr.hostAddress ?: "", extra = "MAC: $hwAddr", detailedData = details))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            sniffLocalNetwork()
        }
        
        Toast.makeText(this, "Omni-Sniffing engaged...", Toast.LENGTH_SHORT).show()
    }

    private fun stopScan() {
        isScanning.value = false
        val hasBluetoothScanPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || 
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        
        if (hasBluetoothScanPermission) {
            try {
                bluetoothAdapter?.cancelDiscovery()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        scope.coroutineContext.cancelChildren()
    }

    private suspend fun sniffLocalNetwork() {
        val dhcp = try {
            @Suppress("DEPRECATION")
            wifiManager?.dhcpInfo
        } catch (e: Exception) {
            null
        } ?: return
        
        val ipAddress = dhcp.ipAddress
        if (ipAddress == 0) return
        
        @Suppress("DEPRECATION")
        val prefix = Formatter.formatIpAddress(ipAddress).substringBeforeLast(".")
        
        coroutineScope {
            (1..50).map { i ->
                launch {
                    val testIp = "$prefix.$i"
                    try {
                        val address = InetAddress.getByName(testIp)
                        if (address.isReachable(500)) {
                            val hostName = address.hostName
                            val details = mapOf(
                                "IP Address" to testIp,
                                "Hostname" to hostName
                            )
                            withContext(Dispatchers.Main) {
                                addResult(ScanEntry("IP Scan", hostName, testIp, extra = "Live Node Detected", detailedData = details))
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
            }.joinAll()
        }
    }

    private fun testIr() {
        try {
            if (irManager?.hasIrEmitter() == true) {
                irManager?.transmit(38000, intArrayOf(1000, 1000, 500, 500))
                Toast.makeText(this, "Broadcasting IR Signal...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "IR not supported on this device", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "IR Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
        }
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
        }
        scope.cancel()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    results: List<ScanEntry>,
    isScanning: Boolean,
    onScanToggle: () -> Unit,
    hasIr: Boolean,
    btAvailable: Boolean,
    nfcAvailable: Boolean,
    onIrTest: () -> Unit,
    onExportLogs: () -> Unit,
    onExportSingle: (ScanEntry) -> Unit,
    onDonate: () -> Unit
) {
    var showDonateDialog by remember { mutableStateOf(false) }
    
    val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.TRANSMIT_IR
    )
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res.values.all { it }) onScanToggle()
        else onScanToggle()
    }

    var selectedEntry by remember { mutableStateOf<ScanEntry?>(null) }

    if (showDonateDialog) {
        AlertDialog(
            onDismissRequest = { showDonateDialog = false },
            title = { Text("Support the Developers", color = Color(0xFF03DAC6), fontFamily = FontFamily.Monospace) },
            text = { Text("Would you like to donate to support future updates?", color = Color.White, fontFamily = FontFamily.Monospace) },
            confirmButton = {
                TextButton(onClick = {
                    showDonateDialog = false
                    onDonate()
                }) {
                    Text("YES, DONATE", color = Color(0xFF00FF41), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDonateDialog = false }) {
                    Text("LATER", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF121212)
        )
    }

    selectedEntry?.let { entry ->
        DetailSheet(entry = entry, onExport = { onExportSingle(entry) }, onDismiss = { selectedEntry = null })
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { GlitchTitle() },
                actions = {
                    IconButton(onClick = onExportLogs) {
                        Icon(Icons.Default.SaveAlt, contentDescription = "Export Logs", tint = Color(0xFF00FF41))
                    }
                    IconButton(onClick = { showDonateDialog = true }) {
                        Icon(Icons.Default.VolunteerActivism, contentDescription = "Donate", tint = Color(0xFF03DAC6))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF000000),
                    titleContentColor = Color(0xFF00FF41)
                )
            )
        },
        floatingActionButton = {
            NeonFab(isScanning, onClick = { launcher.launch(permissions.toTypedArray()) })
        },
        containerColor = Color(0xFF000000)
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isScanning) {
                ScanningBackgroundEffect()
                DataParticles()
            }

            Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                Box {
                    if (isScanning) SonarPulseEffect()
                    StatusPanel(hasIr, btAvailable, nfcAvailable, onIrTest)
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = Color(0xFF00FF41), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("DATA STREAM ACTIVE", style = MaterialTheme.typography.labelLarge, color = Color(0xFF00FF41), fontFamily = FontFamily.Monospace)
                    if (isScanning) {
                        Spacer(Modifier.width(12.dp))
                        GlowProgressIndicator(modifier = Modifier.weight(1f))
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                if (results.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("WAITING FOR SIGNAL...", color = Color.DarkGray, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(results) { entry ->
                            TrafficItem(entry, onClick = { selectedEntry = entry })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GlitchTitle() {
    val infiniteTransition = rememberInfiniteTransition(label = "glitch")
    val offset1 by infiniteTransition.animateFloat(
        initialValue = -2f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(50, easing = LinearEasing), RepeatMode.Reverse), label = "o1"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.8f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(100, easing = LinearEasing), RepeatMode.Reverse), label = "a"
    )

    Box(contentAlignment = Alignment.Center) {
        Text(
            "OMNISCANNER ELITE",
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF00FF41).copy(alpha = alpha),
            modifier = Modifier.offset(x = offset1.dp)
        )
        Text(
            "OMNISCANNER ELITE",
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFFF003C).copy(alpha = 0.3f),
            modifier = Modifier.offset(x = (-offset1).dp, y = 1.dp)
        )
    }
}

@Composable
fun SonarPulseEffect() {
    val infiniteTransition = rememberInfiniteTransition(label = "sonar")
    val radius by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearOutSlowInEasing)), label = "r"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearOutSlowInEasing)), label = "a"
    )

    Canvas(modifier = Modifier.fillMaxWidth().height(150.dp)) {
        drawCircle(
            color = Color(0xFF00FF41).copy(alpha = alpha),
            radius = radius * size.width / 1.5f,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
fun DataParticles() {
    val infiniteTransition = rememberInfiniteTransition(label = "particles")
    val particles = remember { List(15) { Random.nextFloat() to Random.nextFloat() } }
    
    val animValue by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "v"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        particles.forEach { (x, yStart) ->
            val currentY = ((yStart + animValue) % 1f) * size.height
            drawCircle(
                color = Color(0xFF00FF41).copy(alpha = 0.2f),
                radius = 2.dp.toPx(),
                center = Offset(x * size.width, currentY)
            )
        }
    }
}

@Composable
fun NeonFab(isScanning: Boolean, onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "fab_glow")
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "scale"
    )

    val color = if (isScanning) Color(0xFFCF6679) else Color(0xFF03DAC6)

    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { 
            Icon(
                if (isScanning) Icons.Default.Radar else Icons.Default.LeakAdd, 
                null,
                modifier = Modifier.graphicsLayer(scaleX = if (isScanning) glowScale else 1f, scaleY = if (isScanning) glowScale else 1f)
            ) 
        },
        text = { Text(if (isScanning) "HALT SNIFF" else "ENGAGE SNIFFER", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) },
        containerColor = color,
        contentColor = Color.Black,
        modifier = Modifier.shadow(if (isScanning) 12.dp else 4.dp, RoundedCornerShape(16.dp), ambientColor = color, spotColor = color)
    )
}

@Composable
fun ScanningBackgroundEffect() {
    val infiniteTransition = rememberInfiniteTransition(label = "scan_bg")
    val scanY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "y"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val gridStep = 40.dp.toPx()
        for (x in 0..(size.width / gridStep).toInt()) {
            drawLine(Color(0xFF00FF41).copy(alpha = 0.05f), Offset(x * gridStep, 0f), Offset(x * gridStep, size.height))
        }
        for (y in 0..(size.height / gridStep).toInt()) {
            drawLine(Color(0xFF00FF41).copy(alpha = 0.05f), Offset(0f, y * gridStep), Offset(size.width, y * gridStep))
        }

        val y = size.height * scanY
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color(0xFF00FF41).copy(alpha = 0.1f), Color.Transparent),
                startY = y - 100,
                endY = y + 100
            ),
            topLeft = Offset(0f, y - 100),
            size = Size(size.width, 200f)
        )
        drawLine(
            color = Color(0xFF00FF41).copy(alpha = 0.4f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 2f
        )
    }
}

@Composable
fun GlowProgressIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "glow_bar")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "alpha"
    )

    LinearProgressIndicator(
        modifier = modifier.height(2.dp).clip(CircleShape),
        color = Color(0xFF00FF41).copy(alpha = alpha),
        trackColor = Color.DarkGray.copy(alpha = 0.2f)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(entry: ScanEntry, onExport: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0A0A0A),
        contentColor = Color.White,
        scrimColor = Color.Black.copy(alpha = 0.9f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 40.dp, start = 24.dp, end = 24.dp)
                .navigationBarsPadding()
        ) {
            Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF00FF41).copy(alpha = 0.3f), CircleShape))
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "DEEP ANALYTICS REPORT",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFF00FF41),
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                IconButton(onClick = onExport) {
                    Icon(Icons.Default.Download, "Export Single", tint = Color(0xFF03DAC6))
                }
            }
            Text(
                "ACQUISITION: ${SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.timestamp))}",
                fontSize = 10.sp,
                color = Color.Gray,
                fontFamily = FontFamily.Monospace
            )
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = Color(0xFF1E1E1E))
            
            DetailRow("SOURCE TYPE", entry.type.uppercase())
            DetailRow("IDENTIFIER", entry.name)
            DetailRow("PHYSICAL ADDR", entry.address)
            
            if (entry.signal.isNotEmpty()) {
                DetailRow("SIGNAL GAIN", entry.signal)
            }
            
            if (entry.detailedData.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("HARDWARE METADATA", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF03DAC6), fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(8.dp))
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF121212)),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF333333))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        entry.detailedData.forEach { (key, value) ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(key.uppercase(), modifier = Modifier.weight(1.2f), fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text(value, modifier = Modifier.weight(2f), fontSize = 11.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FF41), contentColor = Color.Black),
                shape = RoundedCornerShape(2.dp)
            ) {
                Text("DISMISS ANALYTICS", fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 10.dp)) {
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray, fontFamily = FontFamily.Monospace)
        Text(value, fontSize = 18.sp, color = Color.White, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun StatusPanel(hasIr: Boolean, btAvailable: Boolean, nfcAvailable: Boolean, onIrTest: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A0A)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E1E1E)),
        modifier = Modifier.shadow(8.dp, RoundedCornerShape(12.dp), ambientColor = Color(0xFF00FF41).copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("SUB-SYSTEM DIAGNOSTICS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                SensorStatus("WLAN", Icons.Default.SettingsInputAntenna, true)
                SensorStatus("BT_LE", Icons.AutoMirrored.Filled.BluetoothSearching, btAvailable)
                SensorStatus("NFC", Icons.Default.Contactless, nfcAvailable)
                SensorStatus("IR_TX", Icons.Default.RssFeed, hasIr)
            }
            if (hasIr) {
                OutlinedButton(
                    onClick = onIrTest,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF03DAC6)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF03DAC6).copy(alpha = 0.5f))
                ) {
                    Text("EMIT IR BURST", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun SensorStatus(label: String, icon: ImageVector, available: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "alpha"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            icon, 
            contentDescription = null, 
            tint = if (available) Color(0xFF00FF41).copy(alpha = alpha) else Color(0xFF660000),
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun TrafficItem(entry: ScanEntry, onClick: () -> Unit) {
    val accentColor = when (entry.type) {
        "WiFi" -> Color(0xFF2196F3)
        "Bluetooth" -> Color(0xFFBB86FC)
        "NFC" -> Color(0xFFFFB74D)
        "Network" -> Color(0xFF03DAC6)
        "IP Scan" -> Color(0xFF00FF41)
        else -> Color.Gray
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .drawBehind {
                drawRoundRect(
                    color = accentColor.copy(alpha = 0.05f),
                    size = size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
                )
            },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0A0A)),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, accentColor.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(accentColor, CircleShape).shadow(4.dp, CircleShape, spotColor = accentColor))
                    Spacer(Modifier.width(8.dp))
                    Text(entry.type.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Black, color = accentColor, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(2.dp))
                Text(entry.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                Text(entry.address, fontSize = 12.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                if (entry.extra.isNotEmpty()) {
                    Text(entry.extra, fontSize = 10.sp, color = accentColor.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace)
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.size(20.dp))
        }
    }
}
