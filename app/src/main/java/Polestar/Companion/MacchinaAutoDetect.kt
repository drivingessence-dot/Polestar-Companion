package Polestar.Companion

import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket
import android.util.Log

object MacchinaAutoDetect {
    
    private const val TAG = "MacchinaAutoDetect"
    
    // Common GVRET ports for Macchina A0
    private val candidatePorts = listOf(35000, 35001, 35002, 35003, 35004, 35005, 23, 22, 21)
    private const val TIMEOUT_MS = 300
    
    /**
     * Auto-detect the working GVRET port on the Macchina A0
     * @param host IP address of the Macchina A0 (default: 192.168.4.1)
     * @return The working port number, or null if none found
     */
    suspend fun detectCanPort(host: String = "192.168.4.1"): Int? = coroutineScope {
        Log.d(TAG, "Starting auto-detect of GVRET port on $host")
        
        val deferredList = candidatePorts.map { port ->
            async(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Testing port $port on $host")
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                        Log.i(TAG, "✅ Port $port is open on $host")
                        port
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "❌ Port $port not accessible on $host: ${e.message}")
                    null
                }
            }
        }
        
        val result = deferredList.awaitAll().firstOrNull { it != null }
        if (result != null) {
            Log.i(TAG, "🎯 Detected working GVRET port: $result on $host")
        } else {
            Log.e(TAG, "❌ No working GVRET ports found on $host")
        }
        result
    }
    
    /**
     * Test basic connectivity to a host
     * @param host IP address to test
     * @return true if host is reachable, false otherwise
     */
    suspend fun testConnectivity(host: String = "192.168.4.1"): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Testing basic connectivity to $host")
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, 80), 1000) // Try HTTP port first
                Log.d(TAG, "✅ $host is reachable")
                true
            }
        } catch (e: Exception) {
            Log.d(TAG, "❌ $host not reachable: ${e.message}")
            false
        }
    }
    
    /**
     * Get the phone's current network information
     * @param context Android context
     * @return Pair of (phoneIP, networkMask) or null if unable to get info
     */
    fun getPhoneNetworkInfo(context: android.content.Context): Pair<String, String>? {
        return try {
            val wifiManager = context.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val dhcpInfo = wifiManager.dhcpInfo
            val phoneIP = intToIp(dhcpInfo.ipAddress)
            val networkMask = intToIp(dhcpInfo.netmask)
            
            Log.i(TAG, "Phone IP: $phoneIP, Network: $networkMask")
            Pair(phoneIP, networkMask)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting network info: ${e.message}")
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
     * Scan for Macchina A0 devices on the network
     * @param context Android context
     * @return IP address of detected Macchina A0, or null if none found
     */
    suspend fun scanForMacchinaA0(context: android.content.Context): String? = coroutineScope {
        val networkInfo = getPhoneNetworkInfo(context)
        if (networkInfo == null) {
            Log.w(TAG, "Could not get network info, using default IP")
            return@coroutineScope "192.168.4.1"
        }
        
        val (phoneIP, networkMask) = networkInfo
        Log.i(TAG, "Scanning network for Macchina A0 devices...")
        
        // Try common Macchina A0 IPs first
        val commonIPs = listOf(
            "192.168.4.1",  // Default Macchina A0 AP mode
            "192.168.1.100", // Common static IP
            "192.168.0.100", // Alternative static IP
            "10.0.0.1"       // Some configurations
        )
        
        // Test common IPs first
        for (ip in commonIPs) {
            if (testConnectivity(ip)) {
                Log.i(TAG, "✅ Found responsive device at $ip")
                return@coroutineScope ip
            }
        }
        
        // If no common IPs work, try scanning the local network
        try {
            val networkBase = phoneIP.substring(0, phoneIP.lastIndexOf('.'))
            Log.i(TAG, "Scanning network range: $networkBase.1-254")
            
            // Scan common ranges
            val ranges = listOf(
                (1..10),      // Common gateway range
                (100..110),   // Common device range
                (200..210)    // Alternative device range
            )
            
            for (range in ranges) {
                for (i in range) {
                    val testIP = "$networkBase.$i"
                    if (testIP != phoneIP && testConnectivity(testIP)) {
                        Log.i(TAG, "✅ Found responsive device at $testIP")
                        return@coroutineScope testIP
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning network: ${e.message}")
        }
        
        null
    }
}
