package Polestar.Companion

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import Polestar.Companion.databinding.ActivityConnectionSettingsBinding
import kotlinx.coroutines.runBlocking

class ConnectionSettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityConnectionSettingsBinding
    private lateinit var connectionManager: MachinnaA0ConnectionManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        title = "Macchina A0 Connection Settings"
        
        // Initialize connection manager
        connectionManager = MachinnaA0ConnectionManager(this)
        
        setupUI()
        loadConnectionSettings()
    }
    
    private fun setupUI() {
        // Set up button listeners
                binding.btnSaveSettings.setOnClickListener {
                    saveSettings()
                }
                
        binding.btnTestConnection.setOnClickListener {
            testConnection()
        }
        
                binding.btnTroubleshoot.setOnClickListener {
                    troubleshootConnection()
                }
                
                binding.btnTestDisplay.setOnClickListener {
                    testCANDisplay()
        }
    }
    
    private fun loadConnectionSettings() {
        val prefs = getSharedPreferences("connection_settings", MODE_PRIVATE)
        val wifiIp = prefs.getString("wifi_ip", "192.168.4.1") ?: "192.168.4.1"
        val wifiPort = prefs.getInt("wifi_port", 23)
        
        // Only WiFi connection is supported now
            binding.radioWifi.isChecked = true
            binding.layoutWifiSettings.visibility = View.VISIBLE
        
        // Set WiFi settings
        binding.editWifiIp.setText(wifiIp)
        binding.editWifiPort.setText(wifiPort.toString())
    }
    
    private fun saveSettings() {
        val prefs = getSharedPreferences("connection_settings", MODE_PRIVATE)
        val editor = prefs.edit()
        
        // Save connection type
        editor.putString("connection_type", "wifi")
        
        // Save WiFi settings
        val wifiIp = binding.editWifiIp.text.toString().trim()
        val wifiPort = binding.editWifiPort.text.toString().toIntOrNull() ?: 23
        
        if (wifiIp.isNotEmpty()) {
            editor.putString("wifi_ip", wifiIp)
            editor.putInt("wifi_port", wifiPort)
            
            editor.apply()
            
            Toast.makeText(this, "Settings saved successfully", Toast.LENGTH_SHORT).show()
            
            // Return to main activity
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        } else {
            Toast.makeText(this, "Please enter a valid IP address", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun testConnection() {
        try {
            // Save current settings first
            saveSettings()
            
            // Get MainActivity instance and test connection
                        val mainActivity = MainActivity.getInstance()
            if (mainActivity != null) {
                mainActivity.testMacchinaA0Connection()
                    } else {
                Toast.makeText(this, "Cannot test connection - MainActivity not available", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
            Toast.makeText(this, "Error testing connection: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun troubleshootConnection() {
        try {
            // Get MainActivity instance and show troubleshooting
            val mainActivity = MainActivity.getInstance()
            if (mainActivity != null) {
                mainActivity.troubleshootGVRETConnection()
            } else {
                Toast.makeText(this, "Cannot troubleshoot - MainActivity not available", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error in troubleshooting: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun testCANDisplay() {
        try {
            // Get MainActivity instance and test CAN display
            val mainActivity = MainActivity.getInstance()
            if (mainActivity != null) {
                mainActivity.testCANMessageDisplay()
            } else {
                Toast.makeText(this, "Cannot test display - MainActivity not available", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error testing CAN display: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}