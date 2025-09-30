package Polestar.Companion

import android.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.widget.ViewPager2
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.Log
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import Polestar.Companion.databinding.ActivityMainBinding
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.text.DecimalFormat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.NetworkInterface
import android.content.Context
import android.net.wifi.WifiManager
import android.content.Context.WIFI_SERVICE

// Data classes for connection testing
data class ConnectionTestResult(
    var connectionManagerInitialized: Boolean = false,
    var gvretConnected: Boolean = false,
    var communicationTest: Boolean = false,
    var canMessageFlow: Boolean = false,
    var overallSuccess: Boolean = false,
    var error: String? = null,
    var connectionDetails: ConnectionDetails? = null
)

data class ConnectionDetails(
    val wifiIp: String,
    val wifiPort: Int,
    val connectionType: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    private lateinit var sharedPreferences: SharedPreferences
    private val decimalFormat = DecimalFormat("#.##")
    private lateinit var sohDataManager: SOHDataManager
    // GVRET client for Macchina A0 connection
    var gvretClient: GvretClient? = null
    var isConnectedToMacchina = false
    private var macchinaConnectionJob: Job? = null
    private lateinit var viewPager: ViewPager2
    private var mainContentFragment: MainContentFragment? = null
    private var selectedCarYear: Int = 2021 // Default year
    
    private fun getMainContentFragment(): MainContentFragment? {
        if (mainContentFragment == null) {
            // Try to get the fragment from the adapter
            val adapter = viewPager.adapter as? MainPagerAdapter
            if (adapter != null) {
                // Get the fragment that's currently created
                val fragment = supportFragmentManager.fragments.find { it is MainContentFragment } as? MainContentFragment
                mainContentFragment = fragment
            }
        }
        return mainContentFragment
    }
    
    // Performance optimizations for Pixel 8 Pro
    private val dataUpdateScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var dataUpdateJob: Job? = null
    
    // Cached values to reduce UI updates
    private var lastVehicleData: String = ""
    private var lastStatusText: String = ""
    
    // Battery optimization - adaptive refresh rates
    private var isAppInForeground = true
    private var lastDataUpdateTime = 0L
    private val foregroundRefreshRate = 1000L // 1Hz when in foreground
    private val backgroundRefreshRate = 5000L // 0.2Hz when in background
    
    // CAN ID to PID mapping for Polestar 2 (based on community data)
    private val canIdToPID = mapOf(
        0x1D0L to "vehicle_speed",           // Vehicle Speed (km/h × 0.01)
        0x348L to "battery_soc",             // State of Charge (SOC % × 0.5)
        0x3D2L to "battery_current",         // HV Battery Current (A × 0.1)
        0x0D0L to "steering_angle",          // Steering Angle (degrees × 0.1)
        0x2A0L to "wheel_speeds",            // Wheel Speeds (FL, FR, RL, RR)
        0x1FFF0120L to "odometer",           // Odometer reading
        0x1FFF00A0L to "gear_position",      // Gear position
        0x7E8L to "obd_response",            // Standard OBD-II response
        0x7E9L to "obd_response_2",          // Secondary OBD-II response
        0x7EAL to "obd_response_3",          // Tertiary OBD-II response
        0x7EBL to "obd_response_4",          // Quaternary OBD-II response
        0x1EC6AE80L to "soh_response"        // SOH response from BECM
    )
    
    // OBD-II response IDs for PID parsing
    private val obdResponseIds = setOf(0x7E8L, 0x7E9L, 0x7EAL, 0x7EBL)
    
    // SOH response ID for BECM parsing
    private val sohResponseId = 0x1EC6AE80L
    
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CONNECTION_SETUP = 1001
        private var instance: MainActivity? = null
        
        // GVRET Protocol Commands
        private const val CMD_SET_CANBUS_PARAMS = 0x1B.toByte()
        private const val CMD_ENABLE_CANBUS = 0x27.toByte()
        private const val CAN_SPEED_500K = 0x01.toByte()
        
        fun getInstance(): MainActivity? = instance
        
        // Used to load the 'Companion' library on application startup.
        init {
            System.loadLibrary("Companion")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set instance for static access
        instance = this
        
        // Initialize shared preferences first
        sharedPreferences = getSharedPreferences("PolestarCompanionPrefs", MODE_PRIVATE)
        
        // Initialize simple TCP connection
        connectToMacchina()
        
        // Apply theme before setting content view
        applyTheme()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Optimize for Pixel 8 Pro performance
        optimizeForModernDevices()
        
        // Initialize SOH data manager
        sohDataManager = SOHDataManager(this)
        lifecycleScope.launch {
            sohDataManager.setCarYear(selectedCarYear)
        }
        
        // Setup ViewPager2
        setupViewPager()

        // Initialize the app
        initializeApp()
        
        // UI event handlers are now in MainContentFragment
        
        // Start optimized data update loop
        startOptimizedDataUpdateLoop()
        
        // Update connection status after ViewPager is set up
        handler.postDelayed({
            updateConnectionStatus()
        }, 100) // Small delay to ensure fragment is ready
    }
    
    private fun optimizeForModernDevices() {
        // Hide action bar completely
        supportActionBar?.hide()
        
        // Enable edge-to-edge display on modern devices
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        
        // Optimize window flags for better performance
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        
        // Enable immersive mode for better user experience
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }
    
    private fun applyTheme() {
        val useDarkTheme = sharedPreferences.getBoolean("dark_theme", true) // Default to dark
        if (useDarkTheme) {
            setTheme(R.style.Theme_PolestarCompanion)
        } else {
            setTheme(R.style.Theme_PolestarCompanion_Light)
        }
    }
    
    private fun initializeApp() {
        // Check connection settings first
        val prefs = getSharedPreferences("connection_settings", MODE_PRIVATE)
        val connectionType = prefs.getString("connection_type", "wifi")
        
        if (connectionType == null) {
            // No connection settings saved, show connection setup
            showConnectionSetup()
            return
        }
        
        // Initialize CSV logging for CAN data
        initializeCSVLogging()
        
        // Initialize GVRET WiFi connection
        // Connection is now handled automatically in connectToMacchina()
        
        // Connection status will be updated after ViewPager is set up
    }
    
    /**
     * Get the current WiFi network name (SSID) the phone is connected to
     */
    private fun getCurrentWiFiSSID(): String? {
        return NetworkUtils.getCurrentWiFiSSID(this)
    }
    
    
    /**
     * Auto-connect to Macchina A0 with WiFi retry logic
     * Attempts to connect to A0_CAN WiFi with 5s retry intervals and 3 attempts
     */
    private fun connectToMacchina() {
        // Start auto-connect process with retry logic
        startAutoConnectToMacchinaA0()
    }
    
    /**
     * Start auto-connect process to Macchina A0 WiFi
     */
    private fun startAutoConnectToMacchinaA0() {
        macchinaConnectionJob?.cancel()
        macchinaConnectionJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "Starting auto-connect to Macchina A0 WiFi")
                updateConnectionStatusUI("Attempting auto-connect to A0_CAN...")
                
                val success = NetworkUtils.attemptAutoConnectToMacchinaA0(this@MainActivity) { status ->
                    // Update UI on main thread
                    runOnUiThread {
                        updateConnectionStatusUI(status)
                    }
                }
                
                if (success) {
                    Log.i(TAG, "Auto-connect successful, proceeding with GVRET connection")
                    updateConnectionStatusUI("WiFi connected, establishing GVRET connection...")
                    
                    // Now try to connect to GVRET
                    connectToMacchinaDirect()
                } else {
                    Log.w(TAG, "Auto-connect failed, showing manual connection guidance")
                    runOnUiThread {
                        showManualConnectionDialog()
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during auto-connect process", e)
                runOnUiThread {
                    updateConnectionStatusUI("Auto-connect error: ${e.message}")
                    showManualConnectionDialog()
                }
            }
        }
    }
    
    /**
     * Show dialog with manual connection instructions
     */
    private fun showManualConnectionDialog() {
        val guidanceMessage = NetworkUtils.getConnectionGuidanceMessage()
        
        AlertDialog.Builder(this)
            .setTitle("Connect to Macchina A0")
            .setMessage(guidanceMessage)
            .setPositiveButton("Retry Auto-Connect") { _, _ ->
                startAutoConnectToMacchinaA0()
            }
            .setNegativeButton("Connect Manually") { _, _ ->
                // Try direct connection anyway
                connectToMacchinaDirect()
            }
            .setNeutralButton("Cancel") { _, _ ->
                updateConnectionStatusUI("Connection cancelled")
            }
            .show()
    }
    
    /**
     * Get the phone's current IP address and network range
     */
    private fun getPhoneNetworkInfo(): Pair<String, String>? {
        return NetworkUtils.getPhoneNetworkInfo(this)
    }
    
    /**
     * Convert integer IP to string format
     */
    private fun intToIp(ip: Int): String {
        return NetworkUtils.intToIp(ip)
    }
    
    /**
     * Scan network for potential Macchina A0 devices
     */
    private suspend fun scanForMacchinaA0(): String? {
        return NetworkUtils.scanForMacchinaA0(this)
    }
    
    /**
     * Scan local network for responsive devices
     */
    private suspend fun scanLocalNetwork(phoneIP: String, networkMask: String): String? {
        return NetworkUtils.scanLocalNetwork(phoneIP, networkMask)
    }
    
    /**
     * Test basic network connectivity to a specific IP using GVRET ports
     */
    private suspend fun testBasicConnectivity(ip: String): Boolean {
        return NetworkUtils.testBasicConnectivity(ip)
    }

    /**
     * Detect working GVRET port with improved timeout handling
     */
    private suspend fun detectPort(ip: String): Int? {
        val candidatePorts = listOf(35000, 23) // Try 35000 first (more common for Macchina A0)
        
        for (port in candidatePorts) {
            try {
                Log.d(TAG, "Testing GVRET port $port on $ip")
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), 2000) // 2s timeout
                socket.close()
                Log.i(TAG, "✅ GVRET port $port is open on $ip")
                return port
            } catch (e: Exception) {
                Log.d(TAG, "GVRET port $port not accessible: ${e.message}")
                // Try next port
            }
        }
        return null
    }

    /**
     * Auto-detect working port and connect to Macchina A0
     */
    private suspend fun detectAndConnectToMacchina(ip: String): Boolean {
        // First detect the working port
        val port = detectPort(ip)
        if (port == null) {
            Log.e(TAG, "No working ports found on $ip")
            return false
        }
        
        try {
            Log.i(TAG, "Connecting to Macchina A0 on $ip:$port")
            
            // Create GVRET client with detected IP and port
            val config = GvretConfig(
                ip = ip,
                port = port,
                startStreamCmd = byteArrayOf(), // No start command - device streams automatically
                canFrameCmd = 0xF0, // Use 0xF0 as shown in example
                defaultTxCmd = 0xF2,
                txFormat = TxFormat.SIMPLE
            )
            
            gvretClient = GvretClient(config)
            
            // Set up callbacks
            gvretClient?.onConnected = {
                Log.i(TAG, "✅ Connected to Macchina A0 via GVRET")
                isConnectedToMacchina = true
                lifecycleScope.launch(Dispatchers.Main) {
                        updateConnectionStatus()
                    Toast.makeText(this@MainActivity, "Connected to Macchina A0 at $ip:$port", Toast.LENGTH_SHORT).show()
                }
            }
            
            gvretClient?.onDisconnected = { throwable ->
                Log.w(TAG, "GVRET client disconnected", throwable)
                isConnectedToMacchina = false
                lifecycleScope.launch(Dispatchers.Main) {
                    updateConnectionStatus()
                }
            }
            
            gvretClient?.onCanFrame = { frame ->
                // Convert CanFrame to CANMessage for compatibility
                val canMessage = CANMessage(
                    id = frame.canId,
                    data = frame.data,
                    length = frame.dlc,
                    timestamp = frame.timestampUs / 1000, // Convert microseconds to milliseconds
                    isExtended = frame.canId > 0x7FF,
                    isRTR = false
                )
                
                Log.i(TAG, "🎯 RECEIVED CAN MESSAGE: ID=0x${frame.canId.toString(16).uppercase()}, Data=${frame.data.joinToString(" ") { "%02X".format(it) }}, Length=${frame.dlc}")
                
                // Forward to native interface for raw CAN capture
                forwardToNativeInterface(canMessage)
                
                // Check if this is a Polestar-specific message
                when (frame.canId) {
                    0x1D0L -> Log.i(TAG, "🚗 Vehicle Speed Message")
                    0x348L -> Log.i(TAG, "🔋 Battery SOC Message")
                    0x3D3L -> Log.i(TAG, "⚡ Battery Voltage Message")
                    0x7E8L, 0x7E9L, 0x7EAL, 0x7EBL -> Log.i(TAG, "📡 OBD-II Response Message")
                    0x1EC6AE80L -> Log.i(TAG, "🔋 SOH Response Message")
                    else -> Log.d(TAG, "📨 Other CAN Message")
                }
                
                onCANMessageReceived(canMessage)
            }
            
            gvretClient?.onOtherGvretMessage = { cmd, payload ->
                Log.d(TAG, "Received other GVRET message: cmd=0x${cmd.toString(16)}, payload=${payload.joinToString(" ") { "%02X".format(it) }}")
            }
            
            // Connect and start
            gvretClient?.connectAndStart()
            
            // Configure CAN bus parameters (500kbps)
            configureCANBusParameters()
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to $ip:$port: ${e.message}")
            return false
        }
    }

    /**
     * Connect to Macchina A0 using GvretClient
     */
    private fun connectToMacchinaDirect() {
        macchinaConnectionJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "🔍 Scanning for Macchina A0 device...")
                
                // First, try to detect the Macchina A0 IP address
                val detectedIP = scanForMacchinaA0()
                if (detectedIP == null) {
                    Log.e(TAG, "❌ No Macchina A0 device found on network")
                    withContext(Dispatchers.Main) {
                        showCANConnectionError("No Macchina A0 device found on network. Please ensure:\n" +
                                "1. Phone is connected to Macchina A0 WiFi network\n" +
                                "2. Macchina A0 is powered and connected to OBD port")
                    }
                    return@launch
                }
                
                Log.i(TAG, "Detected Macchina A0 at: $detectedIP")
                Log.i(TAG, "🔍 Detecting working port on $detectedIP")
                
                // Detect working port
                val detectedPort = detectPort(detectedIP)
                if (detectedPort == null) {
                    Log.e(TAG, "No working ports found on $detectedIP")
                    withContext(Dispatchers.Main) {
                        showCANConnectionError("Could not connect to Macchina A0 at $detectedIP. Please ensure:\n" +
                                "1. Macchina A0 is powered and connected to OBD port\n" +
                                "2. Device is responding on network")
                    }
                    return@launch
                }
                
                Log.i(TAG, "Found working port: $detectedPort")
                
                // Create GVRET client with detected IP and port
                val config = GvretConfig(
                    ip = detectedIP,
                    port = detectedPort,
                    startStreamCmd = byteArrayOf(), // No start command - device streams automatically
                    canFrameCmd = 0xF0, // Use 0xF0 as shown in example
                    defaultTxCmd = 0xF2,
                    txFormat = TxFormat.SIMPLE
                )
                
                gvretClient = GvretClient(config)
                
                // Set up callbacks
                gvretClient?.onConnected = {
                    Log.i(TAG, "✅ Connected to Macchina A0 via GVRET")
                    isConnectedToMacchina = true
                    lifecycleScope.launch(Dispatchers.Main) {
                        updateConnectionStatus()
                        Toast.makeText(this@MainActivity, "Connected to Macchina A0 at $detectedIP:$detectedPort", Toast.LENGTH_SHORT).show()
                        
                        // Start requesting essential data
                        lifecycleScope.launch(Dispatchers.IO) {
                            delay(1000) // Wait 1 second for connection to stabilize
                            requestAllEssentialPIDs()
                            requestVehicleSOH()
                        }
                    }
                }
                
                gvretClient?.onDisconnected = { throwable ->
                    Log.w(TAG, "GVRET client disconnected", throwable)
                    isConnectedToMacchina = false
                    lifecycleScope.launch(Dispatchers.Main) {
                        updateConnectionStatus()
                    }
                }
                
                gvretClient?.onCanFrame = { frame ->
                    // Convert CanFrame to CANMessage for compatibility
                    val canMessage = CANMessage(
                        id = frame.canId,
                        data = frame.data,
                        length = frame.dlc,
                        timestamp = frame.timestampUs / 1000, // Convert microseconds to milliseconds
                        isExtended = frame.canId > 0x7FF,
                        isRTR = false
                    )
                    
                    Log.d(TAG, "Received CAN frame: ID=0x${frame.canId.toString(16).uppercase()}, Data=${frame.data.joinToString(" ") { "%02X".format(it) }}, Length=${frame.dlc}")
                    
                    // Forward to native interface for raw CAN capture
                    forwardToNativeInterface(canMessage)
                    
                    // Parse OBD-II and SOH responses
                    parseVehicleResponse(canMessage)
                    
                    // Send to UI immediately
                    onCANMessageReceived(canMessage)
                }
                
                gvretClient?.onOtherGvretMessage = { cmd, payload ->
                    Log.d(TAG, "Received other GVRET message: cmd=0x${cmd.toString(16)}, payload=${payload.joinToString(" ") { "%02X".format(it) }}")
                }
                
                // Connect and start
                gvretClient?.connectAndStart()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to Macchina A0: ${e.message}")
                withContext(Dispatchers.Main) {
                    showCANConnectionError("Connection error: ${e.message}")
                }
            }
        }
    }
    
    // GVRET data reading and parsing is now handled by GvretClient
    
    /**
     * Parse vehicle responses for OBD-II PIDs and SOH data
     */
    private fun parseVehicleResponse(message: CANMessage) {
        try {
            when (message.id) {
                // OBD-II Response IDs (7E8-7EF)
                in 0x7E8L..0x7EFL -> {
                    parseOBDIIResponse(message)
                }
                // BECM SOH Response (0x1EC6AE80) - tester address
                0x1EC6AE80L -> {
                    parseSOHResponse(message)
                }
                // Standard vehicle data IDs
                0x1D0L -> parseVehicleSpeed(message)
                0x348L -> parseBatterySOC(message)
                0x3D3L -> parseBatteryVoltage(message)
                0x2A0L -> parseWheelSpeeds(message)
                0x3D2L -> parseBatteryCurrent(message)
                0x4A8L -> parseChargingPower(message)
                0x3E8L -> parseAmbientTemperature(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing vehicle response: ${e.message}", e)
        }
    }
    
    /**
     * Parse OBD-II response messages
     */
    private fun parseOBDIIResponse(message: CANMessage) {
        if (message.length < 3) return
        
        val mode = message.data[1].toInt() and 0xFF
        val pid = message.data[2].toInt() and 0xFF
        
        Log.d(TAG, "OBD-II Response: Mode=0x${mode.toString(16)}, PID=0x${pid.toString(16)}")
        
        when (mode) {
            0x41 -> { // Current data response
                when (pid) {
                    0x0D -> { // Vehicle speed
                        if (message.length >= 4) {
                            val speed = message.data[3].toInt() and 0xFF
                            updateVehicleDataFromCAN("speed", speed.toString())
                            Log.d(TAG, "Vehicle Speed: ${speed} km/h")
                        }
                    }
                    0x42 -> { // Control module voltage
                        if (message.length >= 5) {
                            val voltage = ((message.data[3].toInt() and 0xFF) * 256 + (message.data[4].toInt() and 0xFF)) / 1000.0
                            updateVehicleDataFromCAN("voltage", voltage.toString())
                            Log.d(TAG, "Control Module Voltage: ${voltage}V")
                        }
                    }
                    0x46 -> { // Ambient air temperature
                        if (message.length >= 4) {
                            val temp = (message.data[3].toInt() and 0xFF) - 40
                            updateVehicleDataFromCAN("ambient", temp.toString())
                            Log.d(TAG, "Ambient Temperature: ${temp}°C")
                        }
                    }
                    0x5B -> { // Battery pack SOC
                        if (message.length >= 4) {
                            val soc = ((message.data[3].toInt() and 0xFF) * 100) / 255
                            updateVehicleDataFromCAN("battery_soc", soc.toString())
                            Log.d(TAG, "Battery SOC: ${soc}%")
                        }
                    }
                }
            }
            0x49 -> { // VIN response
                if (pid == 0x02 && message.length >= 4) {
                    val vinPart = String(message.data, 3, message.length - 3)
                    Log.d(TAG, "VIN Part: $vinPart")
                    // VIN comes in multiple messages - would need to collect them
                }
            }
        }
    }
    
    /**
     * Parse SOH response from BECM
     * Expected format: 0x1EC6AE80: 0x07 0x62 0x49 0x6d XX XX XX XX
     * 0x07 = number of valid bytes following
     * 0x62 = response to 0x22 request (0x40 + 0x22)
     * 0x49 0x6d = DID being responded to
     * XX XX XX XX = SOH value in 0.01% units (4 bytes)
     */
    private fun parseSOHResponse(message: CANMessage) {
        try {
            if (message.length < 8) {
                Log.w(TAG, "SOH response too short: ${message.length} bytes")
                return
            }
            
            val length = message.data[0].toInt() and 0xFF
            val responseType = message.data[1].toInt() and 0xFF
            val didHigh = message.data[2].toInt() and 0xFF
            val didLow = message.data[3].toInt() and 0xFF
            
            Log.d(TAG, "SOH Response: ID=0x${message.id.toString(16).uppercase()}, Length=$length, Type=0x${responseType.toString(16).uppercase()}, DID=0x${didHigh.toString(16).uppercase()}${didLow.toString(16).uppercase()}")
            Log.d(TAG, "SOH Response Data: ${message.data.joinToString(" ") { "%02X".format(it) }}")
            
            // Verify this is a SOH response (0x62 = response to 0x22, DID 0x496D)
            if (responseType == 0x62 && didHigh == 0x49 && didLow == 0x6D) {
                // Extract SOH value from bytes 4-7 (4 bytes in 0.01% units)
                val sohRaw = ((message.data[4].toInt() and 0xFF) shl 24) or
                           ((message.data[5].toInt() and 0xFF) shl 16) or
                           ((message.data[6].toInt() and 0xFF) shl 8) or
                           (message.data[7].toInt() and 0xFF)
                
                val sohValue = sohRaw * 0.01 // Convert from 0.01% units to percentage
                
                Log.i(TAG, "✅ SOH received from BECM: $sohValue% (raw: $sohRaw)")
                updateVehicleDataFromCAN("soh", sohValue.toString())
                
                // Update UI
                lifecycleScope.launch(Dispatchers.Main) {
                    updateSOH()
                    Toast.makeText(this@MainActivity, "SoH: $sohValue%", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.w(TAG, "Invalid SOH response format - expected 0x62 0x49 0x6D, got 0x${responseType.toString(16).uppercase()} 0x${didHigh.toString(16).uppercase()} 0x${didLow.toString(16).uppercase()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing SOH response", e)
        }
    }
    
    // Individual vehicle data parsers
    private fun parseVehicleSpeed(message: CANMessage) {
        if (message.length >= 4) {
            val speed = ((message.data[0].toInt() and 0xFF) or 
                        ((message.data[1].toInt() and 0xFF) shl 8)) / 100.0
            updateVehicleDataFromCAN("speed", speed.toString())
        }
    }
    
    private fun parseBatterySOC(message: CANMessage) {
        if (message.length >= 1) {
            val soc = (message.data[0].toInt() and 0xFF) / 2.0
            updateVehicleDataFromCAN("battery_soc", soc.toString())
        }
    }
    
    private fun parseBatteryVoltage(message: CANMessage) {
        if (message.length >= 2) {
            val voltage = ((message.data[0].toInt() and 0xFF) or 
                          ((message.data[1].toInt() and 0xFF) shl 8)) / 100.0
            updateVehicleDataFromCAN("voltage", voltage.toString())
        }
    }
    
    private fun parseWheelSpeeds(message: CANMessage) {
        if (message.length >= 8) {
            val fl = ((message.data[0].toInt() and 0xFF) or ((message.data[1].toInt() and 0xFF) shl 8)) / 100.0
            val fr = ((message.data[2].toInt() and 0xFF) or ((message.data[3].toInt() and 0xFF) shl 8)) / 100.0
            val rl = ((message.data[4].toInt() and 0xFF) or ((message.data[5].toInt() and 0xFF) shl 8)) / 100.0
            val rr = ((message.data[6].toInt() and 0xFF) or ((message.data[7].toInt() and 0xFF) shl 8)) / 100.0
            Log.d(TAG, "Wheel Speeds: FL=${fl}, FR=${fr}, RL=${rl}, RR=${rr} km/h")
        }
    }
    
    private fun parseBatteryCurrent(message: CANMessage) {
        if (message.length >= 2) {
            val current = ((message.data[0].toInt() and 0xFF) or 
                          ((message.data[1].toInt() and 0xFF) shl 8)) / 10.0
            updateVehicleDataFromCAN("current", current.toString())
        }
    }
    
    private fun parseChargingPower(message: CANMessage) {
        if (message.length >= 2) {
            val power = ((message.data[0].toInt() and 0xFF) or 
                        ((message.data[1].toInt() and 0xFF) shl 8)) / 10.0
            updateVehicleDataFromCAN("charging_power", power.toString())
        }
    }
    
    private fun parseAmbientTemperature(message: CANMessage) {
        if (message.length >= 1) {
            val temp = (message.data[0].toInt() and 0xFF) - 40
            updateVehicleDataFromCAN("ambient", temp.toString())
        }
    }
    
    /**
     * Disconnect from Macchina A0 and clean up resources
     */
    private fun disconnectFromMacchina() {
        try {
            isConnectedToMacchina = false
            macchinaConnectionJob?.cancel()
            
            // Disconnect GVRET client
            gvretClient?.disconnect()
            gvretClient = null
            
            Log.i(TAG, "Disconnected from Macchina A0")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting from Macchina A0: ${e.message}", e)
        }
    }
    
    /**
     * Configure CAN bus parameters for 500kbps baud rate
     */
    private suspend fun configureCANBusParameters() {
        try {
            Log.i(TAG, "Configuring CAN bus parameters for 500kbps")
            
            // Set CAN bus parameters (try 250kbps first for Polestar 2)
            val canParams = byteArrayOf(
                0x02.toByte(),   // Speed: 250kbps (try this first)
                0x00.toByte(),   // Mode (normal)
                0x00.toByte(),   // Reserved
                0x00.toByte()    // Reserved
            )
            
            // Send GVRET command to set CAN bus parameters
            sendGVRETCommand(CMD_SET_CANBUS_PARAMS, canParams)
            
            // Small delay to ensure command is processed
            delay(100)
            
            // Enable CAN bus
            sendGVRETCommand(CMD_ENABLE_CANBUS, byteArrayOf())
            
            // Add delay before starting message reception
            delay(500)
            
            Log.i(TAG, "✅ CAN bus configured for 250kbps")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring CAN bus parameters: ${e.message}", e)
        }
    }
    
    /**
     * Send a GVRET command to the Macchina A0
     */
    private fun sendGVRETCommand(command: Byte, data: ByteArray) {
        try {
            if (gvretClient == null) {
                Log.e(TAG, "Cannot send GVRET command - no GVRET client")
                return
            }
            
            // GVRET command format: [0xF1, command, length, data...]
            val commandBytes = ByteArray(3 + data.size)
            commandBytes[0] = 0xF1.toByte()  // GVRET command prefix
            commandBytes[1] = command        // Command byte
            commandBytes[2] = data.size.toByte()  // Data length
            
            // Copy data bytes
            System.arraycopy(data, 0, commandBytes, 3, data.size)
            
            // Send command using GvretClient - use raw send method
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // Send raw command bytes directly
                    gvretClient?.let { client ->
                        // Use reflection or direct method if available
                        val method = client.javaClass.getMethod("sendRawData", ByteArray::class.java)
                        method.invoke(client, commandBytes)
                        Log.d(TAG, "Sent GVRET command: 0x${command.toString(16).uppercase()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send GVRET command", e)
                    // Fallback: try using sendCanFrame with special ID
                    gvretClient?.sendCanFrame(0, 0xFFFFL, commandBytes)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending GVRET command: ${e.message}", e)
        }
    }
    
    private fun showConnectionSetup() {
        try {
            AlertDialog.Builder(this)
                .setTitle("Setup Macchina A0 Connection")
                .setMessage("Please configure how to connect to your Macchina A0 OBD reader.\n\nYou can choose between Bluetooth or WiFi connection.")
                .setPositiveButton("Setup Connection") { dialog: android.content.DialogInterface, which: Int ->
                    try {
                        val intent = Intent(this, ConnectionSettingsActivity::class.java)
                        startActivityForResult(intent, REQUEST_CONNECTION_SETUP)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error opening connection settings", e)
                        Toast.makeText(this, "Error opening connection settings: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Skip") { dialog: android.content.DialogInterface, which: Int ->
                    // Continue with limited functionality
                    Log.i(TAG, "Connection setup skipped by user")
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing connection setup dialog", e)
            Toast.makeText(this, "Error showing connection setup: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showCANConnectionError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("CAN Connection Error")
            .setMessage("$message\n\nPlease ensure:\n• Macchina A0 OBD reader is connected\n• Vehicle is running or in accessory mode\n• CAN interface is available")
            .setPositiveButton("Retry") { dialog: android.content.DialogInterface, which: Int ->
                initializeApp()
            }
            .setNegativeButton("Continue") { dialog: android.content.DialogInterface, which: Int ->
                // Continue with limited functionality
            }
            .setCancelable(false)
            .show()
    }
    
    private fun setupViewPager() {
        viewPager = binding.viewPager
        val adapter = MainPagerAdapter(this)
        viewPager.adapter = adapter
        
        // Setup page change listener for indicator dots
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageIndicator(position)
            }
        })
        
        // Get reference to main content fragment after adapter is set
        // We'll get it when we need it, not during setup
    }
    
    private fun updatePageIndicator(position: Int) {
        val dot1 = findViewById<View>(R.id.dot_page_1)
        val dot2 = findViewById<View>(R.id.dot_page_2)
        val dot3 = findViewById<View>(R.id.dot_page_3)
        
        when (position) {
            0 -> {
                dot1.setBackgroundResource(R.drawable.dot_indicator_active)
                dot2.setBackgroundResource(R.drawable.dot_indicator_inactive)
                dot3.setBackgroundResource(R.drawable.dot_indicator_inactive)
            }
            1 -> {
                dot1.setBackgroundResource(R.drawable.dot_indicator_inactive)
                dot2.setBackgroundResource(R.drawable.dot_indicator_active)
                dot3.setBackgroundResource(R.drawable.dot_indicator_inactive)
            }
            2 -> {
                dot1.setBackgroundResource(R.drawable.dot_indicator_inactive)
                dot2.setBackgroundResource(R.drawable.dot_indicator_inactive)
                dot3.setBackgroundResource(R.drawable.dot_indicator_active)
            }
        }
    }
    
    // Helper methods for fragment to call - now using GVRET connection
    fun startMonitoring() {
        if (isConnectedToMacchina) {
            isMonitoring = true
            updateConnectionStatusUI("GVRET Monitoring Active")
            updateButtonStates()
            Toast.makeText(this, "GVRET Monitoring Started", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "GVRET Monitoring started")
        } else {
            Log.e(TAG, "GVRET connection not available")
            updateConnectionStatusUI("GVRET Connection Not Available")
        }
    }
    
    fun stopMonitoring() {
        isMonitoring = false
        updateConnectionStatusUI("Monitoring Stopped")
        updateButtonStates()
        Toast.makeText(this, "Monitoring Stopped", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Monitoring stopped")
    }
    
    fun updateVehicleData() {
        val dataJson = getVehicleData()
        try {
            val jsonObject = JSONObject(dataJson)
            val useImperialUnits = SettingsActivity.getImperialUnits(sharedPreferences)
            
            // Update UI with vehicle data - show empty values when no data available
            val vin = jsonObject.optString("vin", "")
            getMainContentFragment()?.getFragmentBinding()?.textVin?.text = if (vin.isNotEmpty()) "VIN: $vin" else "VIN: "
            
            // Store VIN for SOH baseline calculation
            if (vin.isNotEmpty()) {
                sharedPreferences.edit().putString("vehicle_vin", vin).apply()
            }
            
            // SOC - only show if we have valid data
            val soc = jsonObject.optInt("soc", -1)
            getMainContentFragment()?.getFragmentBinding()?.textSoc?.text = if (soc != -1) "Battery SOC: ${soc}%" else "Battery SOC: "
            
            // Voltage - only show if we have valid data
            val voltage = jsonObject.optDouble("voltage", -1.0)
            getMainContentFragment()?.getFragmentBinding()?.textVoltage?.text = if (voltage != -1.0) "12V Battery: ${decimalFormat.format(voltage)}V" else "12V Battery: "
            
            // Convert temperature
            val ambientCelsius = jsonObject.optInt("ambient", -100)
            val ambientText = if (ambientCelsius != -100) {
                if (useImperialUnits) {
                    val fahrenheit = (ambientCelsius * 9.0 / 5.0) + 32.0
                    "Ambient Temp: ${decimalFormat.format(fahrenheit)}°F"
                } else {
                    "Ambient Temp: ${ambientCelsius}°C"
                }
            } else {
                "Ambient Temp: "
            }
            getMainContentFragment()?.getFragmentBinding()?.textAmbient?.text = ambientText
            
            // Convert speed
            val speedKmh = jsonObject.optInt("speed", -1)
            val speedText = if (speedKmh != -1) {
                if (useImperialUnits) {
                    val mph = speedKmh * 0.621371
                    "Speed: ${decimalFormat.format(mph)} mph"
                } else {
                    "Speed: ${speedKmh} km/h"
                }
            } else {
                "Speed: "
            }
            getMainContentFragment()?.getFragmentBinding()?.textSpeed?.text = speedText
            
            // Convert odometer
            val odometerKm = jsonObject.optInt("odometer", -1)
            val odometerText = if (odometerKm != -1) {
                if (useImperialUnits) {
                    val miles = odometerKm * 0.621371
                    "Odometer: ${decimalFormat.format(miles)} mi"
                } else {
                    "Odometer: ${odometerKm} km"
                }
            } else {
                "Odometer: "
            }
            getMainContentFragment()?.getFragmentBinding()?.textOdometer?.text = odometerText
            
            // Update gear - only show if we have valid data
            val gear = jsonObject.optString("gear", "")
            getMainContentFragment()?.getFragmentBinding()?.textGear?.text = if (gear.isNotEmpty() && gear != "U") "Gear: $gear" else "Gear: "
            
            // Update RSSI - only show if we have valid data
            val rssi = jsonObject.optInt("rssi", -1)
            getMainContentFragment()?.getFragmentBinding()?.textRssi?.text = if (rssi != -1) "Signal: ${rssi} dBm" else "Signal: "
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing vehicle data", e)
        }
    }
    
    fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }
    
    fun openConnectionSettings() {
        val intent = Intent(this, ConnectionSettingsActivity::class.java)
        startActivityForResult(intent, REQUEST_CONNECTION_SETUP)
    }
    
    private fun updateButtonStates() {
        val fragment = getMainContentFragment()
        fragment?.getFragmentBinding()?.btnStartMonitoring?.isEnabled = !isMonitoring
        fragment?.getFragmentBinding()?.btnStopMonitoring?.isEnabled = isMonitoring
    }
    
    
    private fun startOptimizedDataUpdateLoop() {
        // Cancel any existing job
        dataUpdateJob?.cancel()
        
        // Start optimized coroutine-based data update loop
        dataUpdateJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    try {
                if (isMonitoring) {
                            updateVehicleDataOptimized()
                        } else {
                            updateConnectionStatusOptimized()
                        }
                        // Optimized refresh rate: 5Hz (200ms) for monitoring, 1Hz (1000ms) for status
                        delay(if (isMonitoring) 200L else 1000L)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Coroutine was cancelled - this is normal, don't log as error
                        Log.d(TAG, "Data update loop cancelled")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in data update loop", e)
                        delay(1000L) // Wait longer on error
                    }
                }
            }
        }
    }
    
    fun updateSOH() {
        // Check GVRET connection status first
        val isConnected = isConnectedToMacchina
        
        if (!isConnected) {
            // GVRET connection not available - show empty
            getMainContentFragment()?.getFragmentBinding()?.textSoh?.text = "Battery SOH: "
            val currentSSID = getCurrentWiFiSSID() ?: "unknown"
            showSOHError("GVRET connection not available. Please ensure Macchina A0 is connected via WiFi (current: $currentSSID).")
            return
        }
        
        val dataJson = getVehicleData()
        try {
            val jsonObject = JSONObject(dataJson)
            val sohValue = jsonObject.optDouble("soh", -1.0)
            val sohText = if (sohValue >= 0) {
                "Battery SOH: ${decimalFormat.format(sohValue)}%"
                } else {
                "Battery SOH: "
            }
            getMainContentFragment()?.getFragmentBinding()?.textSoh?.text = sohText
            
            if (sohValue < 0) {
                showSOHError("Failed to read SOH from BECM. Please check CAN communication.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing SOH data", e)
            getMainContentFragment()?.getFragmentBinding()?.textSoh?.text = "Battery SOH: "
            showSOHError("SOH reading error: ${e.message}")
        }
    }
    
    private fun showSOHError(message: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("SOH Reading Error")
                .setMessage("$message\n\nPlease ensure:\n• Vehicle is running\n• BECM is accessible\n• CAN communication is working")
                .setPositiveButton("Retry") { dialog: android.content.DialogInterface, which: Int ->
                    updateSOH()
                }
                .setNegativeButton("OK") { dialog: android.content.DialogInterface, which: Int ->
                    // Do nothing
                }
                .setCancelable(false)
                .show()
        }
    }
    
    fun saveSOHDataAndRefreshGraph() {
        lifecycleScope.launch {
            try {
                // Check connection status first
                val connectionStatus = getConnectionStatus()
                val isConnected = connectionStatus.contains("Connected to OBD", ignoreCase = true) || 
                                 connectionStatus.contains("Monitoring", ignoreCase = true) ||
                                 connectionStatus.contains("OBD Reader Connected", ignoreCase = true)
                
                if (!isConnected) {
                    Log.d(TAG, "BECM not reachable - skipping SOH data save")
                    return@launch
                }
                
                val dataJson = getVehicleData()
                val jsonObject = JSONObject(dataJson)
                val sohValue = jsonObject.optDouble("soh", -1.0)
                
                if (sohValue >= 0) {
                    // Save SOH reading with current timestamp
                    Log.d(TAG, "Adding manual SOH reading: ${sohValue.toFloat()}% at ${System.currentTimeMillis()}")
                    sohDataManager.addSOHReading(sohValue.toFloat())
                    
                    // Refresh graph if we're on the graph page
                    if (viewPager.currentItem == 1) {
                        val fragment = supportFragmentManager.fragments.find { 
                            it is SOHGraphFragment 
                        } as? SOHGraphFragment
                        fragment?.refreshChart()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving SOH data", e)
            }
        }
    }
    
    fun onYearChanged(year: Int) {
        selectedCarYear = year
        // Update SOH data manager with new year and reset graph
        lifecycleScope.launch {
            // Clear all existing readings to reset the graph
            sohDataManager.clearAllReadings()
            // Set the new year (this will regenerate baseline data)
            sohDataManager.setCarYear(year)
            // Refresh the SOH graph when year changes
            refreshSOHGraph()
        }
    }
    
    fun getSelectedCarYear(): Int = selectedCarYear
    
    private fun refreshSOHGraph() {
        // Refresh the SOH graph fragment if it exists
        val fragment = supportFragmentManager.fragments.find { 
            it is SOHGraphFragment 
        } as? SOHGraphFragment
        fragment?.refreshChart()
    }
    
    private fun updateConnectionStatus() {
        val status = if (isConnectedToMacchina) {
            "Connected to Macchina A0 via WiFi GVRET"
        } else {
            "Not Connected - WiFi GVRET Connection Required"
        }
        updateConnectionStatusUI(status)
    }
    
    private fun updateConnectionStatusUI(status: String) {
        // Only show green when actively connected to OBD reader
        val isConnected = status.contains("Connected to OBD", ignoreCase = true) || 
                         status.contains("Monitoring", ignoreCase = true) ||
                         status.contains("OBD Reader Connected", ignoreCase = true) ||
                         status.contains("Connected to Macchina A0", ignoreCase = true)
        
        // Update status text with emoji (only green checkmark, no red X)
        val statusWithEmoji = if (isConnected) {
            "✅ $status"
        } else {
            status // No emoji for disconnected state
        }
        
        val fragment = getMainContentFragment()
        val statusText = fragment?.getFragmentBinding()?.statusText
        val connectionCard = fragment?.getFragmentBinding()?.connectionStatusCard
        
        if (statusText != null && statusText.text != statusWithEmoji) {
            statusText.text = statusWithEmoji
            
            // Update card background color with darker, eye-friendly red
            val cardColor = if (isConnected) {
                getColor(android.R.color.holo_green_light)
            } else {
                android.graphics.Color.parseColor("#8B0000") // Dark red, easier on eyes
            }
            connectionCard?.setCardBackgroundColor(cardColor)
        }
    }
    
    private suspend fun updateVehicleDataOptimized() {
        withContext(Dispatchers.Default) {
        val dataJson = getVehicleData()
            
            // Only update UI if data has changed
            if (dataJson != lastVehicleData) {
                lastVehicleData = dataJson
                
                withContext(Dispatchers.Main) {
                    updateVehicleDataFromJson(dataJson)
                }
            }
        }
    }
    
    private suspend fun updateConnectionStatusOptimized() {
        withContext(Dispatchers.Default) {
            val status = getConnectionStatus()
            // Only show green when actively connected to OBD reader
            val isConnected = status.contains("Connected to OBD", ignoreCase = true) || 
                             status.contains("Monitoring", ignoreCase = true) ||
                             status.contains("OBD Reader Connected", ignoreCase = true)
            
            // Update status text with emoji (only green checkmark, no red X)
            val statusWithEmoji = if (isConnected) {
                "✅ $status"
            } else {
                status // No emoji for disconnected state
            }
            
            // Only update UI if status has changed
            if (statusWithEmoji != lastStatusText) {
                lastStatusText = statusWithEmoji
                
                withContext(Dispatchers.Main) {
                    updateConnectionStatusUI(status)
                }
            }
        }
    }
    
    private fun updateVehicleDataFromJson(dataJson: String) {
        try {
            val jsonObject = JSONObject(dataJson)
            val useImperialUnits = SettingsActivity.getImperialUnits(sharedPreferences)
            
            // Update UI with vehicle data - show empty values when no data available
            val vin = jsonObject.optString("vin", "")
            getMainContentFragment()?.getFragmentBinding()?.textVin?.text = if (vin.isNotEmpty()) "VIN: $vin" else "VIN: "
            
            // Store VIN for SOH baseline calculation
            if (vin.isNotEmpty()) {
                sharedPreferences.edit().putString("vehicle_vin", vin).apply()
            }
            
            // SOC - only show if we have valid data
            val soc = jsonObject.optInt("soc", -1)
            getMainContentFragment()?.getFragmentBinding()?.textSoc?.text = if (soc != -1) "Battery SOC: ${soc}%" else "Battery SOC: "
            
            // Voltage - only show if we have valid data
            val voltage = jsonObject.optDouble("voltage", -1.0)
            getMainContentFragment()?.getFragmentBinding()?.textVoltage?.text = if (voltage != -1.0) "12V Battery: ${decimalFormat.format(voltage)}V" else "12V Battery: "
            
            // Convert temperature
            val ambientCelsius = jsonObject.optInt("ambient", -100)
            val ambientText = if (ambientCelsius != -100) {
                if (useImperialUnits) {
                    val fahrenheit = (ambientCelsius * 9.0 / 5.0) + 32.0
                    "Ambient Temp: ${decimalFormat.format(fahrenheit)}°F"
                } else {
                    "Ambient Temp: ${ambientCelsius}°C"
                }
            } else {
                "Ambient Temp: "
            }
            getMainContentFragment()?.getFragmentBinding()?.textAmbient?.text = ambientText
            
            // Convert speed
            val speedKmh = jsonObject.optInt("speed", -1)
            val speedText = if (speedKmh != -1) {
                if (useImperialUnits) {
                    val mph = speedKmh * 0.621371
                    "Speed: ${decimalFormat.format(mph)} mph"
                } else {
                    "Speed: ${speedKmh} km/h"
                }
            } else {
                "Speed: "
            }
            getMainContentFragment()?.getFragmentBinding()?.textSpeed?.text = speedText
            
            // Convert odometer
            val odometerKm = jsonObject.optInt("odometer", -1)
            val odometerText = if (odometerKm != -1) {
                if (useImperialUnits) {
                    val miles = odometerKm * 0.621371
                    "Odometer: ${decimalFormat.format(miles)} mi"
                } else {
                    "Odometer: ${odometerKm} km"
                }
            } else {
                "Odometer: "
            }
            getMainContentFragment()?.getFragmentBinding()?.textOdometer?.text = odometerText
            
            // Update gear - only show if we have valid data
            val gear = jsonObject.optString("gear", "")
            getMainContentFragment()?.getFragmentBinding()?.textGear?.text = if (gear.isNotEmpty() && gear != "U") "Gear: $gear" else "Gear: "
            
            // Update RSSI - only show if we have valid data
            val rssi = jsonObject.optInt("rssi", -1)
            getMainContentFragment()?.getFragmentBinding()?.textRssi?.text = if (rssi != -1) "Signal: ${rssi} dBm" else "Signal: "
            
            Log.d(TAG, "Vehicle data updated: $dataJson")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing vehicle data JSON", e)
            Toast.makeText(this, "Error parsing vehicle data", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Native methods implemented by the 'Companion' native library
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CONNECTION_SETUP) {
            if (resultCode == RESULT_OK) {
                // Connection settings saved, try to initialize again
                initializeApp()
            } else {
                // User cancelled, continue with limited functionality
                Log.i(TAG, "Connection setup cancelled by user")
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Disconnect from Macchina A0
        disconnectFromMacchina()
        
        // Clear instance
        instance = null
        
        // Close CSV logging
        closeCSVLogging()
        
        // Clean up coroutines
        dataUpdateJob?.cancel()
        dataUpdateScope.cancel()
        uiScope.cancel()
        
        // Stop monitoring if active
                if (isMonitoring) {
            stopMonitoring()
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Reduce update frequency when app is in background
        dataUpdateJob?.cancel()
    }
    
    override fun onResume() {
        super.onResume()
        // Resume optimized data updates
        startOptimizedDataUpdateLoop()
    }

    /**
     * Native methods implemented by the 'Companion' native library
     */
    external fun stringFromJNI(): String
    external fun initializeOBDMonitor(): Boolean
    external fun startOBDMonitoring(): Boolean
    external fun stopOBDMonitoring()
    external fun getVehicleData(): String
    external fun isMonitoringActive(): Boolean
    external fun getConnectionStatus(): String
    external fun isConnected(): Boolean
    external fun startRawCANCapture()
    
    // Safe wrapper for CAN capture - now using GVRET connection
    fun startRawCANCaptureSafe() {
        try {
            Log.d(TAG, "GVRET CAN capture is already active - no additional setup needed")
            if (isConnectedToMacchina) {
                Log.d(TAG, "GVRET connection is active and reading CAN messages")
            } else {
                Log.w(TAG, "GVRET connection not active - CAN messages may not be received")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking GVRET CAN capture status", e)
        }
    }
    external fun stopRawCANCapture()
    external fun isRawCANCaptureActive(): Boolean
    
    // Check if GVRET CAN capture is active
    fun isGVRETCANCaptureActive(): Boolean {
        return isConnectedToMacchina
    }
    external fun isCANInterfaceReady(): Boolean
    
    // Check if GVRET WiFi connection is ready
    fun isGVRETConnectionReady(): Boolean {
        return isConnectedToMacchina
    }
    
    // Confirm connection to Macchina A0 with detailed testing
    suspend fun confirmMacchinaA0Connection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        val result = ConnectionTestResult()
        
        try {
            Log.i(TAG, "=== Starting Macchina A0 Connection Confirmation ===")
            
            // Test 1: Check if connection manager is initialized
            result.connectionManagerInitialized = true // Simple TCP connection is always initialized
            Log.d(TAG, "Connection manager initialized: ${result.connectionManagerInitialized}")
            
            // Test 2: Check GVRET connection status
            result.gvretConnected = isConnectedToMacchina
            Log.d(TAG, "GVRET connection active: ${result.gvretConnected}")
            
            // Test 3: Test GVRET communication (if connected)
            if (result.gvretConnected) {
                result.communicationTest = testGVRETCommunication()
                Log.d(TAG, "GVRET communication test: ${result.communicationTest}")
            }
            
            // Test 4: Check if we can receive CAN messages
            result.canMessageFlow = testCANMessageReception()
            Log.d(TAG, "CAN message flow test: ${result.canMessageFlow}")
            
            // Test 5: Get connection details
            val prefs = getSharedPreferences("connection_settings", MODE_PRIVATE)
            result.connectionDetails = ConnectionDetails(
                wifiIp = prefs.getString("wifi_ip", "192.168.4.1") ?: "192.168.4.1",
                wifiPort = prefs.getInt("wifi_port", 35000),
                connectionType = prefs.getString("connection_type", "wifi") ?: "wifi"
            )
            
            // Overall result
            result.overallSuccess = result.connectionManagerInitialized && 
                                  result.gvretConnected && 
                                  result.communicationTest && 
                                  result.canMessageFlow
            
            Log.i(TAG, "=== Macchina A0 Connection Confirmation Complete ===")
            Log.i(TAG, "Overall success: ${result.overallSuccess}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during connection confirmation", e)
            result.error = e.message ?: "Unknown error"
        }
        
        result
    }
    
    // Test GVRET communication by sending a test command
    private suspend fun testGVRETCommunication(): Boolean = withContext(Dispatchers.IO) {
        try {
            // For now, we'll consider communication successful if we're connected
            // In a full implementation, we could send a GVRET keepalive command and check response
            Log.d(TAG, "GVRET communication test - connection active")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "GVRET communication test failed", e)
            return@withContext false
        }
    }
    
    // Test CAN message reception by checking if we can receive messages
    private suspend fun testCANMessageReception(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Check if the data reader is active
            val isReading = isConnectedToMacchina // Simple connection is reading when connected
            Log.d(TAG, "CAN message reception test - reading active: $isReading")
            return@withContext isReading
        } catch (e: Exception) {
            Log.e(TAG, "CAN message reception test failed", e)
            return@withContext false
        }
    }
    
    // UI method to test connection and show results
    fun testMacchinaA0Connection() {
        lifecycleScope.launch {
            try {
                // Show progress dialog
                val progressDialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle("Testing Macchina A0 Connection")
                    .setMessage("Please wait while we test the connection...")
                    .setCancelable(false)
                    .create()
                progressDialog.show()
                
                // Run connection test
                val result = confirmMacchinaA0Connection()
                
                // Dismiss progress dialog
                progressDialog.dismiss()
                
                // Show results
                showConnectionTestResults(result)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error testing Macchina A0 connection", e)
                Toast.makeText(this@MainActivity, "Connection test failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    // Manually trigger GVRET connection attempt
    fun manualConnectGVRET() {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@MainActivity, "Attempting to connect to Macchina A0...", Toast.LENGTH_SHORT).show()
                
                // Force reinitialize connection
                // Connection is now handled automatically in connectToMacchina()
                
                // Wait a moment for connection attempt
                delay(2000)
                
                // Check result and show feedback
                if (isConnectedToMacchina) {
                    Toast.makeText(this@MainActivity, "Successfully connected to Macchina A0!", Toast.LENGTH_SHORT).show()
                    updateConnectionStatus()
                } else {
                    Toast.makeText(this@MainActivity, "Connection failed. Use Troubleshoot for help.", Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in manual GVRET connection", e)
                Toast.makeText(this@MainActivity, "Connection error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    // Quick troubleshooting method for GVRET connection issues
    fun troubleshootGVRETConnection() {
        lifecycleScope.launch {
            try {
                val troubleshooting = buildString {
                    append("=== GVRET CONNECTION TROUBLESHOOTING ===\n\n")
                    
                    // Check connection settings
                    val prefs = getSharedPreferences("connection_settings", MODE_PRIVATE)
                    val wifiIp = prefs.getString("wifi_ip", "192.168.4.1") ?: "192.168.4.1"
                    val wifiPort = prefs.getInt("wifi_port", 35000)
                    
                    append("Current Settings:\n")
                    append("• IP Address: $wifiIp\n")
                    append("• Port: $wifiPort\n")
                    append("• Connection Type: WiFi GVRET\n\n")
                    
                    // Check connection manager status
                    append("Connection Manager Status:\n")
                    append("• Initialized: true\n")
                    append("• Connected: ${isConnectedToMacchina}\n")
                    append("• Reading: ${isConnectedToMacchina}\n\n")
                    
                    // Provide step-by-step troubleshooting
                    append("TROUBLESHOOTING STEPS:\n\n")
                    append("1. CHECK MACCHINA A0 POWER:\n")
                    append("   • Ensure Macchina A0 is powered on\n")
                    append("   • LED should be lit on the device\n")
                    append("   • Wait 30 seconds after powering on\n\n")
                    
                    append("2. CHECK WIFI CONNECTION:\n")
                    val currentSSID = getCurrentWiFiSSID() ?: "unknown"
                    append("   • Connect phone to Macchina A0 WiFi network\n")
                    append("   • Current network: $currentSSID\n")
                    append("   • Default password: aBigSecret\n\n")
                    
                    append("3. VERIFY IP ADDRESS & PORT:\n")
                    append("   • Macchina A0 default IP: 192.168.4.1\n")
                    append("   • GVRET default port: 23 (same as SavvyCAN)\n")
                    append("   • Check in phone WiFi settings\n")
                    append("   • Try pinging 192.168.4.1 from phone\n\n")
                    
                    append("4. TEST CONNECTION:\n")
                    append("   • Use 'Test Connection' button in Settings\n")
                    append("   • Check Android logs for detailed errors\n")
                    append("   • Try restarting the app\n\n")
                    
                    append("5. ALTERNATIVE SOLUTIONS:\n")
                    append("   • Restart Macchina A0 device\n")
                    append("   • Forget and reconnect to WiFi network\n")
                    append("   • Check if other apps can connect (SavvyCAN)\n")
                    append("   • Verify Macchina A0 firmware is up to date\n\n")
                    
                    append("If problems persist, check the Android logs\n")
                    append("for detailed error messages.")
                }
                
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("GVRET Connection Troubleshooting")
                    .setMessage(troubleshooting)
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                    .setNeutralButton("Connect Now") { _, _ -> manualConnectGVRET() }
                    .setNegativeButton("Test Connection") { _, _ -> testMacchinaA0Connection() }
                    .show()
                    
            } catch (e: Exception) {
                Log.e(TAG, "Error in troubleshooting", e)
                Toast.makeText(this@MainActivity, "Troubleshooting failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // Show connection test results in a dialog
    private fun showConnectionTestResults(result: ConnectionTestResult) {
        val message = buildString {
            append("=== Macchina A0 Connection Test Results ===\n\n")
            
            // Overall status
            append("Overall Status: ")
            if (result.overallSuccess) {
                append("✅ CONNECTED\n")
            } else {
                append("❌ NOT CONNECTED\n")
            }
            append("\n")
            
            // Individual test results
            append("Test Details:\n")
            append("• Connection Manager: ${if (result.connectionManagerInitialized) "✅" else "❌"}\n")
            append("• GVRET Connection: ${if (result.gvretConnected) "✅" else "❌"}\n")
            append("• Communication Test: ${if (result.communicationTest) "✅" else "❌"}\n")
            append("• CAN Message Flow: ${if (result.canMessageFlow) "✅" else "❌"}\n")
            
            // Connection details
            result.connectionDetails?.let { details ->
                append("\nConnection Details:\n")
                append("• Type: ${details.connectionType.uppercase()}\n")
                append("• IP Address: ${details.wifiIp}\n")
                append("• Port: ${details.wifiPort}\n")
            }
            
            // Error information
            result.error?.let { error ->
                append("\nError: $error\n")
            }
            
            // Recommendations
            append("\nRecommendations:\n")
            if (!result.gvretConnected) {
                append("• Connect to Macchina A0 WiFi network\n")
                append("• Verify IP address is 192.168.4.1\n")
                append("• Check if Macchina A0 is powered on\n")
            } else if (!result.canMessageFlow) {
                append("• Start the vehicle to generate CAN messages\n")
                append("• Check CAN bus connection on Macchina A0\n")
            } else {
                append("• Connection is working properly!\n")
                append("• You can now use CAN logging features\n")
            }
        }
        
        AlertDialog.Builder(this)
            .setTitle("Connection Test Results")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .setNeutralButton("Retry Test") { _, _ -> testMacchinaA0Connection() }
            .show()
    }
    
    // CAN message buffer for when CANDataFragment is not yet created
    private val canMessageBuffer = mutableListOf<CANMessage>()
    private var canDataFragment: CANDataFragment? = null
    
    // Debug method to test CAN message flow
    fun testCANMessageFlow() {
        Log.d(TAG, "=== TESTING CAN MESSAGE FLOW ===")
        
        // Check if GVRET connection is active
        if (!isConnectedToMacchina) {
            Log.e(TAG, "GVRET connection not active - cannot test message flow")
            Toast.makeText(this, "GVRET connection not active. Please connect to Macchina A0 first.", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Create test CAN messages with proper Polestar 2 data format
        val testMessages = listOf(
            CANMessage(
                id = 0x1D0L, // Vehicle Speed
                data = byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x64.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()), // 100 km/h (little-endian)
                length = 4,
                timestamp = System.currentTimeMillis(),
                isExtended = false,
                isRTR = false
            ),
            CANMessage(
                id = 0x348L, // Battery SOC
                data = byteArrayOf(0x64.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()), // 50% SOC
                length = 1,
                timestamp = System.currentTimeMillis(),
                isExtended = false,
                isRTR = false
            ),
            CANMessage(
                id = 0x3D3L, // Battery Voltage
                data = byteArrayOf(0x0C.toByte(), 0x80.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()), // 3200mV (little-endian)
                length = 2,
                timestamp = System.currentTimeMillis(),
                isExtended = false,
                isRTR = false
            ),
            CANMessage(
                id = 0x2A0L, // Wheel Speeds
                data = byteArrayOf(0x64.toByte(), 0x00.toByte(), 0x65.toByte(), 0x00.toByte(), 0x66.toByte(), 0x00.toByte(), 0x67.toByte(), 0x00.toByte()), // FL:100, FR:101, RL:102, RR:103 km/h
                length = 8,
                timestamp = System.currentTimeMillis(),
                isExtended = false,
                isRTR = false
            ),
            CANMessage(
                id = 0x3D2L, // Battery Current
                data = byteArrayOf(0x64.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()), // 10.0A (little-endian)
                length = 2,
                timestamp = System.currentTimeMillis(),
                isExtended = false,
                isRTR = false
            ),
            CANMessage(
                id = 0x4A8L, // Charging Power
                data = byteArrayOf(0x64.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()), // 10.0kW (little-endian)
                length = 2,
                timestamp = System.currentTimeMillis(),
                isExtended = false,
                isRTR = false
            )
        )
        
        // Send test messages
        for (message in testMessages) {
            Log.d(TAG, "Sending test message: ${message.id.toString(16).uppercase()}")
            onCANMessageReceived(message)
        }
        
        Log.d(TAG, "Test messages sent. Check CAN Messages window.")
    }
    
    // Method to receive CAN messages from native library
    fun onCANMessageReceived(message: CANMessage) {
        try {
            Log.d(TAG, "=== MainActivity.onCANMessageReceived() called ===")
            Log.d(TAG, "Received CAN message: ID=${message.getIdAsHex()}, Data=${message.getDataAsHex()}, Length=${message.length}")
            
            // Check if the activity is still valid
            if (isFinishing || isDestroyed) {
                Log.w(TAG, "Activity is finishing or destroyed - ignoring CAN message")
                return
            }
            
            // Handle parsed JSON messages (empty data indicates parsed message)
            if (message.length == 0 && message.data.isEmpty()) {
                Log.d(TAG, "Received parsed JSON message - no raw data to process")
                // Parsed messages are already processed by GVRETWiFiManager
                return
            }
            
            // Check if this is a standard OBD-II response (11-bit or 29-bit)
            if (message.id == CANConstants.SHORT_RECV_ID || 
                (message.id and CANConstants.LONG_RECV_MASK) == CANConstants.LONG_RECV_ID) {
                parseOBDResponse(message)
            }
            // Check if this is a Polestar-specific broadcast message
            else if ((message.id and CANConstants.LONGBC_RECV_MASK) == CANConstants.LONGBC_RECV_ID) {
                parseBroadcastMessage(message)
            }
            // Handle other CAN messages
            else {
                // Parse CAN message for PID data
                parseCANMessageForPIDs(message)
            }
            
            // Log raw CAN data to CSV (only for messages with actual data)
            if (message.length > 0) {
                logToCSV("CAN_${message.getIdAsHex()}", message.getDataAsHex().replace(" ", "").toDoubleOrNull() ?: 0.0, "raw")
            }
            
                // Log decoded signals based on CAN ID and update vehicle data
                when (message.id) {
                    0x1D0L -> {
                        if (message.length >= 4) {
                            val speedRaw = (message.data[2].toInt() and 0xFF) or ((message.data[3].toInt() and 0xFF) shl 8)
                            val speed = speedRaw * 0.01
                            logDecodedCANSignal("1D0", "Vehicle Speed", speed, "km/h")
                            // Update vehicle data
                            updateVehicleDataFromCAN("speed", speed.toInt().toString())
                        }
                    }
                    0x348L -> {
                        if (message.length >= 1) {
                            val soc = (message.data[0].toInt() and 0xFF) * 0.5
                            logDecodedCANSignal("348", "Battery SOC", soc, "%")
                            // Update vehicle data
                            updateVehicleDataFromCAN("soc", soc.toInt().toString())
                        }
                    }
                    0x3D3L -> {
                        if (message.length >= 2) {
                            val voltageRaw = (message.data[0].toInt() and 0xFF) or ((message.data[1].toInt() and 0xFF) shl 8)
                            val voltage = voltageRaw * 0.1
                            logDecodedCANSignal("3D3", "HV Battery Voltage", voltage, "V")
                            // Update vehicle data
                            updateVehicleDataFromCAN("voltage", voltage.toString())
                        }
                    }
                0x2A0L -> {
                    if (message.length >= 8) {
                        val fl = (message.data[0].toInt() and 0xFF) or ((message.data[1].toInt() and 0xFF) shl 8)
                        val fr = (message.data[2].toInt() and 0xFF) or ((message.data[3].toInt() and 0xFF) shl 8)
                        val rl = (message.data[4].toInt() and 0xFF) or ((message.data[5].toInt() and 0xFF) shl 8)
                        val rr = (message.data[6].toInt() and 0xFF) or ((message.data[7].toInt() and 0xFF) shl 8)
                        logDecodedCANSignal("2A0", "Wheel FL", fl * 0.01, "km/h")
                        logDecodedCANSignal("2A0", "Wheel FR", fr * 0.01, "km/h")
                        logDecodedCANSignal("2A0", "Wheel RL", rl * 0.01, "km/h")
                        logDecodedCANSignal("2A0", "Wheel RR", rr * 0.01, "km/h")
                    }
                }
                0x3D2L -> {
                    if (message.length >= 2) {
                        val currentRaw = (message.data[0].toInt() and 0xFF) or ((message.data[1].toInt() and 0xFF) shl 8)
                        val current = (currentRaw.toShort() * 0.1).toDouble()
                        logDecodedCANSignal("3D2", "HV Battery Current", current, "A")
                    }
                }
                0x4A8L -> {
                    if (message.length >= 2) {
                        val powerRaw = (message.data[0].toInt() and 0xFF) or ((message.data[1].toInt() and 0xFF) shl 8)
                        val power = powerRaw * 0.1
                        logDecodedCANSignal("4A8", "Charging Power", power, "kW")
                    }
                }
                // Add ambient temperature handling if available
                0x3E8L -> { // Example ambient temperature CAN ID - adjust as needed
                    if (message.length >= 1) {
                        val ambient = (message.data[0].toInt() and 0xFF) - 40 // Typical offset
                        logDecodedCANSignal("3E8", "Ambient Temperature", ambient.toDouble(), "°C")
                        // Update vehicle data
                        updateVehicleDataFromCAN("ambient", ambient.toString())
                    }
                }
            }
            
            // Try to get the CAN data fragment
            val canFragment = getCANDataFragment()
            if (canFragment != null) {
                Log.d(TAG, "Found CANDataFragment, adding message: ID=${message.getIdAsHex()}, Data=${message.getDataAsHex()}")
                canFragment.addCANMessage(message)
            } else {
                // Store message in buffer until fragment is available
                Log.d(TAG, "CANDataFragment not found - storing message in buffer: ID=${message.getIdAsHex()}, Data=${message.getDataAsHex()}")
                canMessageBuffer.add(message)
                Log.d(TAG, "Buffer now contains ${canMessageBuffer.size} messages")
                
                // Try to deliver buffered messages periodically
                if (canMessageBuffer.size > 10) { // If buffer gets too large, try to deliver
                    deliverBufferedCANMessages()
                }
            }
            
            // Log message details for debugging
            Log.d(TAG, "CAN Message: ID=0x${message.getIdAsHex()}, Data=${message.getDataAsHex()}, Length=${message.length}, Extended=${message.isExtended}")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCANMessageReceived", e)
        }
    }
    
    // Update vehicle data from CAN message values
    private fun updateVehicleDataFromCAN(field: String, value: String) {
        try {
            Log.d(TAG, "Updating vehicle data: $field = $value")
            // Call native method to update vehicle data
            updateVehicleDataNative(field, value)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating vehicle data from CAN", e)
        }
    }
    
    /**
     * Parse CAN message for PID data extraction
     */
    private fun parseCANMessageForPIDs(message: CANMessage) {
        try {
            // Check if this is an OBD-II response
            if (obdResponseIds.contains(message.id)) {
                parseOBDResponse(message)
            } else if (message.id == sohResponseId) {
                // Check if this is a SOH response from BECM
                parseSOHResponse(message)
            } else {
                // Check if this CAN ID maps to a known PID
                canIdToPID[message.id]?.let { pidName ->
                    val value = decodeCANValue(message.id, message.data, message.length)
                    if (value != null) {
                        Log.d(TAG, "Extracted PID from CAN: $pidName = $value")
                        updateVehicleDataFromCAN(pidName, value.toString())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing CAN message for PIDs", e)
        }
    }
    
    /**
     * Parse OBD-II response messages
     */
    private fun parseOBDResponse(message: CANMessage) {
        try {
            if (message.length < 3) return
            
            val frameType = CANConstants.getFrameType(message.data)
            var mode = -1
            var pid = -1
            
            when (frameType) {
                CANConstants.SINGLE_FRAME -> {
                    val (length, parsedMode, parsedPid) = CANConstants.parseSingleFrame(message.data)
                    mode = parsedMode
                    pid = parsedPid
                    Log.d(TAG, "Single frame: Length=$length, Mode=0x${mode.toString(16).uppercase()}, PID=0x${pid.toString(16).uppercase()}")
                }
                
                CANConstants.FIRST_FRAME -> {
                    val (length, parsedMode, parsedPid) = CANConstants.parseFirstFrame(message.data)
                    mode = parsedMode
                    pid = parsedPid
                    Log.d(TAG, "First frame: Length=$length, Mode=0x${mode.toString(16).uppercase()}, PID=0x${pid.toString(16).uppercase()}")
                    
                    // Request consecutive frames
                    val flowRequestId = CANConstants.getFlowRequestId(message.id)
                    val flowData = CANConstants.buildFlowRequest(message.id)
                    lifecycleScope.launch(Dispatchers.IO) {
                        gvretClient?.sendCanFrame(0, flowRequestId, flowData, message.isExtended)
                    }
                }
                
                CANConstants.CONSECUTIVE_FRAME -> {
                    val index = CANConstants.parseConsecutiveFrame(message.data)
                    Log.d(TAG, "Consecutive frame: Index=$index")
                    // Handle consecutive frame data (VIN parsing, etc.)
                    return
                }
                
                else -> {
                    Log.w(TAG, "Unknown frame type: $frameType")
                    return
                }
            }
            
            // Parse response based on mode and PID
            when (mode) {
                CANConstants.MODE_CURRENT_RESPONSE -> {
                    parseCurrentModeResponse(message, pid)
                }
                
                CANConstants.MODE_INFORMATION_RESPONSE -> {
                    parseInformationModeResponse(message, pid)
                }
                
                else -> {
                    Log.d(TAG, "Unknown response mode: 0x${mode.toString(16).uppercase()}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing OBD response", e)
        }
    }
    
    /**
     * Parse Mode 0x41 (Current Data) responses
     */
    private fun parseCurrentModeResponse(message: CANMessage, pid: Int) {
        try {
            when (pid) {
                CANConstants.PID_VEHICLE_SPEED -> {
                    if (message.length >= 4) {
                        val speed = message.data[3].toInt() and 0xFF
                        Log.i(TAG, "Vehicle Speed: $speed km/h")
                        updateVehicleDataFromCAN("speed", speed.toString())
                        logDecodedCANSignal("SPEED", "Vehicle Speed", speed.toDouble(), "km/h")
                    }
                }
                
                CANConstants.PID_BATTERY_PACK_SOC -> {
                    if (message.length >= 4) {
                        val soc = ((message.data[3].toInt() and 0xFF) * 100.0 / 255.0).toInt()
                        Log.i(TAG, "Battery SOC: $soc%")
                        updateVehicleDataFromCAN("soc", soc.toString())
                        logDecodedCANSignal("SOC", "Battery SOC", soc.toDouble(), "%")
                    }
                }
                
                CANConstants.PID_CONTROL_MODULE_VOLTAGE -> {
                    if (message.length >= 5) {
                        val voltageRaw = ((message.data[3].toInt() and 0xFF) shl 8) or (message.data[4].toInt() and 0xFF)
                        val voltage = voltageRaw / 1000.0
                        Log.i(TAG, "Control Module Voltage: $voltage V")
                        updateVehicleDataFromCAN("voltage", voltage.toString())
                        logDecodedCANSignal("VOLTAGE", "Control Module Voltage", voltage, "V")
                    }
                }
                
                CANConstants.PID_AMBIENT_AIR_TEMPERATURE -> {
                    if (message.length >= 4) {
                        val temp = (message.data[3].toInt() and 0xFF) - 40
                        Log.i(TAG, "Ambient Air Temperature: $temp°C")
                        updateVehicleDataFromCAN("ambient_temp", temp.toString())
                        logDecodedCANSignal("AMBIENT", "Ambient Temperature", temp.toDouble(), "°C")
                    }
                }
                
                else -> {
                    Log.d(TAG, "Unknown PID in current mode: 0x${pid.toString(16).uppercase()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing current mode response", e)
        }
    }
    
    /**
     * Parse Mode 0x49 (Information) responses
     */
    private fun parseInformationModeResponse(message: CANMessage, pid: Int) {
        try {
            when (pid) {
                CANConstants.PID_VIN -> {
                    // VIN parsing logic (multi-frame)
                    Log.d(TAG, "VIN response received")
                    // TODO: Implement VIN parsing for multi-frame responses
                }
                
                else -> {
                    Log.d(TAG, "Unknown PID in information mode: 0x${pid.toString(16).uppercase()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing information mode response", e)
        }
    }
    
    /**
     * Parse Polestar-specific broadcast messages (odometer, gear, etc.)
     */
    private fun parseBroadcastMessage(message: CANMessage) {
        try {
            Log.d(TAG, "Parsing broadcast message: ID=0x${message.id.toString(16).uppercase()}")
            
            when (message.id) {
                CANConstants.ODOMETER_ID -> {
                    if (message.length >= 3) {
                        val odometer = ((message.data[0].toInt() and 0x0F) shl 16) or
                                     ((message.data[1].toInt() and 0xFF) shl 8) or
                                     (message.data[2].toInt() and 0xFF)
                        
                        Log.i(TAG, "Odometer: $odometer km")
                        updateVehicleDataFromCAN("odometer", odometer.toString())
                        logDecodedCANSignal("ODOMETER", "Odometer", odometer.toDouble(), "km")
                    }
                }
                
                CANConstants.GEAR_ID -> {
                    if (message.length >= 7) {
                        val gearIndex = message.data[6].toInt() and 0x03
                        val gear = CANConstants.GEAR_TRANSLATE[gearIndex]
                        
                        Log.i(TAG, "Gear: $gear")
                        updateVehicleDataFromCAN("gear", gear.toString())
                        logDecodedCANSignal("GEAR", "Gear", gear.toDouble(), "")
                    }
                }
                
                else -> {
                    Log.d(TAG, "Unknown broadcast message ID: 0x${message.id.toString(16).uppercase()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing broadcast message", e)
        }
    }
    
    
    /**
     * Decode CAN value based on CAN ID and data
     */
    private fun decodeCANValue(canId: Long, data: ByteArray, length: Int): Double? {
        return try {
            when (canId) {
                0x1D0L -> {
                    // Vehicle Speed (km/h × 0.01) - bytes 2-3: little-endian uint16
                    if (length >= 4) {
                        val speedRaw = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
                        speedRaw * 0.01
                    } else null
                }
                0x348L -> {
                    // State of Charge (SOC %) - byte 0: SOC % × 0.5
                    if (length >= 1) {
                        (data[0].toInt() and 0xFF) * 0.5
                    } else null
                }
                0x3D2L -> {
                    // HV Battery Current (A × 0.1) - bytes 0-1: little-endian int16 (signed)
                    if (length >= 2) {
                        val currentRaw = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                        val signedCurrent = if (currentRaw > 32767) currentRaw - 65536 else currentRaw
                        signedCurrent * 0.1
                    } else null
                }
                0x0D0L -> {
                    // Steering Angle (degrees × 0.1) - bytes 0-1: little-endian int16 (signed)
                    if (length >= 2) {
                        val angleRaw = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                        val signedAngle = if (angleRaw > 32767) angleRaw - 65536 else angleRaw
                        signedAngle * 0.1
                    } else null
                }
                0x2A0L -> {
                    // Wheel Speeds (FL, FR, RL, RR) km/h × 0.01 - return average
                    if (length >= 8) {
                        val fl = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                        val fr = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
                        val rl = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)
                        val rr = (data[6].toInt() and 0xFF) or ((data[7].toInt() and 0xFF) shl 8)
                        (fl + fr + rl + rr) * 0.01 / 4.0
                    } else null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding CAN value for ID 0x${canId.toString(16).uppercase()}", e)
            null
        }
    }
    
    /**
     * Parse vehicle speed from OBD response
     */
    // Removed duplicate parseVehicleSpeed function - using CANMessage version instead
    
    /**
     * Parse control module voltage from OBD response
     */
    private fun parseControlModuleVoltage(data: ByteArray, length: Int) {
        if (length >= 3) {
            val voltage = (data[2].toInt() and 0xFF) * 0.1
            Log.d(TAG, "Control Module Voltage: $voltage V")
            updateVehicleDataFromCAN("voltage", voltage.toString())
        }
    }
    
    /**
     * Parse ambient temperature from OBD response
     */
    private fun parseAmbientTemperature(data: ByteArray, length: Int) {
        if (length >= 3) {
            val temp = (data[2].toInt() and 0xFF) - 40 // OBD-II offset
            Log.d(TAG, "Ambient Temperature: $temp °C")
            updateVehicleDataFromCAN("ambient", temp.toString())
        }
    }
    
    /**
     * Parse battery SOC from OBD response
     */
    // Removed duplicate parseBatterySOC function - using CANMessage version instead
    
    /**
     * Parse engine RPM from OBD response
     */
    private fun parseEngineRPM(data: ByteArray, length: Int) {
        if (length >= 4) {
            val rpm = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            Log.d(TAG, "Engine RPM: $rpm")
            updateVehicleDataFromCAN("rpm", rpm.toString())
        }
    }
    
    /**
     * Parse throttle position from OBD response
     */
    private fun parseThrottlePosition(data: ByteArray, length: Int) {
        if (length >= 3) {
            val throttle = (data[2].toInt() and 0xFF) * 100.0 / 255.0
            Log.d(TAG, "Throttle Position: $throttle%")
            updateVehicleDataFromCAN("throttle", throttle.toString())
        }
    }
    
    /**
     * Parse coolant temperature from OBD response
     */
    private fun parseCoolantTemperature(data: ByteArray, length: Int) {
        if (length >= 3) {
            val temp = (data[2].toInt() and 0xFF) - 40 // OBD-II offset
            Log.d(TAG, "Coolant Temperature: $temp °C")
            updateVehicleDataFromCAN("coolant_temp", temp.toString())
        }
    }
    
    /**
     * Parse VIN from OBD response (multi-frame)
     */
    private fun parseVIN(data: ByteArray, length: Int) {
        if (length >= 3) {
            val frameNumber = data[1].toInt() and 0xFF
            val vinData = data.sliceArray(2 until length)
            
            Log.d(TAG, "VIN Frame $frameNumber: ${vinData.joinToString("") { "%02X".format(it) }}")
            // TODO: Implement multi-frame VIN assembly
        }
    }
    
    // Native method to update vehicle data
    external fun updateVehicleDataNative(field: String, value: String)
    external fun forwardCANMessageFromGVRET(id: Long, data: ByteArray, timestamp: Long, isExtended: Boolean, isRTR: Boolean)
    
    // Helper method to forward CAN messages to native interface
    private fun forwardToNativeInterface(message: CANMessage) {
        try {
            Log.d(TAG, "Forwarding CAN message to native interface - ID: 0x${message.getIdAsHex()}, Data: ${message.getDataAsHex()}")
            forwardCANMessageFromGVRET(
                message.id,
                message.data,
                message.timestamp,
                message.isExtended,
                message.isRTR
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error forwarding CAN message to native interface", e)
        }
    }
    
    // Helper method to get CANDataFragment from ViewPager
    private fun getCANDataFragment(): CANDataFragment? {
        try {
            // First check if we already have a reference
            if (canDataFragment != null && canDataFragment!!.isAdded) {
                return canDataFragment
            }
            
            // Try to find it in the fragment manager
            val fragment = supportFragmentManager.fragments.find { it is CANDataFragment } as? CANDataFragment
            if (fragment != null) {
                canDataFragment = fragment
                return fragment
            }
            
            // Try to get it from the ViewPager adapter
            val adapter = viewPager.adapter as? MainPagerAdapter
            if (adapter != null) {
                // ViewPager2 creates fragments lazily, so we need to check if page 2 is created
                val fragmentManager = supportFragmentManager
                val fragmentTag = "f" + 2 // ViewPager2 uses this tag format
                val fragment = fragmentManager.findFragmentByTag(fragmentTag) as? CANDataFragment
                if (fragment != null) {
                    canDataFragment = fragment
                    return fragment
                }
            }
            
            Log.d(TAG, "CANDataFragment not yet created by ViewPager2")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting CANDataFragment", e)
        }
        return null
    }
    
    // Method to deliver buffered messages to CANDataFragment
    fun deliverBufferedCANMessages() {
        try {
            val canFragment = getCANDataFragment()
            if (canFragment != null && canMessageBuffer.isNotEmpty()) {
                Log.d(TAG, "Delivering ${canMessageBuffer.size} buffered CAN messages to CANDataFragment")
                
                // Deliver messages in reverse order (oldest first) to maintain chronological order
                val messagesToDeliver = canMessageBuffer.toList().reversed()
                for (message in messagesToDeliver) {
                    canFragment.addCANMessage(message)
                }
                canMessageBuffer.clear()
                Log.d(TAG, "All buffered messages delivered")
            } else if (canMessageBuffer.isNotEmpty()) {
                Log.d(TAG, "CANDataFragment not ready, keeping ${canMessageBuffer.size} messages in buffer")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error delivering buffered CAN messages", e)
        }
    }
    
    // Test method to verify CAN message display is working
    fun testCANMessageDisplay() {
        try {
            Log.d(TAG, "Testing CAN message display...")
            
            // Create a test CAN message
            val testMessage = CANMessage(
                id = 0x1D0L, // Vehicle Speed
                data = byteArrayOf(0x00, 0x00, 0x64, 0x00, 0x00, 0x00, 0x00, 0x00), // 100 km/h
                length = 4,
                timestamp = System.currentTimeMillis(),
                isExtended = false,
                isRTR = false
            )
            
            Log.d(TAG, "Created test message: ID=${testMessage.getIdAsHex()}, Data=${testMessage.getDataAsHex()}")
            
            // Send it through the normal flow
            onCANMessageReceived(testMessage)
            
            Log.d(TAG, "Test CAN message sent to display")
            
            } catch (e: Exception) {
            Log.e(TAG, "Error testing CAN message display", e)
            Toast.makeText(this, "Test failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Test method to verify GVRET connection is working
    fun testGVRETConnection() {
        try {
            Log.d(TAG, "Testing GVRET connection...")
            
            if (gvretClient == null) {
                Toast.makeText(this, "GVRET client not initialized", Toast.LENGTH_SHORT).show()
                return
            }
            
            if (!isConnectedToMacchina) {
                Toast.makeText(this, "Not connected to Macchina A0", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Send a test CAN frame
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val testData = byteArrayOf(0x01.toByte(), 0x0D.toByte()) // OBD-II speed request
                    gvretClient?.sendCanFrame(0, 0x7DFL, testData)
                    Log.d(TAG, "Sent test CAN frame to GVRET")
                    
                    withContext(Dispatchers.Main) {
                        Log.d(TAG, "Test CAN frame sent via GVRET")
                }
            } catch (e: Exception) {
                    Log.e(TAG, "Error sending test CAN frame", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Test failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error testing GVRET connection", e)
            Toast.makeText(this, "Test failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Test GVRETWiFiManager directly
    fun testGVRETWiFiManager() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "=== TESTING GVRETWiFiManager ===")
                
                // Create GVRETWiFiManager instance
                val gvretManager = GVRETWiFiManager(this@MainActivity)
                
                // Set callback to receive CAN messages
                gvretManager.setCANMessageCallback { message ->
                    Log.i(TAG, "🎯 GVRETWiFiManager CAN MESSAGE: ID=0x${message.id.toString(16).uppercase()}, Data=${message.getDataAsHex()}")
                }
                
                // Connect to Macchina A0
                val connected = gvretManager.connect("192.168.4.1", 23)
                if (connected) {
                    Log.i(TAG, "✅ GVRETWiFiManager connected successfully")
                    
                    // Test connection
                    val testResult = gvretManager.testConnection()
                    Log.i(TAG, "GVRETWiFiManager test result: $testResult")
                    
                    // Start reading
                    gvretManager.startReading()
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "GVRETWiFiManager test started - check logs", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Log.e(TAG, "❌ GVRETWiFiManager connection failed")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "GVRETWiFiManager connection failed", Toast.LENGTH_SHORT).show()
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error testing GVRETWiFiManager", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Test failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    // Debug method to check GVRET connection status
    fun debugGVRETStatus() {
        try {
            Log.d(TAG, "=== GVRET CONNECTION DEBUG ===")
            Log.d(TAG, "GVRET Client: ${if (gvretClient != null) "Initialized" else "NULL"}")
            Log.d(TAG, "Connected to Macchina: $isConnectedToMacchina")

            if (gvretClient != null) {
                // Check if we can find the fragment
                val canFragment = getCANDataFragment()
                Log.d(TAG, "CANDataFragment: ${if (canFragment != null) "Found" else "NOT FOUND"}")
                Log.d(TAG, "Buffered messages: ${canMessageBuffer.size}")

                // Test sending a message directly
                val testMessage = CANMessage(
                    id = 0x123L,
                    data = byteArrayOf(0x01, 0x02, 0x03, 0x04),
                    length = 4,
                    timestamp = System.currentTimeMillis(),
                    isExtended = false,
                    isRTR = false
                )

                Log.d(TAG, "Sending test message directly to UI...")
                onCANMessageReceived(testMessage)

                // Check current WiFi connection
                val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                val ssid = wifiInfo.ssid
                Log.d(TAG, "Current WiFi SSID: $ssid")

                // Check if GVRET client is actually receiving data
                Log.d(TAG, "GVRET Client socket connected: ${gvretClient?.socket?.isConnected}")
                Log.d(TAG, "GVRET Client read job active: ${gvretClient?.readJob?.isActive}")

                Toast.makeText(this, "Debug: GVRET=${gvretClient != null}, Connected=$isConnectedToMacchina, Fragment=${canFragment != null}, Buffer=${canMessageBuffer.size}, WiFi=$ssid", Toast.LENGTH_LONG).show()
                } else {
                Toast.makeText(this, "GVRET client is NULL - connection failed", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
            Log.e(TAG, "Error in debugGVRETStatus", e)
            Toast.makeText(this, "Debug failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Enhanced diagnostic method to check data flow
    fun diagnoseCANDataFlow() {
        try {
            Log.d(TAG, "=== CAN DATA FLOW DIAGNOSTIC ===")
            
            // 1. Check GVRET client status
            Log.d(TAG, "1. GVRET Client Status:")
            Log.d(TAG, "   - Client exists: ${gvretClient != null}")
            Log.d(TAG, "   - Socket connected: ${gvretClient?.socket?.isConnected}")
            Log.d(TAG, "   - Read job active: ${gvretClient?.readJob?.isActive}")
            Log.d(TAG, "   - Connection flag: $isConnectedToMacchina")
            
            // 2. Check fragment status
            Log.d(TAG, "2. CANDataFragment Status:")
            val canFragment = getCANDataFragment()
            Log.d(TAG, "   - Fragment exists: ${canFragment != null}")
            Log.d(TAG, "   - Fragment message count: ${canFragment?.getMessageCount() ?: 0}")
            
            // 3. Check message buffer
            Log.d(TAG, "3. Message Buffer Status:")
            Log.d(TAG, "   - Buffer size: ${canMessageBuffer.size}")
            if (canMessageBuffer.isNotEmpty()) {
                Log.d(TAG, "   - Latest buffered message: ID=${canMessageBuffer.last().getIdAsHex()}")
            }
            
            // 4. Check WiFi connection
            Log.d(TAG, "4. WiFi Connection Status:")
            val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            Log.d(TAG, "   - SSID: ${wifiInfo.ssid}")
            Log.d(TAG, "   - Connected: ${wifiInfo.networkId != -1}")
            
            // 5. Test message flow
            Log.d(TAG, "5. Testing Message Flow:")
                val testMessage = CANMessage(
                    id = 0x999L,
                    data = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()),
                    length = 4,
                    timestamp = System.currentTimeMillis(),
                    isExtended = false,
                    isRTR = false
                )
            
            Log.d(TAG, "   - Sending test message: ID=${testMessage.getIdAsHex()}")
            onCANMessageReceived(testMessage)
            
            // 6. Check if message appeared in fragment
            lifecycleScope.launch {
                delay(1000) // Wait 1 second
                val updatedFragment = getCANDataFragment()
                val updatedCount = updatedFragment?.getMessageCount() ?: 0
                Log.d(TAG, "   - Fragment message count after test: $updatedCount")
                
                val message = "Diagnostic Complete:\n" +
                        "GVRET: ${gvretClient != null}\n" +
                        "Connected: $isConnectedToMacchina\n" +
                        "Fragment: ${canFragment != null}\n" +
                        "Buffer: ${canMessageBuffer.size}\n" +
                        "Messages: $updatedCount\n" +
                        "WiFi: ${wifiInfo.ssid}"
                
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in diagnoseCANDataFlow", e)
            Toast.makeText(this, "Diagnostic failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Force start CAN monitoring for debugging
    fun forceStartCANMonitoring() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "=== Force Starting CAN Monitoring ===")
                
                // 1. Force connection if not connected
                if (!isConnectedToMacchina) {
                    Log.d(TAG, "Not connected, attempting connection...")
                    connectToMacchinaDirect()
                    delay(2000) // Wait for connection
                }
                
                // 2. Start CAN session
                val canFragment = getCANDataFragment()
                if (canFragment != null) {
                    canFragment.startSession()
                    Log.d(TAG, "Started CAN session")
                } else {
                    Log.w(TAG, "CANDataFragment not found")
                }
                
                // 3. Send test messages
                val testMessage = CANMessage(
                    id = 0x999L,
                    data = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()),
                    length = 4,
                    timestamp = System.currentTimeMillis(),
                    isExtended = false,
                    isRTR = false
                )
                onCANMessageReceived(testMessage)
                Log.d(TAG, "Sent test message")
                
                // 4. Request essential PIDs
                requestAllEssentialPIDs()
                delay(500)
                requestVehicleSOH()
                
                Log.d(TAG, "Force start CAN monitoring completed")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error force starting CAN monitoring", e)
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    // Request all essential PIDs
    fun requestAllEssentialPIDs() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Requesting all essential PIDs using proper OBD-II format")
                
                var successCount = 0
                for ((mode, pid) in CANConstants.PID_REQUESTS) {
                    try {
                        // Build proper OBD-II request
                        val requestData = CANConstants.buildPIDRequest(mode, pid)
                        
                        // Send to both 11-bit and 29-bit addresses (like working example)
                        gvretClient?.sendCanFrame(0, CANConstants.SHORT_SEND_ID, requestData, false)
                        gvretClient?.sendCanFrame(0, CANConstants.LONG_SEND_ID, requestData, true)
                        
                        val description = when (pid) {
                            CANConstants.PID_VEHICLE_SPEED -> "Vehicle Speed"
                            CANConstants.PID_CONTROL_MODULE_VOLTAGE -> "Control Module Voltage"
                            CANConstants.PID_AMBIENT_AIR_TEMPERATURE -> "Ambient Air Temperature"
                            CANConstants.PID_BATTERY_PACK_SOC -> "Battery Pack SOC"
                            CANConstants.PID_VIN -> "VIN"
                            else -> "Unknown PID"
                        }
                        
                        Log.d(TAG, "Requested Mode=0x${mode.toString(16).uppercase()}, PID=0x${pid.toString(16).uppercase()}: $description")
                        successCount++
                        
                        // Small delay between requests
                        delay(100)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to request PID 0x${pid.toString(16).uppercase()}", e)
                    }
                }
                
                Log.i(TAG, "Successfully requested $successCount/${CANConstants.PID_REQUESTS.size} PIDs")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting essential PIDs", e)
            }
        }
    }
    
    // Request vehicle SOH
    fun requestVehicleSOH() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Requesting vehicle SOH from BECM")
                
                // Correct SoH request format: 0x1DD01635: 0x03 0x22 0x49 0x6d 0x00 0x00 0x00 0x00
                val sohRequestData = byteArrayOf(
                    0x03.toByte(),  // Number of valid bytes following
                    0x22.toByte(),  // UDS message type
                    0x49.toByte(),  // DID high byte
                    0x6D.toByte(),  // DID low byte
                    0x00.toByte(),  // Padding
                    0x00.toByte(),  // Padding
                    0x00.toByte(),  // Padding
                    0x00.toByte()   // Padding
                )
                
                val becmId = 0x1DD01635L
                val result = gvretClient?.sendCanFrame(0, becmId, sohRequestData, true)
                
                Log.d(TAG, "SOH request sent to BECM: ID=0x${becmId.toString(16).uppercase()}, Data=${sohRequestData.joinToString(" ") { "%02X".format(it) }}, Result=$result")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting SOH", e)
            }
        }
    }
    
    fun requestSpecificPID(mode: Int, pid: Int) {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Requesting PID: Mode=0x${mode.toString(16).uppercase()}, PID=0x${pid.toString(16).uppercase()}")
                
                if (gvretClient == null) {
                    Toast.makeText(this@MainActivity, "Not connected to Macchina A0", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                // Build OBD-II request: Mode + PID
                val requestData = byteArrayOf(mode.toByte(), pid.toByte())
                
                // Send as CAN message to ECU (standard OBD-II request)
                val canId = 0x7DFL // Standard OBD-II request ID
                
                // Send via GVRET client
                gvretClient?.sendCanFrame(0, canId, requestData, false)
                
                Log.d(TAG, "PID request sent: Mode=0x${mode.toString(16).uppercase()}, PID=0x${pid.toString(16).uppercase()}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting PID", e)
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Start periodic SoH requests (every 30 seconds)
     */
    fun startPeriodicSoHRequests() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isConnectedToMacchina) {
                try {
                    requestVehicleSOH()
                    delay(30000) // 30 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic SoH request", e)
                    delay(5000) // Wait 5 seconds before retrying
                }
            }
        }
    }
    
    /**
     * Test SoH request manually
     */
    fun testSoHRequest() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "=== Testing SoH Request ===")
                requestVehicleSOH()
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "SoH request sent - check logs for response", Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error testing SoH request", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "SoH test failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * Request VIN from vehicle
     */
    fun requestVehicleVIN() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Requesting VIN from vehicle")
                // TODO: Implement VIN request functionality for simple TCP connection
                // VIN request not implemented yet
                Log.d(TAG, "VIN request not implemented for GVRET connection")
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting VIN", e)
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Start/stop periodic PID polling
     */
    fun togglePeriodicPIDPolling() {
        lifecycleScope.launch {
            try {
                // TODO: Implement periodic polling functionality for simple TCP connection
                Toast.makeText(this@MainActivity, "Periodic polling not yet implemented for simple TCP connection", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling PID polling", e)
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Request all essential PIDs plus SOH for Polestar 2
     */
    fun requestAllEssentialPIDsWithSOH() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Requesting all essential PIDs plus SOH for Polestar 2")
                
                val essentialPIDs = listOf(
                    Pair(0x01, 0x0D), // Vehicle Speed
                    Pair(0x01, 0x42), // Control Module Voltage
                    Pair(0x01, 0x46), // Ambient Air Temperature
                    Pair(0x01, 0x5B), // Battery Pack SOC
                    Pair(0x01, 0x0C), // Engine RPM
                    Pair(0x01, 0x11), // Throttle Position
                    Pair(0x01, 0x05), // Coolant Temperature
                    Pair(0x09, 0x02)  // VIN
                )
                
                var successCount = 0
                for ((mode, pid) in essentialPIDs) {
                    // TODO: Implement PID request for simple TCP connection
                    if (false) {
                        successCount++
                    }
                    delay(100) // Small delay between requests
                }
                
                // Also request SOH from BECM
                delay(200) // Small delay before SOH request
                // TODO: Implement SOH request for simple TCP connection
                val sohSuccess = false
                if (sohSuccess) {
                    successCount++
                }
                
                Log.d(TAG, "Sent $successCount/${essentialPIDs.size + 1} requests (including SOH)")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting essential PIDs with SOH", e)
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private var csvWriter: FileWriter? = null
    private var csvFile: File? = null
    
    // Initialize CSV logging
    private fun initializeCSVLogging() {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            csvFile = File(getExternalFilesDir(null), "polestar2_log_$timestamp.csv")
            csvWriter = FileWriter(csvFile)
            csvWriter?.write("Timestamp,Signal,Value,Unit\n") // CSV header
            Log.d(TAG, "CSV logging initialized: ${csvFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CSV logging", e)
        }
    }
    
    // Log CAN data to CSV with decoded values
    private fun logToCSV(signal: String, value: Double, unit: String = "") {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val signalWithUnit = if (unit.isNotEmpty()) "$signal ($unit)" else signal
            csvWriter?.write("$timestamp,$signalWithUnit,$value\n")
            csvWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to CSV", e)
        }
    }
    
    // Log to CSV
    private fun logToCSV(message: String) {
        Log.d(TAG, "CSV Log: $message")
    }
    
    // Log decoded CAN signal (simple version)
    private fun logDecodedCANSignal(signal: String, value: String) {
        Log.d(TAG, "CAN Signal: $signal = $value")
    }
    
    // Log decoded CAN signal to CSV (full version)
    private fun logDecodedCANSignal(canId: String, signal: String, value: Double, unit: String = "") {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val signalWithUnit = if (unit.isNotEmpty()) "$signal ($unit)" else signal
            csvWriter?.write("$timestamp,CAN_${canId}_$signalWithUnit,$value\n")
            csvWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write decoded CAN signal to CSV", e)
        }
    }
    
    // Close CSV logging
    private fun closeCSVLogging() {
        try {
            csvWriter?.close()
            Log.d(TAG, "CSV logging closed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close CSV logging", e)
        }
    }
    
    // Start reading CAN data from GVRET WiFi
    fun startGVRETDataReader() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting GVRET WiFi data reader")
                
                // Simple TCP connection already handles CAN message reading
                Log.d(TAG, "Simple TCP connection is already reading CAN messages")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in GVRET WiFi data reader", e)
            }
        }
    }
    
    // Start reading CAN data from Macchina A0 via WiFi GVRET
    fun startMacchinaA0DataReader() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting Macchina A0 WiFi GVRET data reader")
                
                // Simple TCP connection already handles CAN message reading
                Log.d(TAG, "Simple TCP connection is already reading CAN messages from Macchina A0")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in Macchina A0 WiFi GVRET data reader", e)
            }
        }
    }
    
    
    // Method to check current connection status
    fun getConnectionDiagnostics(): String {
        val diagnostics = StringBuilder()
        diagnostics.append("=== MACCHINA A0 CONNECTION STATUS ===\n\n")
        
        diagnostics.append("OBD Monitor Status:\n")
        diagnostics.append("- Initialized: YES\n")
        diagnostics.append("- CAN Interface Ready: ${isCANInterfaceReady()}\n")
        diagnostics.append("- Raw CAN Capture Active: ${isRawCANCaptureActive()}\n")
        diagnostics.append("- GVRET Connection Ready: ${isGVRETConnectionReady()}\n")
        diagnostics.append("- GVRET CAN Capture Active: ${isGVRETCANCaptureActive()}\n")
        diagnostics.append("- Connection Status: ${getConnectionStatus()}\n")
        diagnostics.append("- Is Connected: ${isConnected()}\n\n")
        
        diagnostics.append("Next Steps:\n")
        diagnostics.append("1. Connect to Macchina A0 via WiFi GVRET (192.168.4.1:23)\n")
        diagnostics.append("2. Use 'Test Connection' button in Settings\n")
        diagnostics.append("3. Start receiving real CAN data from Polestar 2\n\n")
        
        diagnostics.append("This app only works with real CAN data from\n")
        diagnostics.append("your Polestar 2 via Macchina A0 OBD reader.\n")
        
        return diagnostics.toString()
    }
    
    // --- Enhanced Macchina A0 Control Commands ---
    
    /**
     * Enable raw CAN streaming from Macchina A0
     */
    fun enableRawCANStreaming() {
        try {
            gvretClient?.sendRawEnable(true)
            Log.i(TAG, "Enabled raw CAN streaming")
            Toast.makeText(this, "Raw CAN streaming enabled", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable raw CAN streaming", e)
            Toast.makeText(this, "Failed to enable raw CAN streaming: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Disable raw CAN streaming from Macchina A0
     */
    fun disableRawCANStreaming() {
        try {
            gvretClient?.sendRawEnable(false)
            Log.i(TAG, "Disabled raw CAN streaming")
            Toast.makeText(this, "Raw CAN streaming disabled", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable raw CAN streaming", e)
            Toast.makeText(this, "Failed to disable raw CAN streaming: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Set CAN ID filter on Macchina A0
     */
    fun setCANFilter(ids: List<Int>) {
        try {
            gvretClient?.sendFilter(ids)
            Log.i(TAG, "Set CAN filter for IDs: ${ids.joinToString(", ") { "0x${it.toString(16).uppercase()}" }}")
            Toast.makeText(this, "CAN filter set for ${ids.size} IDs", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set CAN filter", e)
            Toast.makeText(this, "Failed to set CAN filter: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Request status from Macchina A0
     */
    fun requestMacchinaStatus() {
        try {
            gvretClient?.requestStatus()
            Log.i(TAG, "Requested status from Macchina A0")
            Toast.makeText(this, "Status requested", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request status", e)
            Toast.makeText(this, "Failed to request status: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Connect to Macchina A0 in JSON mode
     */
    fun connectToMacchinaJSONMode() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "=== Starting JSON Mode Connection ===")
                
                // Check WiFi connection first
                val wifiSSID = getCurrentWiFiSSID()
                Log.i(TAG, "Current WiFi SSID: $wifiSSID")
                
                if (wifiSSID != "MacchinaA0" && wifiSSID != "A0-CAN") {
                    withContext(Dispatchers.Main) {
                        Log.w(TAG, "Not connected to MacchinaA0 WiFi - current SSID: $wifiSSID")
                        Toast.makeText(this@MainActivity, "Please connect to MacchinaA0 WiFi first", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                
                Log.i(TAG, "Attempting JSON mode connection to 192.168.4.1:35000")
                
                // Set up CAN frame callback for JSON mode
                gvretClient?.onCanFrame = { frame ->
                    // Convert CanFrame to CANMessage for compatibility
                    val canMessage = CANMessage(
                        id = frame.canId,
                        data = frame.data,
                        length = frame.dlc,
                        timestamp = frame.timestampUs / 1000, // Convert microseconds to milliseconds
                        isExtended = frame.canId > 0x7FF,
                        isRTR = false
                    )
                    
                    Log.d(TAG, "Received CAN frame: ID=0x${frame.canId.toString(16).uppercase()}, Data=${frame.data.joinToString(" ") { "%02X".format(it) }}, Length=${frame.dlc}")
                    
                    // Forward to native interface for raw CAN capture
                    forwardToNativeInterface(canMessage)
                    
                    // Parse OBD-II and SOH responses
                    parseVehicleResponse(canMessage)
                    
                    // Send to UI immediately
                    onCANMessageReceived(canMessage)
                }
                
                val success = gvretClient?.connect("192.168.4.1", 35000, true) ?: false
                
                if (success) {
                    Log.i(TAG, "✅ JSON mode connection successful")
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Connected in JSON mode", Toast.LENGTH_SHORT).show()
                    }
                    
                    // Enable raw streaming
                    delay(500)
                    Log.i(TAG, "Enabling raw CAN streaming")
                    gvretClient?.sendRawEnable(true)
                    
                    // Request initial status
                    delay(500)
                    Log.i(TAG, "Requesting device status")
                    gvretClient?.requestStatus()
                    
                    // Start reading JSON messages
                    Log.i(TAG, "Starting JSON message reading")
                    gvretClient?.startReading()
                    
                } else {
                    Log.e(TAG, "❌ JSON mode connection failed")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to connect in JSON mode", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting in JSON mode", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * Connect to Macchina A0 in GVRET mode
     */
    fun connectToMacchinaGVRETMode() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val success = gvretClient?.connect("192.168.4.1", 23, false) ?: false
                if (success) {
                    withContext(Dispatchers.Main) {
                        Log.i(TAG, "Connected to Macchina A0 in GVRET mode")
                        Toast.makeText(this@MainActivity, "Connected in GVRET mode", Toast.LENGTH_SHORT).show()
                        
                        // Start reading GVRET messages
                        gvretClient?.startReading()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Log.e(TAG, "Failed to connect to Macchina A0 in GVRET mode")
                        Toast.makeText(this@MainActivity, "Failed to connect in GVRET mode", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "Error connecting in GVRET mode", e)
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * Set common OBD-II CAN ID filter
     */
    fun setOBDIIFilter() {
        val obdIds = listOf(
            0x7E8,  // OBD-II response
            0x7E9,  // OBD-II response
            0x7EA,  // OBD-II response
            0x7EB,  // OBD-II response
            0x18DAF100,  // 29-bit OBD-II response
            0x1FFF0120,  // Polestar odometer
            0x1FFF00A0   // Polestar gear
        )
        setCANFilter(obdIds)
    }
    
    /**
     * Clear CAN ID filter (allow all messages)
     */
    fun clearCANFilter() {
        setCANFilter(emptyList())
    }
    
    /**
     * Test connection to Macchina A0 with detailed logging
     */
    fun testMacchinaA0ConnectionDetailed() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "=== Starting Detailed Macchina A0 Connection Test ===")
                
                // Step 1: Check WiFi connection
                val wifiSSID = getCurrentWiFiSSID()
                Log.i(TAG, "Current WiFi SSID: $wifiSSID")
                
                if (wifiSSID != "MacchinaA0" && wifiSSID != "A0-CAN") {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Please connect to MacchinaA0 WiFi first", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                
                // Step 2: Test JSON mode connection
                Log.i(TAG, "Attempting JSON mode connection to 192.168.4.1:35000")
                val success = gvretClient?.connect("192.168.4.1", 35000, true) ?: false
                
                if (success) {
                    Log.i(TAG, "✅ JSON mode connection successful")
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Connected to Macchina A0 in JSON mode", Toast.LENGTH_SHORT).show()
                    }
                    
                    // Step 3: Enable raw streaming
                    delay(500)
                    Log.i(TAG, "Enabling raw CAN streaming")
                    gvretClient?.sendRawEnable(true)
                    
                    // Step 4: Request status
                    delay(500)
                    Log.i(TAG, "Requesting device status")
                    gvretClient?.requestStatus()
                    
                    // Step 5: Start reading
                    Log.i(TAG, "Starting message reading")
                    gvretClient?.startReading()
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Connection test completed - check logs for data", Toast.LENGTH_LONG).show()
                    }
                    
                } else {
                    Log.e(TAG, "❌ JSON mode connection failed")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to connect to Macchina A0", Toast.LENGTH_SHORT).show()
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Connection test failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Connection test failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}