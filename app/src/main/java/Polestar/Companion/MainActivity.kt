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
        val connectionType = prefs.getString("connection_type", null)
        
        if (connectionType == null) {
            // No connection settings saved, show connection setup
            showConnectionSetup()
            return
        }
        
        // Initialize CSV logging for CAN data
        initializeCSVLogging()
        
        // Initialize OBD monitor with connection settings
        if (initializeOBDMonitor()) {
            Log.i(TAG, "OBD Monitor initialized successfully")
        } else {
            Log.e(TAG, "Failed to initialize OBD Monitor")
            showCANConnectionError("Failed to initialize CAN interface with Macchina A0 OBD reader")
        }
        // Connection status will be updated after ViewPager is set up
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
    
    // Helper methods for fragment to call
    fun startMonitoring() {
        if (initializeOBDMonitor()) {
            if (startOBDMonitoring()) {
                isMonitoring = true
                updateConnectionStatusUI("Monitoring Active")
                updateButtonStates()
                Toast.makeText(this, "OBD Monitoring Started", Toast.LENGTH_SHORT).show()
                Log.i(TAG, "OBD Monitoring started")
            } else {
                Log.e(TAG, "Failed to start OBD Monitoring")
                updateConnectionStatusUI("Failed to Start Monitoring")
            }
        } else {
            Log.e(TAG, "OBD Monitor not initialized")
            updateConnectionStatusUI("OBD Monitor Not Initialized")
        }
    }
    
    fun stopMonitoring() {
        stopOBDMonitoring()
        isMonitoring = false
        updateConnectionStatusUI("Monitoring Stopped")
        updateButtonStates()
        Toast.makeText(this, "OBD Monitoring Stopped", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "OBD Monitoring stopped")
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
        // Check connection status first
        val connectionStatus = getConnectionStatus()
        val isConnected = connectionStatus.contains("Connected to OBD", ignoreCase = true) || 
                         connectionStatus.contains("Monitoring", ignoreCase = true) ||
                         connectionStatus.contains("OBD Reader Connected", ignoreCase = true)
        
        if (!isConnected) {
            // BECM cannot be reached - show empty
            getMainContentFragment()?.getFragmentBinding()?.textSoh?.text = "Battery SOH: "
            showSOHError("BECM not connected. Please ensure vehicle is running and CAN communication is working.")
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
        val status = getConnectionStatus()
        updateConnectionStatusUI(status)
    }
    
    private fun updateConnectionStatusUI(status: String) {
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
    
    // Safe wrapper for startRawCANCapture
    fun startRawCANCaptureSafe() {
        try {
            Log.d(TAG, "Starting raw CAN capture safely...")
            startRawCANCapture()
            Log.d(TAG, "Raw CAN capture started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting raw CAN capture", e)
        }
    }
    external fun stopRawCANCapture()
    external fun isRawCANCaptureActive(): Boolean
    external fun isCANInterfaceReady(): Boolean
    
    // CAN message buffer for when CANDataFragment is not yet created
    private val canMessageBuffer = mutableListOf<CANMessage>()
    private var canDataFragment: CANDataFragment? = null
    
    // Debug method to test CAN message flow
    fun testCANMessageFlow() {
        Log.d(TAG, "=== TESTING CAN MESSAGE FLOW ===")
        
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
            Log.d(TAG, "Sending test message: ${message.getIdAsHex()}")
            onCANMessageReceived(message)
        }
        
        Log.d(TAG, "Test messages sent. Check CAN Messages window.")
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
            
            // Log raw CAN data to CSV
            logToCSV("CAN_${message.getIdAsHex()}", message.getDataAsHex().toDoubleOrNull() ?: 0.0, "raw")
            
            // Log decoded signals based on CAN ID
            when (message.id) {
                0x1D0L -> {
                    if (message.length >= 4) {
                        val speedRaw = (message.data[2].toInt() and 0xFF) or ((message.data[3].toInt() and 0xFF) shl 8)
                        val speed = speedRaw * 0.01
                        logDecodedCANSignal("1D0", "Vehicle Speed", speed, "km/h")
                    }
                }
                0x348L -> {
                    if (message.length >= 1) {
                        val soc = (message.data[0].toInt() and 0xFF) * 0.5
                        logDecodedCANSignal("348", "Battery SOC", soc, "%")
                    }
                }
                0x3D3L -> {
                    if (message.length >= 2) {
                        val voltageRaw = (message.data[0].toInt() and 0xFF) or ((message.data[1].toInt() and 0xFF) shl 8)
                        val voltage = voltageRaw * 0.1
                        logDecodedCANSignal("3D3", "HV Battery Voltage", voltage, "V")
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
            }
            
            // Try to get the CAN data fragment
            val canFragment = getCANDataFragment()
            if (canFragment != null) {
                Log.d(TAG, "Found CANDataFragment, adding message")
                canFragment.addCANMessage(message)
            } else {
                // Store message in buffer until fragment is available
                Log.d(TAG, "CANDataFragment not found - storing message in buffer")
                canMessageBuffer.add(message)
                Log.d(TAG, "Buffer now contains ${canMessageBuffer.size} messages")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCANMessageReceived", e)
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
    
    // CSV logging for CAN data
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
                    Log.d(TAG, "Received CAN message from Macchina A0: ${message.id.toString(16).uppercase()}")
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
        diagnostics.append("- Connection Status: ${getConnectionStatus()}\n")
        diagnostics.append("- Is Connected: ${isConnected()}\n\n")
        
        diagnostics.append("Next Steps:\n")
        diagnostics.append("1. Connect to Macchina A0 via Bluetooth or WiFi\n")
        diagnostics.append("2. Implement SLCAN protocol communication\n")
        diagnostics.append("3. Start receiving real CAN data from Polestar 2\n\n")
        
        diagnostics.append("This app only works with real CAN data from\n")
        diagnostics.append("your Polestar 2 via Macchina A0 OBD reader.\n")
        
        return diagnostics.toString()
    }
}