package Polestar.Companion

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import Polestar.Companion.databinding.ActivityConnectionSettingsBinding
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

class ConnectionSettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityConnectionSettingsBinding
    private lateinit var connectionManager: MachinnaA0ConnectionManager
    private var detectedPort: Int? = null
    private var detectedIP: String? = null
    
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
        
        // Add a test button to show current WiFi status
        binding.btnTestDisplay.setOnClickListener {
            showCurrentWiFiStatus()
                }
    }
    
    private fun loadConnectionSettings() {
        // Only WiFi connection is supported now
        binding.radioWifi.isChecked = true
        binding.layoutWifiSettings.visibility = View.VISIBLE
        
        // IP and Port are now fixed - no need to load from settings
        // IP: 192.168.4.1 (fixed)
        // Ports: 23, 35000 (auto-detected)
    }
    
    private fun saveSettings() {
        val prefs = getSharedPreferences("connection_settings", MODE_PRIVATE)
        val editor = prefs.edit()
        
        // Save connection type
        editor.putString("connection_type", "wifi")
        
        // Save fixed WiFi settings
        editor.putString("wifi_ip", "192.168.4.1")
        editor.putInt("wifi_port", 23) // Default port, will try 35000 if needed
        
        editor.apply()
        
        Toast.makeText(this, "Auto-connection settings saved", Toast.LENGTH_SHORT).show()
        
        // Return to main activity
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
    

    /**
     * Get the current WiFi SSID the phone is connected to
     */
    private fun getCurrentWiFiSSID(): String? {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ssid = wifiInfo.ssid

            Log.d("ConnectionSettings", "Raw WiFi SSID: '$ssid'")
            Log.d("ConnectionSettings", "WiFi Info: ${wifiInfo.toString()}")

            // Remove quotes if present
            val cleanSSID = if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid.substring(1, ssid.length - 1)
            } else {
                ssid
            }
            
            Log.d("ConnectionSettings", "Clean WiFi SSID: '$cleanSSID'")
            cleanSSID
        } catch (e: Exception) {
            Log.e("ConnectionSettings", "Error getting WiFi SSID: ${e.message}", e)
            null
        }
    }
    
    private fun testConnection() {
        lifecycleScope.launch {
            try {
                // Save current settings first
                saveSettings()
                
                // Use auto-detection approach
                autoDetectAndConnect()
                
            } catch (e: Exception) {
                Log.e("ConnectionSettings", "Error during connection test: ${e.message}", e)
                showConnectionError("Connection test failed: ${e.message}")
            }
        }
    }
    
    private suspend fun autoDetectAndConnect() {
        try {
            Log.d("ConnectionSettings", "Starting auto-detect of Macchina A0...")
            Toast.makeText(this@ConnectionSettingsActivity, "🔍 Scanning for Macchina A0...", Toast.LENGTH_SHORT).show()
            
            // First, try to detect the Macchina A0 IP address
            detectedIP = MacchinaAutoDetect.scanForMacchinaA0(this@ConnectionSettingsActivity)
            if (detectedIP == null) {
                Log.e("ConnectionSettings", "❌ No Macchina A0 device found on network")
                showConnectionError("No Macchina A0 device found on network. Please ensure:\n" +
                        "1. Phone is connected to Macchina A0 WiFi network\n" +
                        "2. Macchina A0 is powered and connected to OBD port")
                return
            }
            
            Log.i("ConnectionSettings", "Detected Macchina A0 at: $detectedIP")
            Toast.makeText(this@ConnectionSettingsActivity, "🔍 Found device at $detectedIP, detecting port...", Toast.LENGTH_SHORT).show()
            
            // Now detect the working port
            detectedPort = MacchinaAutoDetect.detectCanPort(detectedIP!!)
            
            if (detectedPort != null) {
                Log.i("ConnectionSettings", "Detected working port: $detectedPort")
                Toast.makeText(this@ConnectionSettingsActivity, "✅ Found Macchina A0 at $detectedIP:$detectedPort", Toast.LENGTH_SHORT).show()
                // Note: Actual connection is handled by MainActivity's direct TCP approach
            } else {
                Log.e("ConnectionSettings", "❌ No working GVRET ports found on $detectedIP")
                showConnectionError("No working GVRET ports found on $detectedIP. Please ensure:\n" +
                        "1. Macchina A0 is powered and connected to OBD port\n" +
                        "2. GVRET protocol is enabled on the device")
            }
        } catch (e: Exception) {
            Log.e("ConnectionSettings", "Error during auto-detection: ${e.message}", e)
            showConnectionError("Auto-detection failed: ${e.message}")
        }
    }
    
    /**
     * Auto-detect working port by testing basic TCP connectivity
     */
    private suspend fun detectAndConnect(ip: String): Boolean {
        // Only test GVRET ports for Macchina A0
        val candidatePorts = listOf(23, 35000)
        
        Log.i("ConnectionSettings", "Starting GVRET port scan on $ip")
        
        for (port in candidatePorts) {
            try {
                Log.i("ConnectionSettings", "Testing GVRET port $port")
                
                // Test basic TCP connection with proper timeout
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), 3000) // 3s timeout
                Log.i("ConnectionSettings", "✅ TCP connection successful on port $port")
                socket.close()
                
                // Port is open, connection successful
                Log.i("ConnectionSettings", "✅ Successfully found open port $port")
                return true
                
            } catch (e: Exception) {
                Log.d("ConnectionSettings", "Port $port not accessible: ${e.message}")
                // Try next port
            }
        }
        
        Log.e("ConnectionSettings", "❌ No GVRET ports found on $ip")
        return false
    }
    
    /**
     * Detect working GVRET port with improved timeout handling
     */
    private suspend fun detectPort(ip: String): Int? {
        val candidatePorts = listOf(35000, 23) // Try 35000 first (more common for Macchina A0)
        
        for (port in candidatePorts) {
            try {
                Log.d("ConnectionSettings", "Testing GVRET port $port on $ip")
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), 2000) // 2s timeout
                socket.close()
                Log.i("ConnectionSettings", "✅ GVRET port $port is open on $ip")
                return port
            } catch (e: Exception) {
                Log.d("ConnectionSettings", "GVRET port $port not accessible: ${e.message}")
                // Try next port
            }
        }
        return null
    }

    /**
     * Get the phone's current IP address and network range
     */
    private fun getPhoneNetworkInfo(): Pair<String, String>? {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcpInfo = wifiManager.dhcpInfo
            val phoneIP = intToIp(dhcpInfo.ipAddress)
            val networkMask = intToIp(dhcpInfo.netmask)
            
            Log.i("ConnectionSettings", "Phone IP: $phoneIP, Network: $networkMask")
            Pair(phoneIP, networkMask)
        } catch (e: Exception) {
            Log.e("ConnectionSettings", "Error getting network info: ${e.message}")
            null
        }
    }
    
    /**
     * Convert integer IP to string format
     */
    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }
    
    /**
     * Scan network for potential Macchina A0 devices
     */
    private suspend fun scanForMacchinaA0(): String? {
        val networkInfo = getPhoneNetworkInfo()
        if (networkInfo == null) {
            Log.w("ConnectionSettings", "Could not get network info, using default IP")
            return "192.168.4.1"
        }
        
        val (phoneIP, networkMask) = networkInfo
        Log.i("ConnectionSettings", "Scanning network for Macchina A0 devices...")
        
        // Try common Macchina A0 IPs first
        val commonIPs = listOf(
            "192.168.4.1",  // Default Macchina A0 AP mode
            "192.168.1.100", // Common static IP
            "192.168.0.100", // Alternative static IP
            "10.0.0.1"       // Some configurations
        )
        
        // Test common IPs first
        for (ip in commonIPs) {
            if (testBasicConnectivity(ip)) {
                Log.i("ConnectionSettings", "✅ Found responsive device at $ip")
                return ip
            }
        }
        
        // If no common IPs work, try scanning the local network
        return scanLocalNetwork(phoneIP, networkMask)
    }
    
    /**
     * Scan local network for responsive devices
     */
    private suspend fun scanLocalNetwork(phoneIP: String, networkMask: String): String? {
        try {
            // Extract network base (e.g., 192.168.1.0 from 192.168.1.100)
            val networkBase = phoneIP.substring(0, phoneIP.lastIndexOf('.'))
            Log.i("ConnectionSettings", "Scanning network range: $networkBase.1-254")
            
            // Scan common ranges
            val ranges = listOf(
                (1..10),      // Common gateway range
                (100..110),   // Common device range
                (200..210)    // Alternative device range
            )
            
            for (range in ranges) {
                for (i in range) {
                    val testIP = "$networkBase.$i"
                    if (testIP != phoneIP && testBasicConnectivity(testIP)) {
                        Log.i("ConnectionSettings", "✅ Found responsive device at $testIP")
                        return testIP
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ConnectionSettings", "Error scanning network: ${e.message}")
        }
        
        return null
    }
    
    /**
     * Test basic network connectivity to a specific IP using GVRET ports
     */
    private suspend fun testBasicConnectivity(ip: String): Boolean {
        // Try port 35000 first (more common for Macchina A0), then port 23
        val portsToTry = listOf(35000, 23)
        
        for (port in portsToTry) {
            try {
                Log.d("ConnectionSettings", "Testing GVRET connectivity to $ip:$port")
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, port), 1000) // 1s timeout for scanning
                socket.close()
                Log.d("ConnectionSettings", "✅ $ip is reachable on GVRET port $port")
                return true
            } catch (e: Exception) {
                Log.d("ConnectionSettings", "❌ $ip not reachable on GVRET port $port: ${e.message}")
                // Try next port
            }
        }
        return false
    }

    private fun attemptConnection() {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@ConnectionSettingsActivity, "🔍 Scanning for Macchina A0...", Toast.LENGTH_SHORT).show()

                // First, try to detect the Macchina A0 IP address
                val detectedIP = scanForMacchinaA0()
                if (detectedIP == null) {
                    Toast.makeText(this@ConnectionSettingsActivity, "❌ No Macchina A0 device found on network", Toast.LENGTH_LONG).show()
                    return@launch
                }

                Log.i("ConnectionSettings", "Detected Macchina A0 at: $detectedIP")
                Toast.makeText(this@ConnectionSettingsActivity, "🔍 Found device at $detectedIP, scanning ports...", Toast.LENGTH_SHORT).show()

                // Use improved port detection
                val port = detectPort(detectedIP)
                if (port != null) {
                    Log.i("ConnectionSettings", "Found working port: $port")
                    Toast.makeText(this@ConnectionSettingsActivity, "✅ Found Macchina A0 at $detectedIP:$port", Toast.LENGTH_SHORT).show()
                    // Note: Actual connection is handled by MainActivity's direct TCP approach
                } else {
                    Toast.makeText(this@ConnectionSettingsActivity, "❌ No GVRET server found on $detectedIP", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ConnectionSettings", "Connection error: ${e.message}", e)
                Toast.makeText(this@ConnectionSettingsActivity, "Connection error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showConnectionError(message: String) {
        // Safe way to show AlertDialog without leaking window
        if (!isFinishing && !isDestroyed) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Connection Error")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }
    
    private fun showMacchinaConnectionInstructions(currentSSID: String?) {
        // Prevent WindowLeaked error by checking if activity is still valid
        if (isFinishing || isDestroyed) return
        
        val message = """
            WiFi Connection Issue:
            
            Current WiFi: '$currentSSID'
            Expected: A0RETSSID
            
            To connect to Macchina A0:
            
            1. Go to Android WiFi Settings
            2. Connect to "A0RETSSID" network
            3. Return to this app
            4. Press "Test Connection" again
            
            The Macchina A0 creates its own WiFi network that your phone must connect to first.
            
            Note: If you're already connected to A0RETSSID, there may be a detection issue.
        """.trimIndent()
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Connect to Macchina A0 WiFi")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }
    
    
    private fun showCurrentWiFiStatus() {
        val currentSSID = getCurrentWiFiSSID()
        val macchinaSSIDs = listOf("A0RETSSID", "ESP32RET", "GVRET", "Macchina A0 Wifi")
        val isConnectedToMacchina = currentSSID != null && macchinaSSIDs.any { 
            currentSSID.contains(it, ignoreCase = true) 
        }
        
        val message = """
            WiFi Status Debug:
            
            Current SSID: '$currentSSID'
            Is Macchina A0: $isConnectedToMacchina
            Expected SSIDs: $macchinaSSIDs
            
            Raw WiFi Info:
            ${try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                "SSID: '${wifiInfo.ssid}'\nBSSID: ${wifiInfo.bssid}\nIP: ${wifiInfo.ipAddress}\nLink Speed: ${wifiInfo.linkSpeed} Mbps"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }}
        """.trimIndent()
        
        android.app.AlertDialog.Builder(this)
            .setTitle("WiFi Status")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
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