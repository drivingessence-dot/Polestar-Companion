package Polestar.Companion

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.Log
import org.json.JSONObject
import Polestar.Companion.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    
    companion object {
        private const val TAG = "MainActivity"
        // Used to load the 'Companion' library on application startup.
        init {
            System.loadLibrary("Companion")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize the app
        initializeApp()
        
        // Set up UI event handlers
        setupUI()
        
        // Start data update loop
        startDataUpdateLoop()
    }
    
    private fun initializeApp() {
        binding.statusText.text = stringFromJNI()
        
        // Initialize OBD monitor
        if (initializeOBDMonitor()) {
            Log.i(TAG, "OBD Monitor initialized successfully")
            binding.statusText.text = "OBD Monitor Initialized"
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
    }
    
    private fun startMonitoring() {
        if (!isMonitoring) {
            if (startOBDMonitoring()) {
                isMonitoring = true
                binding.statusText.text = "Monitoring Active"
                binding.btnStartMonitoring.isEnabled = false
                binding.btnStopMonitoring.isEnabled = true
                Toast.makeText(this, "OBD Monitoring Started", Toast.LENGTH_SHORT).show()
                Log.i(TAG, "OBD Monitoring started")
            } else {
                Toast.makeText(this, "Failed to start monitoring", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Failed to start OBD monitoring")
            }
        }
    }
    
    private fun stopMonitoring() {
        if (isMonitoring) {
            stopOBDMonitoring()
            isMonitoring = false
            binding.statusText.text = "Monitoring Stopped"
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
            
            // Update UI with vehicle data
            binding.textVin.text = "VIN: ${jsonObject.optString("vin", "N/A")}"
            binding.textSoc.text = "Battery SOC: ${jsonObject.optInt("soc", -1)}%"
            binding.textVoltage.text = "12V Battery: ${jsonObject.optDouble("voltage", -1.0)}V"
            binding.textAmbient.text = "Ambient Temp: ${jsonObject.optInt("ambient", -100)}°C"
            binding.textSpeed.text = "Speed: ${jsonObject.optInt("speed", -1)} km/h"
            binding.textOdometer.text = "Odometer: ${jsonObject.optInt("odometer", -1)} km"
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
                }
                // Schedule next update in 5 seconds
                handler.postDelayed(this, 5000)
            }
        }
        handler.post(runnable)
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
}