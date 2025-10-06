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
            
            // Use JSON mode since the device is sending JSON data, not binary GVRET protocol
            val success = gvretWiFiManager.connect(ip, port, useJsonMode = true)
            if (success) {
                isConnected = true
                Log.i(TAG, "Successfully connected to Macchina A0 via WiFi GVRET in JSON mode")
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
     * Check if periodic PID polling is active
     */
    fun isPeriodicPollingActive(): Boolean {
        return isConnected && (gvretWiFiManager.isPeriodicPollingActive())
    }
    
    /**
     * Request specific PID from vehicle
     */
    suspend fun requestPID(mode: Int, pid: Int): Boolean {
        return if (isConnected) {
            gvretWiFiManager.requestPID(mode, pid)
        } else {
            Log.w(TAG, "Cannot request PID - not connected")
            false
        }
    }
    
    /**
     * Request VIN from vehicle
     */
    suspend fun requestVIN(): Boolean {
        return if (isConnected) {
            gvretWiFiManager.requestVIN()
        } else {
            Log.w(TAG, "Cannot request VIN - not connected")
            false
        }
    }
    
    /**
     * Request SOH (State of Health) from Polestar 2 BECM
     */
    suspend fun requestSOH(): Boolean {
        return if (isConnected) {
            gvretWiFiManager.requestSOH()
        } else {
            Log.w(TAG, "Cannot request SOH - not connected")
            false
        }
    }
    
    /**
     * Start periodic PID polling
     */
    suspend fun startPeriodicPIDPolling() {
        if (isConnected) {
            gvretWiFiManager.startPeriodicPIDPolling()
        } else {
            Log.w(TAG, "Cannot start PID polling - not connected")
        }
    }
    
    /**
     * Stop periodic PID polling
     */
    suspend fun stopPeriodicPIDPolling() {
        gvretWiFiManager.stopPeriodicPIDPolling()
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
