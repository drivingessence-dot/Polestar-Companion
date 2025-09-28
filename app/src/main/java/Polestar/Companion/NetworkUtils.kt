package Polestar.Companion

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Utility class for common network operations
 * Consolidates duplicate network functions from across the codebase
 */
object NetworkUtils {
    
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
            val ports = listOf(23, 35000, 23) // Telnet, GVRET, alternative
            
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
}
