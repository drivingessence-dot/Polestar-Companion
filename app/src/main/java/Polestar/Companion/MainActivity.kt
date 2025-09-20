package Polestar.Companion

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.Log
import android.content.Intent
import android.content.SharedPreferences
import org.json.JSONObject
import Polestar.Companion.databinding.ActivityMainBinding
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    private lateinit var sharedPreferences: SharedPreferences
    private val decimalFormat = DecimalFormat("#.##")
    
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

        // Initialize the app
        initializeApp()
        
        // Set up UI event handlers
        setupUI()
        
        // Start data update loop
        startDataUpdateLoop()
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
        binding.statusText.text = stringFromJNI()
        
        // Initialize OBD monitor
        if (initializeOBDMonitor()) {
            Log.i(TAG, "OBD Monitor initialized successfully")
            binding.statusText.text = getConnectionStatus()
        } else {
            Log.e(TAG, "Failed to initialize OBD Monitor")
            binding.statusText.text = "Failed to Initialize OBD Monitor"
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
            binding.statusText.text = "Connecting to OBD Reader..."
            binding.btnStartMonitoring.isEnabled = false
            Toast.makeText(this, "Connecting to OBD Reader...", Toast.LENGTH_SHORT).show()
            
            // Start monitoring in a background thread to avoid blocking UI
            Thread {
                val success = startOBDMonitoring()
                
                // Update UI on main thread
                handler.post {
                    if (success) {
                        isMonitoring = true
                        binding.statusText.text = getConnectionStatus()
                        binding.btnStartMonitoring.isEnabled = false
                        binding.btnStopMonitoring.isEnabled = true
                        Toast.makeText(this@MainActivity, "OBD Monitoring Started", Toast.LENGTH_SHORT).show()
                        Log.i(TAG, "OBD Monitoring started")
                    } else {
                        binding.statusText.text = getConnectionStatus()
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
            binding.statusText.text = getConnectionStatus()
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
    
    private fun startDataUpdateLoop() {
        val runnable = object : Runnable {
            override fun run() {
                if (isMonitoring) {
                    updateVehicleData()
                } else {
                    // Update connection status even when not monitoring
                    updateConnectionStatus()
                }
                // Schedule next update in 200ms for 5Hz refresh rate
                // Optimized for Pixel 8 Pro performance
                handler.postDelayed(this, 200)
            }
        }
        handler.post(runnable)
    }
    
    private fun updateConnectionStatus() {
        val status = getConnectionStatus()
        if (binding.statusText.text != status) {
            binding.statusText.text = status
        }
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