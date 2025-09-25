package Polestar.Companion

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Connection manager for Macchina A0 OBD reader
 * Handles WiFi GVRET connections only
 */
class MachinnaA0ConnectionManager(private val context: Context) {
    
    companion object {
        private const val TAG = "MachinnaA0ConnectionManager"
    }
    
    // WiFi GVRET connection
    private val gvretWiFiManager = GVRETWiFiManager(context)
    private var isConnected = false
    
    /**
     * Check if WiFi is available (always true for this implementation)
     */
    fun isWiFiAvailable(): Boolean {
        return true // WiFi is assumed to be available
    }
    
    /**
     * Connect to Macchina A0 via WiFi GVRET
     */
    suspend fun connectWiFi(ip: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Connecting to Macchina A0 via WiFi GVRET: $ip:$port")
            
            val success = gvretWiFiManager.connect(ip, port)
            if (success) {
                isConnected = true
                Log.i(TAG, "Successfully connected to Macchina A0 via WiFi GVRET")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to Macchina A0 WiFi: $ip:$port", e)
            false
        }
    }
    
    /**
     * Start reading CAN data from Macchina A0
     */
    suspend fun startReading() = withContext(Dispatchers.IO) {
        if (isConnected) {
            gvretWiFiManager.startReading()
        }
    }
    
    /**
     * Set callback for CAN message reception
     */
    fun setCANMessageCallback(callback: (CANMessage) -> Unit) {
        gvretWiFiManager.setCANMessageCallback(callback)
    }
    
    /**
     * Check if connected to Macchina A0
     */
    fun isConnected(): Boolean {
        return isConnected && gvretWiFiManager.isConnected()
    }
    
    /**
     * Check if reading CAN messages
     */
    fun isReading(): Boolean {
        return isConnected && gvretWiFiManager.isReading()
    }
    
    
    /**
     * Disconnect from Macchina A0
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Disconnecting from Macchina A0")
            
            gvretWiFiManager.disconnect()
            isConnected = false
            
            Log.i(TAG, "Disconnected from Macchina A0 successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }
}
