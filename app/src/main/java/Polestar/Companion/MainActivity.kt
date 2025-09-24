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
        // Used to load the 'Companion' library on application startup.
        init {
            System.loadLibrary("Companion")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
            
            // Update UI with vehicle data
            val vin = jsonObject.optString("vin", "N/A")
            getMainContentFragment()?.getFragmentBinding()?.textVin?.text = "VIN: $vin"
            
            // Store VIN for SOH baseline calculation
            if (vin != "N/A") {
                sharedPreferences.edit().putString("vehicle_vin", vin).apply()
            }
            getMainContentFragment()?.getFragmentBinding()?.textSoc?.text = "Battery SOC: ${jsonObject.optInt("soc", -1)}%"
            getMainContentFragment()?.getFragmentBinding()?.textVoltage?.text = "12V Battery: ${decimalFormat.format(jsonObject.optDouble("voltage", -1.0))}V"
            
            // Convert temperature
            val ambientCelsius = jsonObject.optInt("ambient", -100)
            val ambientText = if (useImperialUnits && ambientCelsius != -100) {
                val fahrenheit = (ambientCelsius * 9.0 / 5.0) + 32.0
                "Ambient Temp: ${decimalFormat.format(fahrenheit)}°F"
            } else {
                "Ambient Temp: ${if (ambientCelsius != -100) "${ambientCelsius}°C" else "N/A"}"
            }
            getMainContentFragment()?.getFragmentBinding()?.textAmbient?.text = ambientText
            
            // Convert speed
            val speedKmh = jsonObject.optInt("speed", -1)
            val speedText = if (useImperialUnits && speedKmh != -1) {
                val mph = speedKmh * 0.621371
                "Speed: ${decimalFormat.format(mph)} mph"
            } else {
                "Speed: ${if (speedKmh != -1) "${speedKmh} km/h" else "N/A"}"
            }
            getMainContentFragment()?.getFragmentBinding()?.textSpeed?.text = speedText
            
            // Convert odometer
            val odometerKm = jsonObject.optInt("odometer", -1)
            val odometerText = if (useImperialUnits && odometerKm != -1) {
                val miles = odometerKm * 0.621371
                "Odometer: ${decimalFormat.format(miles)} mi"
            } else {
                "Odometer: ${if (odometerKm != -1) "${odometerKm} km" else "N/A"}"
            }
            getMainContentFragment()?.getFragmentBinding()?.textOdometer?.text = odometerText
            
            // Update gear
            getMainContentFragment()?.getFragmentBinding()?.textGear?.text = "Gear: ${jsonObject.optString("gear", "N/A")}"
            
            // Update RSSI
            val rssi = jsonObject.optInt("rssi", -1)
            getMainContentFragment()?.getFragmentBinding()?.textRssi?.text = "Signal: ${if (rssi != -1) "${rssi} dBm" else "N/A"}"
            
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
            // BECM cannot be reached - show NULL
            getMainContentFragment()?.getFragmentBinding()?.textSoh?.text = "Battery SOH: NULL"
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
                "Battery SOH: NULL"
            }
            getMainContentFragment()?.getFragmentBinding()?.textSoh?.text = sohText
            
            if (sohValue < 0) {
                showSOHError("Failed to read SOH from BECM. Please check CAN communication.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing SOH data", e)
            getMainContentFragment()?.getFragmentBinding()?.textSoh?.text = "Battery SOH: NULL"
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
            
            // Update UI with vehicle data
            val vin = jsonObject.optString("vin", "N/A")
            getMainContentFragment()?.getFragmentBinding()?.textVin?.text = "VIN: $vin"
            
            // Store VIN for SOH baseline calculation
            if (vin != "N/A") {
                sharedPreferences.edit().putString("vehicle_vin", vin).apply()
            }
            getMainContentFragment()?.getFragmentBinding()?.textSoc?.text = "Battery SOC: ${jsonObject.optInt("soc", -1)}%"
            getMainContentFragment()?.getFragmentBinding()?.textVoltage?.text = "12V Battery: ${decimalFormat.format(jsonObject.optDouble("voltage", -1.0))}V"
            
            // Convert temperature
            val ambientCelsius = jsonObject.optInt("ambient", -100)
            val ambientText = if (useImperialUnits && ambientCelsius != -100) {
                val fahrenheit = (ambientCelsius * 9.0 / 5.0) + 32.0
                "Ambient Temp: ${decimalFormat.format(fahrenheit)}°F"
            } else {
                "Ambient Temp: $ambientCelsius°C"
            }
            getMainContentFragment()?.getFragmentBinding()?.textAmbient?.text = ambientText
            
            // Convert speed
            val speedKmh = jsonObject.optInt("speed", -1)
            val speedText = if (useImperialUnits && speedKmh != -1) {
                val mph = speedKmh * 0.621371
                "Speed: ${decimalFormat.format(mph)} mph"
            } else {
                "Speed: $speedKmh km/h"
            }
            getMainContentFragment()?.getFragmentBinding()?.textSpeed?.text = speedText
            
            // Convert odometer
            val odometerKm = jsonObject.optInt("odometer", -1)
            val odometerText = if (useImperialUnits && odometerKm != -1) {
                val miles = odometerKm * 0.621371
                "Odometer: ${decimalFormat.format(miles)} miles"
            } else {
                "Odometer: $odometerKm km"
            }
            getMainContentFragment()?.getFragmentBinding()?.textOdometer?.text = odometerText
            
            getMainContentFragment()?.getFragmentBinding()?.textGear?.text = "Gear: ${jsonObject.optString("gear", "U")}"
            getMainContentFragment()?.getFragmentBinding()?.textRssi?.text = "Signal: ${jsonObject.optInt("rssi", -1)} dBm"
            
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
    external fun stopRawCANCapture()
    external fun isRawCANCaptureActive(): Boolean
    external fun isCANInterfaceReady(): Boolean
    
    // Method to receive CAN messages from native library
    fun onCANMessageReceived(message: CANMessage) {
        // Find the CAN data fragment and add the message
        val canFragment = supportFragmentManager.fragments.find { it is CANDataFragment } as? CANDataFragment
        canFragment?.addCANMessage(message)
    }
}