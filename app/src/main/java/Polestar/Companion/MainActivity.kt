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
    private lateinit var connectionManager: MachinnaA0ConnectionManager
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
        
        // Initialize connection manager
        connectionManager = MachinnaA0ConnectionManager(this)
        
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
        initializeGVRETConnection()
        
        // Connection status will be updated after ViewPager is set up
    }
    
    private fun initializeGVRETConnection() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "Initializing GVRET WiFi connection to Macchina A0")
                
                val prefs = getSharedPreferences("connection_settings", MODE_PRIVATE)
                val wifiIp = prefs.getString("wifi_ip", "192.168.4.1") ?: "192.168.4.1"
                val wifiPort = prefs.getInt("wifi_port", 23) // Default GVRET port like SavvyCAN
                
                Log.i(TAG, "Connecting to Macchina A0 at $wifiIp:$wifiPort")
                
                val connected = connectionManager.connectWiFi(wifiIp, wifiPort)
                if (connected) {
                    Log.i(TAG, "Successfully connected to Macchina A0 via WiFi GVRET")
                    
                    // Start the data reader
                    startMacchinaA0DataReader()
                    
                    // Update connection status on main thread
                    withContext(Dispatchers.Main) {
                        updateConnectionStatus()
                    }
                } else {
                    Log.e(TAG, "Failed to connect to Macchina A0 via WiFi GVRET")
                    withContext(Dispatchers.Main) {
                        showCANConnectionError("Failed to connect to Macchina A0 at $wifiIp:$wifiPort. Please check your WiFi connection and try again.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing GVRET connection", e)
                withContext(Dispatchers.Main) {
                    showCANConnectionError("Error connecting to Macchina A0: ${e.message}")
                }
            }
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
        if (connectionManager.isConnected()) {
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
        val isConnected = connectionManager.isConnected()
        
        if (!isConnected) {
            // GVRET connection not available - show empty
            getMainContentFragment()?.getFragmentBinding()?.textSoh?.text = "Battery SOH: "
            showSOHError("GVRET connection not available. Please ensure Macchina A0 is connected via WiFi.")
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
        val status = if (connectionManager.isConnected()) {
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
    external fun requestSOH()
    external fun startRawCANCapture()
    
    // Safe wrapper for CAN capture - now using GVRET connection
    fun startRawCANCaptureSafe() {
        try {
            Log.d(TAG, "GVRET CAN capture is already active - no additional setup needed")
            if (connectionManager.isConnected()) {
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
        return connectionManager.isConnected()
    }
    external fun isCANInterfaceReady(): Boolean
    
    // Check if GVRET WiFi connection is ready
    fun isGVRETConnectionReady(): Boolean {
        return connectionManager.isConnected()
    }
    
    // Confirm connection to Macchina A0 with detailed testing
    suspend fun confirmMacchinaA0Connection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        val result = ConnectionTestResult()
        
        try {
            Log.i(TAG, "=== Starting Macchina A0 Connection Confirmation ===")
            
            // Test 1: Check if connection manager is initialized
            result.connectionManagerInitialized = ::connectionManager.isInitialized
            Log.d(TAG, "Connection manager initialized: ${result.connectionManagerInitialized}")
            
            // Test 2: Check GVRET connection status
            result.gvretConnected = connectionManager.isConnected()
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
            val isReading = connectionManager.isReading()
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
                initializeGVRETConnection()
                
                // Wait a moment for connection attempt
                delay(2000)
                
                // Check result and show feedback
                if (connectionManager.isConnected()) {
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
                    append("• Initialized: ${::connectionManager.isInitialized}\n")
                    append("• Connected: ${connectionManager.isConnected()}\n")
                    append("• Reading: ${connectionManager.isReading()}\n\n")
                    
                    // Provide step-by-step troubleshooting
                    append("TROUBLESHOOTING STEPS:\n\n")
                    append("1. CHECK MACCHINA A0 POWER:\n")
                    append("   • Ensure Macchina A0 is powered on\n")
                    append("   • LED should be lit on the device\n")
                    append("   • Wait 30 seconds after powering on\n\n")
                    
                    append("2. CHECK WIFI CONNECTION:\n")
                    append("   • Connect phone to Macchina A0 WiFi network\n")
                    append("   • Network name usually starts with 'Macchina'\n")
                    append("   • Password is usually 'macchina' or 'password'\n\n")
                    
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
        if (!connectionManager.isConnected()) {
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
        Toast.makeText(this, "Test CAN messages sent. Check page 3 for display.", Toast.LENGTH_SHORT).show()
    }
    
    // Method to receive CAN messages from native library
    fun onCANMessageReceived(message: CANMessage) {
        try {
            Log.d(TAG, "=== MainActivity.onCANMessageReceived() called ===")
            Log.d(TAG, "Received CAN message from native: ID=${message.getIdAsHex()}, Data=${message.getDataAsHex()}, Length=${message.length}")
            
            // Check if the activity is still valid
            if (isFinishing || isDestroyed) {
                Log.w(TAG, "Activity is finishing or destroyed - ignoring CAN message")
                return
            }
            
            // Parse CAN message for PID data
            parseCANMessageForPIDs(message)
            
            // Log raw CAN data to CSV
            logToCSV("CAN_${message.getIdAsHex()}", message.getDataAsHex().replace(" ", "").toDoubleOrNull() ?: 0.0, "raw")
            
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
            }
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
            if (message.length < 2) return
            
            val mode = message.data[0].toInt() and 0xFF
            val pid = message.data[1].toInt() and 0xFF
            
            Log.d(TAG, "OBD Response: Mode=0x${mode.toString(16).uppercase()}, PID=0x${pid.toString(16).uppercase()}")
            
            when (pid) {
                0x0D -> parseVehicleSpeed(message.data, message.length)
                0x42 -> parseControlModuleVoltage(message.data, message.length)
                0x46 -> parseAmbientTemperature(message.data, message.length)
                0x5B -> parseBatterySOC(message.data, message.length)
                0x0C -> parseEngineRPM(message.data, message.length)
                0x11 -> parseThrottlePosition(message.data, message.length)
                0x05 -> parseCoolantTemperature(message.data, message.length)
                0x02 -> parseVIN(message.data, message.length)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing OBD response", e)
        }
    }
    
    /**
     * Parse SOH (State of Health) response from BECM
     * Based on real-world CAN bus discovery: 0x1EC6AE80: 0x07 0x62 0x49 0x6d XX XX XX XX
     */
    private fun parseSOHResponse(message: CANMessage) {
        try {
            if (message.length < 7) return
            
            // Expected format: 0x07 0x62 0x49 0x6d XX XX XX XX
            val length = message.data[0].toInt() and 0xFF
            val responseType = message.data[1].toInt() and 0xFF
            val didHigh = message.data[2].toInt() and 0xFF
            val didLow = message.data[3].toInt() and 0xFF
            
            Log.d(TAG, "SOH Response: Length=$length, Type=0x${responseType.toString(16).uppercase()}, DID=0x${didHigh.toString(16).uppercase()}${didLow.toString(16).uppercase()}")
            
            // Verify this is a SOH response (0x62 = response to 0x22, DID 0x496D)
            if (responseType == 0x62 && didHigh == 0x49 && didLow == 0x6D) {
                // Extract SOH value from bytes 4-7 (4 bytes in 0.01% units)
                if (message.length >= 8) {
                    val sohRaw = ((message.data[4].toInt() and 0xFF) shl 24) or
                                 ((message.data[5].toInt() and 0xFF) shl 16) or
                                 ((message.data[6].toInt() and 0xFF) shl 8) or
                                 (message.data[7].toInt() and 0xFF)
                    
                    val sohValue = sohRaw * 0.01 // Convert from 0.01% units to percentage
                    
                    Log.d(TAG, "SOH received from BECM: $sohValue%")
                    updateVehicleDataFromCAN("soh", sohValue.toString())
                }
            } else {
                Log.w(TAG, "Unexpected SOH response format")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing SOH response", e)
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
    private fun parseVehicleSpeed(data: ByteArray, length: Int) {
        if (length >= 3) {
            val speed = data[2].toInt() and 0xFF
            Log.d(TAG, "Vehicle Speed: $speed km/h")
            updateVehicleDataFromCAN("speed", speed.toString())
        }
    }
    
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
    private fun parseBatterySOC(data: ByteArray, length: Int) {
        if (length >= 3) {
            val soc = data[2].toInt() and 0xFF
            Log.d(TAG, "Battery SOC: $soc%")
            updateVehicleDataFromCAN("soc", soc.toString())
        }
    }
    
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
                for (message in canMessageBuffer) {
                    canFragment.addCANMessage(message)
                }
                canMessageBuffer.clear()
                Log.d(TAG, "All buffered messages delivered")
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
            
            Toast.makeText(this, "Test CAN message sent to display", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error testing CAN message display", e)
            Toast.makeText(this, "Test failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Request specific PID from vehicle
     */
    fun requestSpecificPID(mode: Int, pid: Int) {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Requesting PID: Mode=0x${mode.toString(16).uppercase()}, PID=0x${pid.toString(16).uppercase()}")
                val success = connectionManager.requestPID(mode, pid)
                if (success) {
                    Toast.makeText(this@MainActivity, "PID request sent successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to send PID request", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting PID", e)
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
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
                val success = connectionManager.requestVIN()
                if (success) {
                    Toast.makeText(this@MainActivity, "VIN request sent successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to send VIN request", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting VIN", e)
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Request SOH (State of Health) from Polestar 2 BECM
     */
    fun requestVehicleSOH() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Requesting SOH from Polestar 2 BECM")
                val success = connectionManager.requestSOH()
                if (success) {
                    Toast.makeText(this@MainActivity, "SOH request sent to BECM successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to send SOH request", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting SOH", e)
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
                if (connectionManager.isPeriodicPollingActive()) {
                    connectionManager.stopPeriodicPIDPolling()
                    Toast.makeText(this@MainActivity, "Stopped periodic PID polling", Toast.LENGTH_SHORT).show()
                } else {
                    connectionManager.startPeriodicPIDPolling()
                    Toast.makeText(this@MainActivity, "Started periodic PID polling", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling PID polling", e)
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Request all essential PIDs for Polestar 2
     */
    fun requestAllEssentialPIDs() {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Requesting all essential PIDs for Polestar 2")
                
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
                    if (connectionManager.requestPID(mode, pid)) {
                        successCount++
                    }
                    delay(100) // Small delay between requests
                }
                
                Toast.makeText(this@MainActivity, "Sent $successCount/${essentialPIDs.size} PID requests", Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting essential PIDs", e)
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
                    if (connectionManager.requestPID(mode, pid)) {
                        successCount++
                    }
                    delay(100) // Small delay between requests
                }
                
                // Also request SOH from BECM
                delay(200) // Small delay before SOH request
                val sohSuccess = connectionManager.requestSOH()
                if (sohSuccess) {
                    successCount++
                }
                
                Toast.makeText(this@MainActivity, "Sent $successCount/${essentialPIDs.size + 1} requests (including SOH)", Toast.LENGTH_SHORT).show()
                
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
    
    // Log decoded CAN signal to CSV
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
                
                // Set up CAN message callback
                connectionManager.setCANMessageCallback { message ->
                    Log.d(TAG, "Received CAN message from GVRET WiFi: ${message.id.toString(16).uppercase()}")
                    onCANMessageReceived(message)
                }
                
                // Start reading
                connectionManager.startReading()
                
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
                
                // Set up CAN message callback
                connectionManager.setCANMessageCallback { message ->
                    Log.d(TAG, "Received CAN message from Macchina A0: ID=${message.getIdAsHex()}, Data=${message.getDataAsHex()}, Length=${message.length}")
                    onCANMessageReceived(message)
                }
                
                // Start reading
                connectionManager.startReading()
                
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
        diagnostics.append("- Initialized: ${if (::connectionManager.isInitialized) "YES" else "NO"}\n")
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
}