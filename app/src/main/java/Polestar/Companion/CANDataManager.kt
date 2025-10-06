package Polestar.Companion

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages storage and retrieval of raw CAN bus data
 */
class CANDataManager(private val context: Context) {
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("can_data", Context.MODE_PRIVATE)
    
    private val canMessagesKey = "can_messages"
    private val sessionActiveKey = "session_active"
    private val sessionStartTimeKey = "session_start_time"
    private val maxMessages = 5000 // Reduced limit for better memory management
    private val maxUniqueIds = 500 // Limit unique CAN IDs to track
    
    private val messages = mutableListOf<CANMessage>()
    private val uniqueIdSet = mutableSetOf<Long>() // Track unique IDs efficiently
    private var isSessionActive = false
    private var sessionStartTime: Long = 0
    
    companion object {
        private const val TAG = "CANDataManager"
    }
    
    /**
     * Start a new CAN data capture session
     */
    suspend fun startSession() = withContext(Dispatchers.IO) {
        isSessionActive = true
        sessionStartTime = System.currentTimeMillis()
        messages.clear()
        
        sharedPreferences.edit()
            .putBoolean(sessionActiveKey, true)
            .putLong(sessionStartTimeKey, sessionStartTime)
            .apply()
        
        Log.d(TAG, "CAN capture session started")
    }
    
    /**
     * Stop the current CAN data capture session
     */
    suspend fun stopSession() = withContext(Dispatchers.IO) {
        isSessionActive = false
        
        sharedPreferences.edit()
            .putBoolean(sessionActiveKey, false)
            .apply()
        
        Log.d(TAG, "CAN capture session stopped. Captured ${messages.size} messages")
    }
    
    /**
     * Add a CAN message to the current session
     */
    suspend fun addMessage(message: CANMessage) = withContext(Dispatchers.IO) {
        // Add message
        messages.add(message)
        
        // Track unique IDs efficiently
        uniqueIdSet.add(message.id)
        
        // Limit message count to prevent memory issues
        if (messages.size > maxMessages) {
            val removedMessage = messages.removeAt(0) // Remove oldest message
            
            // Check if we need to remove from unique ID set
            val stillExists = messages.any { it.id == removedMessage.id }
            if (!stillExists) {
                uniqueIdSet.remove(removedMessage.id)
            }
        }
        
        // Limit unique ID tracking
        if (uniqueIdSet.size > maxUniqueIds) {
            // Remove oldest unique IDs (simple approach)
            val oldestIds = messages.take(maxMessages / 2).map { it.id }.distinct()
            uniqueIdSet.removeAll(oldestIds)
        }
        
        Log.d(TAG, "Added CAN message: ${message.getIdAsHex()}, Total: ${messages.size}, Unique IDs: ${uniqueIdSet.size}")
    }
    
    /**
     * Get all captured CAN messages
     */
    suspend fun getAllMessages(): List<CANMessage> = withContext(Dispatchers.IO) {
        messages.toList()
    }
    
    /**
     * Clear all captured messages
     */
    suspend fun clearMessages() = withContext(Dispatchers.IO) {
        messages.clear()
        Log.d(TAG, "All CAN messages cleared")
    }
    
    /**
     * Get session statistics
     */
    suspend fun getSessionStats(): SessionStats = withContext(Dispatchers.IO) {
        val uniqueIds = uniqueIdSet.size // Use cached count instead of expensive operation
        val totalMessages = messages.size
        val sessionDuration = if (isSessionActive) {
            System.currentTimeMillis() - sessionStartTime
        } else {
            0L
        }
        
        SessionStats(
            totalMessages = totalMessages,
            uniqueIds = uniqueIds,
            sessionDuration = sessionDuration,
            isActive = isSessionActive,
            sessionStartTime = sessionStartTime
        )
    }
    
    /**
     * Export CAN data to CSV file
     */
    suspend fun exportToCSV(): String = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "polestar2_can_data_$timestamp.csv"
        val file = File(context.getExternalFilesDir(null), fileName)
        
        try {
            FileWriter(file).use { writer ->
                // Write CSV header with enhanced information
                writer.write("Timestamp,ID,Data Length,Data (Hex),Data (Decimal),Extended,RTR,Description\n")
                
                // Write message data with descriptions
                messages.forEach { message ->
                    val description = getMessageDescription(message.id)
                    writer.write(
                        "${message.getFormattedTimestamp()}," +
                        "${message.getIdAsHex()}," +
                        "${message.length}," +
                        "\"${message.getDataAsHex()}\"," +
                        "\"${message.data.take(message.length).joinToString(",")}\"," +
                        "${message.isExtended}," +
                        "${message.isRTR}," +
                        "\"$description\"\n"
                    )
                }
            }
            
            Log.d(TAG, "✅ CAN data exported successfully to: ${file.absolutePath}")
            Log.d(TAG, "Exported ${messages.size} messages, ${uniqueIdSet.size} unique IDs")
            file.absolutePath
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error exporting CAN data to CSV", e)
            throw e
        }
    }
    
    /**
     * Get description for known CAN IDs
     */
    private fun getMessageDescription(canId: Long): String {
        return when (canId) {
            0x1D0L -> "Vehicle Speed"
            0x348L -> "Battery SOC"
            0x3D3L -> "HV Battery Voltage"
            0x2A0L -> "Wheel Speeds"
            0x3D2L -> "HV Battery Current"
            0x4A8L -> "Charging Power"
            0x3E8L -> "Ambient Temperature"
            0x7E8L, 0x7E9L, 0x7EAL, 0x7EBL -> "OBD-II Response"
            0x1EC6AE80L -> "SOH Response (BECM)"
            0x1DD01635L -> "SOH Request (BECM)"
            0x1FFF0120L -> "Odometer"
            0x1FFF00A0L -> "Gear Position"
            else -> "Unknown"
        }
    }
    
    /**
     * Check if session is active
     */
    fun isSessionActive(): Boolean = isSessionActive
    
    /**
     * Get session start time
     */
    fun getSessionStartTime(): Long = sessionStartTime
    
    /**
     * Load session state from preferences
     */
    suspend fun loadSessionState() = withContext(Dispatchers.IO) {
        isSessionActive = sharedPreferences.getBoolean(sessionActiveKey, false)
        sessionStartTime = sharedPreferences.getLong(sessionStartTimeKey, 0L)
        
        if (isSessionActive && sessionStartTime == 0L) {
            // If session was marked as active but no start time, reset
            isSessionActive = false
            sharedPreferences.edit().putBoolean(sessionActiveKey, false).apply()
        }
    }
}

/**
 * Session statistics data class
 */
data class SessionStats(
    val totalMessages: Int,
    val uniqueIds: Int,
    val sessionDuration: Long,
    val isActive: Boolean,
    val sessionStartTime: Long
) {
    /**
     * Get formatted session duration
     */
    fun getFormattedDuration(): String {
        val seconds = sessionDuration / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        
        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes % 60, seconds % 60)
            minutes > 0 -> String.format("%d:%02d", minutes, seconds % 60)
            else -> String.format("%d s", seconds)
        }
    }
    
    /**
     * Get formatted session start time
     */
    fun getFormattedStartTime(): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = sessionStartTime
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return formatter.format(calendar.time)
    }
}
