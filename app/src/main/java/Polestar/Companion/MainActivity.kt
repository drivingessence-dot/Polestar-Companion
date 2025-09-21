package Polestar.Companion

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.Log
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
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
    
    // Performance optimizations for Pixel 8 Pro
    private val dataUpdateScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var dataUpdateJob: Job? = null
    
    // Cached values to reduce UI updates
    private var lastVehicleData: String = ""
    private var lastStatusText: String = ""
    
    companion object {
        private const val TAG = "MainActivity"
        // Used to load the 'Companion' library on application startup.
        init {
            System.loadLibrary("Companion")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize shared preferences first
        sharedPreferences = getSharedPreferences("PolestarCompanionPrefs", MODE_PRIVATE)
        
        // Apply theme before setting content view
        applyTheme()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Optimize for Pixel 8 Pro performance
        optimizeForModernDevices()
        
        // Initialize the app
        initializeApp()
        
        // Set up UI event handlers
        setupUI()
        
        // Start optimized data update loop
        startOptimizedDataUpdateLoop()
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
        updateConnectionStatusUI(stringFromJNI())
        
        // Initialize OBD monitor
        if (initializeOBDMonitor()) {
            Log.i(TAG, "OBD Monitor initialized successfully")
            updateConnectionStatusUI(getConnectionStatus())
        } else {
            Log.e(TAG, "Failed to initialize OBD Monitor")
            updateConnectionStatusUI("Failed to Initialize OBD Monitor")
        }
    }
    
    private fun setupUI() {
        // Set up button click listeners
        binding.btnStartMonitoring.setOnClickListener {
            startMonitoring()
        }
        
        binding.btnStopMonitoring.setOnClickListener {
            stopMonitoring()
        }
        
        binding.btnRefreshData.setOnClickListener {
            updateVehicleData()
        }
        
        // Set up settings button
        binding.btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }
    
    private fun startMonitoring() {
        if (!isMonitoring) {
            updateConnectionStatusUI("Connecting to OBD Reader...")
            binding.btnStartMonitoring.isEnabled = false
            Toast.makeText(this, "Connecting to OBD Reader...", Toast.LENGTH_SHORT).show()
            
            // Start monitoring in a background thread to avoid blocking UI
            Thread {
                val success = startOBDMonitoring()
                
                // Update UI on main thread
                handler.post {
                    if (success) {
                        isMonitoring = true
                        updateConnectionStatusUI(getConnectionStatus())
                        binding.btnStartMonitoring.isEnabled = false
                        binding.btnStopMonitoring.isEnabled = true
                        Toast.makeText(this@MainActivity, "OBD Monitoring Started", Toast.LENGTH_SHORT).show()
                        Log.i(TAG, "OBD Monitoring started")
                    } else {
                        updateConnectionStatusUI(getConnectionStatus())
                        binding.btnStartMonitoring.isEnabled = true
                        binding.btnStopMonitoring.isEnabled = false
                        Toast.makeText(this@MainActivity, "Failed to start monitoring: ${getConnectionStatus()}", Toast.LENGTH_LONG).show()
                        Log.e(TAG, "Failed to start OBD monitoring: ${getConnectionStatus()}")
                    }
                }
            }.start()
        }
    }
    
    private fun stopMonitoring() {
        if (isMonitoring) {
            stopOBDMonitoring()
            isMonitoring = false
            updateConnectionStatusUI(getConnectionStatus())
            binding.btnStartMonitoring.isEnabled = true
            binding.btnStopMonitoring.isEnabled = false
            Toast.makeText(this, "OBD Monitoring Stopped", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "OBD Monitoring stopped")
        }
    }
    
    private fun updateVehicleData() {
        val dataJson = getVehicleData()
        try {
            val jsonObject = JSONObject(dataJson)
            val useImperialUnits = SettingsActivity.getImperialUnits(sharedPreferences)
            
            // Update UI with vehicle data
            binding.textVin.text = "VIN: ${jsonObject.optString("vin", "N/A")}"
            binding.textSoc.text = "Battery SOC: ${jsonObject.optInt("soc", -1)}%"
            binding.textVoltage.text = "12V Battery: ${decimalFormat.format(jsonObject.optDouble("voltage", -1.0))}V"
            
            // Convert temperature
            val ambientCelsius = jsonObject.optInt("ambient", -100)
            val ambientText = if (useImperialUnits && ambientCelsius != -100) {
                val fahrenheit = (ambientCelsius * 9.0 / 5.0) + 32.0
                "Ambient Temp: ${decimalFormat.format(fahrenheit)}°F"
            } else {
                "Ambient Temp: $ambientCelsius°C"
            }
            binding.textAmbient.text = ambientText
            
            // Convert speed
            val speedKmh = jsonObject.optInt("speed", -1)
            val speedText = if (useImperialUnits && speedKmh != -1) {
                val mph = speedKmh * 0.621371
                "Speed: ${decimalFormat.format(mph)} mph"
            } else {
                "Speed: $speedKmh km/h"
            }
            binding.textSpeed.text = speedText
            
            // Convert odometer
            val odometerKm = jsonObject.optInt("odometer", -1)
            val odometerText = if (useImperialUnits && odometerKm != -1) {
                val miles = odometerKm * 0.621371
                "Odometer: ${decimalFormat.format(miles)} miles"
            } else {
                "Odometer: $odometerKm km"
            }
            binding.textOdometer.text = odometerText
            
            binding.textGear.text = "Gear: ${jsonObject.optString("gear", "U")}"
            binding.textRssi.text = "Signal: ${jsonObject.optInt("rssi", -1)} dBm"
            
            Log.d(TAG, "Vehicle data updated: $dataJson")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing vehicle data JSON", e)
            Toast.makeText(this, "Error parsing vehicle data", Toast.LENGTH_SHORT).show()
        }
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
    
    private fun updateConnectionStatus() {
        val status = getConnectionStatus()
        updateConnectionStatusUI(status)
    }
    
    private fun updateConnectionStatusUI(status: String) {
        val isConnected = status.contains("Connected", ignoreCase = true) || 
                         status.contains("Monitoring", ignoreCase = true)
        
        // Update status text with emoji
        val statusWithEmoji = if (isConnected) {
            "✅ $status"
        } else {
            "❌ $status"
        }
        
        if (binding.statusText.text != statusWithEmoji) {
            binding.statusText.text = statusWithEmoji
            
            // Update card background color
            val cardColor = if (isConnected) {
                getColor(android.R.color.holo_green_light)
            } else {
                getColor(android.R.color.holo_red_light)
            }
            binding.connectionStatusCard.setCardBackgroundColor(cardColor)
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
            val isConnected = status.contains("Connected", ignoreCase = true) || 
                             status.contains("Monitoring", ignoreCase = true)
            
            // Update status text with emoji
            val statusWithEmoji = if (isConnected) {
                "✅ $status"
            } else {
                "❌ $status"
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
            binding.textVin.text = "VIN: ${jsonObject.optString("vin", "N/A")}"
            binding.textSoc.text = "Battery SOC: ${jsonObject.optInt("soc", -1)}%"
            binding.textVoltage.text = "12V Battery: ${decimalFormat.format(jsonObject.optDouble("voltage", -1.0))}V"
            
            // Convert temperature
            val ambientCelsius = jsonObject.optInt("ambient", -100)
            val ambientText = if (useImperialUnits && ambientCelsius != -100) {
                val fahrenheit = (ambientCelsius * 9.0 / 5.0) + 32.0
                "Ambient Temp: ${decimalFormat.format(fahrenheit)}°F"
            } else {
                "Ambient Temp: $ambientCelsius°C"
            }
            binding.textAmbient.text = ambientText
            
            // Convert speed
            val speedKmh = jsonObject.optInt("speed", -1)
            val speedText = if (useImperialUnits && speedKmh != -1) {
                val mph = speedKmh * 0.621371
                "Speed: ${decimalFormat.format(mph)} mph"
            } else {
                "Speed: $speedKmh km/h"
            }
            binding.textSpeed.text = speedText
            
            // Convert odometer
            val odometerKm = jsonObject.optInt("odometer", -1)
            val odometerText = if (useImperialUnits && odometerKm != -1) {
                val miles = odometerKm * 0.621371
                "Odometer: ${decimalFormat.format(miles)} miles"
            } else {
                "Odometer: $odometerKm km"
            }
            binding.textOdometer.text = odometerText
            
            binding.textGear.text = "Gear: ${jsonObject.optString("gear", "U")}"
            binding.textRssi.text = "Signal: ${jsonObject.optInt("rssi", -1)} dBm"
            
            Log.d(TAG, "Vehicle data updated: $dataJson")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing vehicle data JSON", e)
            Toast.makeText(this, "Error parsing vehicle data", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Native methods implemented by the 'Companion' native library
     */
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
}