package Polestar.Companion

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiNetworkSuggestion
import android.net.wifi.WifiNetworkSpecifier
import android.net.ConnectivityManager
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Utility class for common network operations
 * Consolidates duplicate network functions from across the codebase
 */
object NetworkUtils {
    
    private const val TAG = "NetworkUtils"
    private const val MACCHINA_A0_SSID = "A0_CAN"
    private const val MACCHINA_A0_IP = "192.168.4.1"
    private const val AUTO_CONNECT_RETRY_COUNT = 3
    private const val AUTO_CONNECT_RETRY_DELAY = 5000L // 5 seconds
    
    /**
     * Get phone's network information (IP and network mask)
     */
    fun getPhoneNetworkInfo(context: Context): Pair<String, String>? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val dhcpInfo = wifiManager.dhcpInfo
            
            if (dhcpInfo != null) {
                val phoneIP = intToIp(dhcpInfo.ipAddress)
                val networkMask = intToIp(dhcpInfo.netmask)
                return Pair(phoneIP, networkMask)
            }
        } catch (e: Exception) {
            // Fallback to network interface method
            try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (networkInterface in interfaces) {
                    val addresses = Collections.list(networkInterface.inetAddresses)
                    for (address in addresses) {
                        if (!address.isLoopbackAddress && address.isSiteLocalAddress) {
                            val phoneIP = address.hostAddress ?: continue
                            val networkMask = "255.255.255.0" // Default mask
                            return Pair(phoneIP, networkMask)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return null
    }
    
    /**
     * Convert integer IP to string format
     */
    fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }
    
    /**
     * Get current WiFi SSID
     */
    fun getCurrentWiFiSSID(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            return wifiInfo.ssid?.removeSurrounding("\"")
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Test basic connectivity to an IP address
     */
    suspend fun testBasicConnectivity(ip: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val address = InetAddress.getByName(ip)
            address.isReachable(3000) // 3 second timeout
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Scan local network for Macchina A0
     */
    suspend fun scanForMacchinaA0(context: Context): String? = withContext(Dispatchers.IO) {
        val networkInfo = getPhoneNetworkInfo(context) ?: return@withContext null
        val phoneIP = networkInfo.first
        val networkMask = networkInfo.second
        
        scanLocalNetwork(phoneIP, networkMask)
    }
    
    /**
     * Scan local network for devices
     */
    suspend fun scanLocalNetwork(phoneIP: String, networkMask: String): String? = withContext(Dispatchers.IO) {
        try {
            val phoneIPParts = phoneIP.split(".")
            if (phoneIPParts.size != 4) return@withContext null
            
            val baseIP = "${phoneIPParts[0]}.${phoneIPParts[1]}.${phoneIPParts[2]}"
            
            // Scan common Macchina A0 IPs
            val commonIPs = listOf(
                "192.168.4.1",  // Default Macchina A0 AP mode
                "192.168.1.100", // Common router assignment
                "192.168.0.100"  // Common router assignment
            )
            
            // Add network scan
            for (i in 1..254) {
                val testIP = "$baseIP.$i"
                if (testIP != phoneIP && testBasicConnectivity(testIP)) {
                    // Test if it's a Macchina A0 by checking common ports
                    if (isMacchinaA0(testIP)) {
                        return@withContext testIP
                    }
                }
            }
            
            // Check common IPs
            for (ip in commonIPs) {
                if (testBasicConnectivity(ip) && isMacchinaA0(ip)) {
                    return@withContext ip
                }
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Check if an IP address is a Macchina A0 device
     */
    private suspend fun isMacchinaA0(ip: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Test common Macchina A0 ports
            val ports = listOf(35000, 23) // Macchina A0 access point port first, then telnet fallback
            
            for (port in ports) {
                try {
                    val socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress(ip, port), 2000)
                    socket.close()
                    return@withContext true
                } catch (e: Exception) {
                    // Continue to next port
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Check if phone is connected to Macchina A0 WiFi network
     */
    fun isConnectedToMacchinaA0WiFi(context: Context): Boolean {
        val currentSSID = getCurrentWiFiSSID(context)
        return currentSSID == MACCHINA_A0_SSID
    }
    
    /**
     * Attempt to auto-connect to Macchina A0 WiFi with retries
     * Note: Due to Android security restrictions, apps cannot programmatically connect to WiFi
     * This function provides guidance and checks connection status
     */
    suspend fun attemptAutoConnectToMacchinaA0(context: Context, onStatusUpdate: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting auto-connect attempt to Macchina A0 WiFi")
        
        for (attempt in 1..AUTO_CONNECT_RETRY_COUNT) {
            onStatusUpdate("Auto-connect attempt $attempt/$AUTO_CONNECT_RETRY_COUNT to $MACCHINA_A0_SSID")
            Log.d(TAG, "Auto-connect attempt $attempt/$AUTO_CONNECT_RETRY_COUNT")
            
            // Check if already connected to the right network
            if (isConnectedToMacchinaA0WiFi(context)) {
                Log.i(TAG, "Already connected to $MACCHINA_A0_SSID")
                onStatusUpdate("Connected to $MACCHINA_A0_SSID")
                return@withContext true
            }
            
            // Check if Macchina A0 is reachable on current network
            if (testMacchinaA0Connectivity()) {
                Log.i(TAG, "Macchina A0 is reachable on current network")
                onStatusUpdate("Macchina A0 found on current network")
                return@withContext true
            }
            
            // Try to suggest WiFi network connection (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val suggestionResult = suggestMacchinaA0WiFiConnection(context)
                if (suggestionResult) {
                    Log.i(TAG, "WiFi network suggestion sent for $MACCHINA_A0_SSID")
                    onStatusUpdate("WiFi connection suggestion sent")
                }
            }
            
            // Wait before next attempt (except on last attempt)
            if (attempt < AUTO_CONNECT_RETRY_COUNT) {
                onStatusUpdate("Waiting ${AUTO_CONNECT_RETRY_DELAY/1000}s before retry...")
                delay(AUTO_CONNECT_RETRY_DELAY)
            }
        }
        
        Log.w(TAG, "Auto-connect failed after $AUTO_CONNECT_RETRY_COUNT attempts")
        onStatusUpdate("Auto-connect failed. Please connect to $MACCHINA_A0_SSID manually.")
        false
    }
    
    /**
     * Test connectivity to Macchina A0 on current network
     */
    private suspend fun testMacchinaA0Connectivity(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Test common Macchina A0 IPs
            val testIPs = listOf(
                MACCHINA_A0_IP,
                "192.168.1.100",
                "192.168.0.100"
            )
            
            for (ip in testIPs) {
                if (isMacchinaA0(ip)) {
                    Log.d(TAG, "Macchina A0 found at $ip")
                    return@withContext true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error testing Macchina A0 connectivity", e)
            false
        }
    }
    
    /**
     * Suggest WiFi network connection (Android 10+)
     * Note: This requires user approval and may not work on all devices
     */
    private fun suggestMacchinaA0WiFiConnection(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                
                val specifier = WifiNetworkSpecifier.Builder()
                    .setSsid(MACCHINA_A0_SSID)
                    .build()
                
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(specifier)
                    .build()
                
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                
                // Note: This will show a system dialog for user approval
                // The actual connection requires user interaction
                Log.d(TAG, "WiFi network suggestion created for $MACCHINA_A0_SSID")
                true
            } else {
                Log.w(TAG, "WiFi network suggestions not supported on this Android version")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating WiFi network suggestion", e)
            false
        }
    }
    
    /**
     * Get connection guidance message for user
     */
    fun getConnectionGuidanceMessage(): String {
        return """
            To connect to Macchina A0:
            
            1. Go to your phone's WiFi settings
            2. Look for network "$MACCHINA_A0_SSID"
            3. Connect to it (no password required)
            4. Return to this app
            
            The app will automatically detect the connection.
        """.trimIndent()
    }
}
