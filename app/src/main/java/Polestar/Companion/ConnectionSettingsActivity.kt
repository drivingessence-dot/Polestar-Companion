package Polestar.Companion

import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import Polestar.Companion.databinding.ActivityConnectionSettingsBinding
import kotlinx.coroutines.runBlocking

class ConnectionSettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityConnectionSettingsBinding
    private lateinit var connectionManager: MachinnaA0ConnectionManager
    private var selectedBluetoothDevice: BluetoothDevice? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        title = "Macchina A0 Connection Settings"
        
        // Check and request permissions
        checkPermissions()
        
        connectionManager = MachinnaA0ConnectionManager(this)
        
        setupUI()
        loadConnectionSettings()
    }
    
    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        
        // Check Bluetooth permissions
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.BLUETOOTH_SCAN)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }
    
    companion object {
        private const val REQUEST_PERMISSIONS = 1001
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_PERMISSIONS) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(this, "Some permissions were denied. Bluetooth features may not work properly.", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun setupUI() {
        // Connection type selection
        binding.radioBluetooth.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutBluetoothSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.layoutWifiSettings.visibility = View.GONE
        }
        
        binding.radioWifi.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutWifiSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.layoutBluetoothSettings.visibility = View.GONE
        }
        
        // Bluetooth device selection
        binding.spinnerBluetoothDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) { // Skip "Select Device" option
                    val devices = connectionManager.getAvailableBluetoothDevices()
                    selectedBluetoothDevice = devices[position - 1]
                } else {
                    selectedBluetoothDevice = null
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedBluetoothDevice = null
            }
        }
        
        // Test connection button
        binding.btnTestConnection.setOnClickListener {
            testConnection()
        }
        
        // Save settings button
        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
        }
        
        // Debug button to list all devices
        binding.btnTestConnection.setOnLongClickListener {
            showDebugInfo()
            true
        }
        
        // Refresh Bluetooth devices
        binding.btnRefreshBluetooth.setOnClickListener {
            refreshBluetoothDevices()
        }
    }
    
    private fun loadConnectionSettings() {
        val prefs = getSharedPreferences("connection_settings", MODE_PRIVATE)
        val connectionType = prefs.getString("connection_type", "bluetooth") ?: "bluetooth"
        val wifiIp = prefs.getString("wifi_ip", "192.168.0.10") ?: "192.168.0.10"
        val wifiPort = prefs.getInt("wifi_port", 35000)
        
        // Set connection type
        if (connectionType == "wifi") {
            binding.radioWifi.isChecked = true
            binding.layoutWifiSettings.visibility = View.VISIBLE
            binding.layoutBluetoothSettings.visibility = View.GONE
        } else {
            binding.radioBluetooth.isChecked = true
            binding.layoutBluetoothSettings.visibility = View.VISIBLE
            binding.layoutWifiSettings.visibility = View.GONE
        }
        
        // Set WiFi settings
        binding.editWifiIp.setText(wifiIp)
        binding.editWifiPort.setText(wifiPort.toString())
        
        // Load Bluetooth devices
        refreshBluetoothDevices()
    }
    
    private fun showDebugInfo() {
        val debugInfo = connectionManager.debugListAllDevices()
        AlertDialog.Builder(this)
            .setTitle("Debug: Available Bluetooth Devices")
            .setMessage(debugInfo)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    
    private fun refreshBluetoothDevices() {
        if (!connectionManager.isBluetoothAvailable()) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_SHORT).show()
            return
        }
        
        val devices = connectionManager.getAvailableBluetoothDevices()
        val deviceNames = mutableListOf("Select Device")
        
        devices.forEach { device ->
            deviceNames.add("${device.name ?: "Unknown Device"} (${device.address})")
        }
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerBluetoothDevices.adapter = adapter
        
        // Select previously selected device
        val prefs = getSharedPreferences("connection_settings", MODE_PRIVATE)
        val selectedAddress = prefs.getString("bluetooth_device_address", null)
        if (selectedAddress != null) {
            val index = devices.indexOfFirst { it.address == selectedAddress }
            if (index >= 0) {
                binding.spinnerBluetoothDevices.setSelection(index + 1) // +1 for "Select Device"
            }
        }
    }
    
    private fun testConnection() {
        val connectionType = if (binding.radioBluetooth.isChecked) "bluetooth" else "wifi"
        
        when (connectionType) {
            "bluetooth" -> {
                if (selectedBluetoothDevice == null) {
                    Toast.makeText(this, "Please select a Bluetooth device", Toast.LENGTH_SHORT).show()
                    return
                }
                
                testBluetoothConnection()
            }
            "wifi" -> {
                val ip = binding.editWifiIp.text.toString().trim()
                val portText = binding.editWifiPort.text.toString().trim()
                
                if (ip.isEmpty() || portText.isEmpty()) {
                    Toast.makeText(this, "Please enter WiFi IP and port", Toast.LENGTH_SHORT).show()
                    return
                }
                
                val port = portText.toIntOrNull()
                if (port == null || port <= 0 || port > 65535) {
                    Toast.makeText(this, "Please enter a valid port number", Toast.LENGTH_SHORT).show()
                    return
                }
                
                testWiFiConnection(ip, port)
            }
        }
    }
    
    private fun testBluetoothConnection() {
        binding.btnTestConnection.isEnabled = false
        binding.btnTestConnection.text = "Testing..."
        
        Thread {
            try {
                val success = runBlocking {
                    connectionManager.connectBluetooth(selectedBluetoothDevice?.address)
                }
                
                runOnUiThread {
                    binding.btnTestConnection.isEnabled = true
                    binding.btnTestConnection.text = "Test Connection"
                    
                    if (success) {
                        Toast.makeText(this, "Bluetooth connection successful!", Toast.LENGTH_SHORT).show()
                        runBlocking {
                            connectionManager.disconnect()
                        }
                    } else {
                        Toast.makeText(this, "Bluetooth connection failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.btnTestConnection.isEnabled = true
                    binding.btnTestConnection.text = "Test Connection"
                    Toast.makeText(this, "Connection test failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
    
    private fun testWiFiConnection(ip: String, port: Int) {
        binding.btnTestConnection.isEnabled = false
        binding.btnTestConnection.text = "Testing..."
        
        Thread {
            try {
                val success = runBlocking {
                    connectionManager.connectWiFi(ip, port)
                }
                
                runOnUiThread {
                    binding.btnTestConnection.isEnabled = true
                    binding.btnTestConnection.text = "Test Connection"
                    
                    if (success) {
                        Toast.makeText(this, "WiFi connection successful!", Toast.LENGTH_SHORT).show()
                        runBlocking {
                            connectionManager.disconnect()
                        }
                    } else {
                        Toast.makeText(this, "WiFi connection failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.btnTestConnection.isEnabled = true
                    binding.btnTestConnection.text = "Test Connection"
                    Toast.makeText(this, "Connection test failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
    
    private fun saveSettings() {
        val prefs = getSharedPreferences("connection_settings", MODE_PRIVATE)
        val editor = prefs.edit()
        
        val connectionType = if (binding.radioBluetooth.isChecked) "bluetooth" else "wifi"
        editor.putString("connection_type", connectionType)
        
        if (connectionType == "bluetooth") {
            selectedBluetoothDevice?.let { device ->
                editor.putString("bluetooth_device_address", device.address)
                editor.putString("bluetooth_device_name", device.name)
            }
        } else {
            val ip = binding.editWifiIp.text.toString().trim()
            val portText = binding.editWifiPort.text.toString().trim()
            val port = portText.toIntOrNull() ?: 35000
            
            editor.putString("wifi_ip", ip)
            editor.putInt("wifi_port", port)
        }
        
        editor.apply()
        
        Toast.makeText(this, "Connection settings saved", Toast.LENGTH_SHORT).show()
        
        // Return to main activity
        val resultIntent = Intent()
        resultIntent.putExtra("connection_type", connectionType)
        setResult(RESULT_OK, resultIntent)
        finish()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Thread {
            runBlocking {
                connectionManager.disconnect()
            }
        }.start()
    }
}
