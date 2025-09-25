package Polestar.Companion

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * GVRET WiFi Connection Manager for Macchina A0
 * Handles WiFi connection to Macchina A0 at 192.168.4.1 using GVRET protocol
 * Compatible with SavvyCAN's GVRET WiFi implementation
 */
class GVRETWiFiManager(private val context: Context) {
    
    companion object {
        private const val TAG = "GVRETWiFiManager"
        
        // GVRET Protocol Commands
        private const val CMD_GET_CANBUS_PARAMS = 0x1A.toByte()
        private const val CMD_SET_CANBUS_PARAMS = 0x1B.toByte()
        private const val CMD_KEEPALIVE = 0x1C.toByte()
        private const val CMD_SET_SINGLEWIRE_MODE = 0x1D.toByte()
        private const val CMD_SET_SYSTEM_TYPE = 0x1E.toByte()
        private const val CMD_SET_EXTENDED_DIAG = 0x1F.toByte()
        private const val CMD_SETUP_PERIODIC_MSG = 0x20.toByte()
        private const val CMD_QUEUE_PERIODIC_MSG = 0x21.toByte()
        private const val CMD_DELETE_PERIODIC_MSG = 0x22.toByte()
        private const val CMD_CLEAR_PERIODIC_MSGS = 0x23.toByte()
        private const val CMD_GET_PERIODIC_MSGS = 0x24.toByte()
        private const val CMD_GET_DEVICE_INFO = 0x25.toByte()
        private const val CMD_SET_SW_MODE = 0x26.toByte()
        private const val CMD_ENABLE_CANBUS = 0x27.toByte()
        private const val CMD_DISABLE_CANBUS = 0x28.toByte()
        private const val CMD_TIME_SYNC = 0x29.toByte()
        private const val CMD_DIG_INPUTS = 0x2A.toByte()
        private const val CMD_SET_DIG_OUTPUTS = 0x2B.toByte()
        private const val CMD_SETUP_EXT_BUSES = 0x2C.toByte()
        private const val CMD_GET_EXT_BUSES = 0x2D.toByte()
        private const val CMD_RESET_DEVICE = 0x2E.toByte()
        private const val CMD_GET_NUM_BUSES = 0x2F.toByte()
        private const val CMD_GET_EXTENDED_STATUS = 0x30.toByte()
        
        // GVRET Response Types
        private const val RESP_CAN_FRAME = 0x00.toByte()
        private const val RESP_CAN_FRAME_FD = 0x01.toByte()
        private const val RESP_DIG_INPUTS = 0x02.toByte()
        private const val RESP_ANALOG_INPUTS = 0x03.toByte()
        private const val RESP_CANBUS_PARAMS = 0x04.toByte()
        private const val RESP_GET_DIG_INPUTS = 0x05.toByte()
        private const val RESP_GET_ANALOG_INPUTS = 0x06.toByte()
        private const val RESP_DEVICE_INFO = 0x07.toByte()
        private const val RESP_EXT_BUSES = 0x08.toByte()
        private const val RESP_NUM_BUSES = 0x09.toByte()
        private const val RESP_EXTENDED_STATUS = 0x0A.toByte()
        private const val RESP_TIME_SYNC = 0x0B.toByte()
        private const val RESP_PERIODIC_MSG = 0x0C.toByte()
        private const val RESP_GET_PERIODIC_MSGS = 0x0D.toByte()
        private const val RESP_GET_CANBUS_PARAMS = 0x0E.toByte()
        
        // CAN Bus Parameters
        private const val CAN_SPEED_500K = 0x01.toByte()
        private const val CAN_SPEED_250K = 0x02.toByte()
        private const val CAN_SPEED_125K = 0x03.toByte()
        private const val CAN_SPEED_100K = 0x04.toByte()
        private const val CAN_SPEED_83K = 0x05.toByte()
        private const val CAN_SPEED_50K = 0x06.toByte()
        private const val CAN_SPEED_33K = 0x07.toByte()
        private const val CAN_SPEED_20K = 0x08.toByte()
        private const val CAN_SPEED_10K = 0x09.toByte()
        private const val CAN_SPEED_5K = 0x0A.toByte()
    }
    
    // Connection state
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var isConnected = false
    private var isReading = false
    
    // Message queue for received CAN frames
    private val receivedMessages = ConcurrentLinkedQueue<CANMessage>()
    
    // Callback for CAN message reception
    private var canMessageCallback: ((CANMessage) -> Unit)? = null
    
    // Buffer for GVRET data parsing (like C++ implementation)
    private val buffer = mutableListOf<Byte>()
    
    /**
     * Connect to Macchina A0 via WiFi using GVRET protocol
     */
    suspend fun connect(ip: String = "192.168.4.1", port: Int = 23): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Connecting to Macchina A0 via WiFi: $ip:$port")
            
            // Create socket connection
            socket = Socket(ip, port)
            socket?.let { sock ->
                sock.soTimeout = 5000 // 5 second timeout
                
                // Get input and output streams
                inputStream = sock.getInputStream()
                outputStream = sock.getOutputStream()
                
                Log.i(TAG, "WiFi socket connected to Macchina A0")
                
                // GVRET is ready to receive CAN frames immediately (like C++ implementation)
                isConnected = true
                Log.i(TAG, "Successfully connected to Macchina A0 via WiFi")
                true
            } ?: run {
                Log.e(TAG, "Failed to create socket connection")
                false
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to connect to Macchina A0 WiFi: $ip:$port", e)
            disconnect()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error connecting to Macchina A0 WiFi", e)
            disconnect()
            false
        }
    }
    
    /**
     * Initialize GVRET protocol with Macchina A0
     */
    private suspend fun initializeGVRET() = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Initializing GVRET protocol with Macchina A0")
            
            // Send keepalive command
            sendCommand(CMD_KEEPALIVE, byteArrayOf())
            delay(100)
            
            // Get device info
            sendCommand(CMD_GET_DEVICE_INFO, byteArrayOf())
            delay(100)
            
            // Set CAN bus parameters (500kbps for Polestar 2)
            val canParams = byteArrayOf(
                CAN_SPEED_500K,  // Speed
                0x00.toByte(),   // Mode (normal)
                0x00.toByte(),   // Reserved
                0x00.toByte()    // Reserved
            )
            sendCommand(CMD_SET_CANBUS_PARAMS, canParams)
            delay(100)
            
            // Enable CAN bus
            sendCommand(CMD_ENABLE_CANBUS, byteArrayOf())
            delay(100)
            
            Log.i(TAG, "GVRET protocol initialized successfully with Macchina A0")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GVRET protocol", e)
        }
    }
    
    /**
     * Send GVRET command to Macchina A0
     */
    private suspend fun sendCommand(command: Byte, data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            if (outputStream != null && isConnected) {
                val packet = buildGVRETPacket(command, data)
                outputStream!!.write(packet)
                outputStream!!.flush()
                Log.d(TAG, "Sent GVRET command to Macchina A0: 0x${command.toString(16).uppercase()}")
            } else {
                Log.e(TAG, "Cannot send GVRET command - not connected")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send GVRET command", e)
        }
    }
    
    /**
     * Build GVRET packet
     */
    private fun buildGVRETPacket(command: Byte, data: ByteArray): ByteArray {
        val packet = ByteArray(2 + data.size)
        packet[0] = command
        packet[1] = data.size.toByte()
        System.arraycopy(data, 0, packet, 2, data.size)
        return packet
    }
    
    /**
     * Start reading CAN messages from Macchina A0
     */
    suspend fun startReading() = withContext(Dispatchers.IO) {
        if (!isConnected) {
            Log.e(TAG, "Cannot start reading - not connected")
            return@withContext
        }
        
        isReading = true
        Log.i(TAG, "Started reading CAN messages from Macchina A0")
        
        // Start reading loop
        while (isReading && isConnected) {
            try {
                val data = readData()
                if (data != null && data.isNotEmpty()) {
                    parseGVRETData(data)
                }
                delay(10) // Small delay to prevent excessive CPU usage
            } catch (e: Exception) {
                Log.e(TAG, "Error reading GVRET data from Macchina A0", e)
                if (isConnected) {
                    delay(1000) // Wait before retrying
                }
            }
        }
        
        Log.i(TAG, "Stopped reading CAN messages from Macchina A0")
    }
    
    /**
     * Read data from WiFi socket
     */
    private suspend fun readData(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (inputStream != null && isConnected) {
                val buffer = ByteArray(1024)
                val bytesRead = inputStream!!.read(buffer)
                if (bytesRead > 0) {
                    return@withContext buffer.copyOf(bytesRead)
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read data from Macchina A0", e)
            null
        }
    }
    
    /**
     * Parse GVRET data and extract CAN messages
     */
    private fun parseGVRETData(data: ByteArray) {
        try {
            // Add data to buffer (with debug logging like C++ implementation)
            buffer.addAll(data.toList())
            Log.d(TAG, "Feed ${data.size} bytes: ${data.take(minOf(64, data.size)).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}")
            
            // Process buffer with multiple parsing attempts
            var progress = true
            while (progress && buffer.isNotEmpty()) {
                progress = false
                
                // Find command byte position
                val cmdPos = findCommandPosition()
                if (cmdPos == -1) {
                    // No command found, keep buffer reasonable size (matching C++ implementation)
                    if (buffer.size > 8192) {
                        buffer.clear()
                        buffer.addAll(buffer.takeLast(2048)) // Keep last 2048 bytes for resync
                    }
                    break
                }
                
                if (cmdPos > 0) {
                    // Drop garbage bytes before command (with debug logging)
                    Log.d(TAG, "Dropping ${cmdPos} bytes before sync: ${buffer.take(cmdPos).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}")
                    buffer.removeAll(buffer.take(cmdPos))
                    progress = true
                    continue
                }
                
                // Try to parse frame starting at position 0
                val parsedFrame = tryParseFrame()
                if (parsedFrame != null) {
                    receivedMessages.offer(parsedFrame)
                    canMessageCallback?.invoke(parsedFrame)
                    Log.d(TAG, "Received CAN message from Macchina A0: ID=0x${parsedFrame.id.toString(16).uppercase()}, Data=${parsedFrame.getDataAsHex()}, Length=${parsedFrame.length}")
                    progress = true
                } else {
                    // Drop first byte and try again (with debug logging)
                    Log.d(TAG, "Resync drop: ${buffer.take(minOf(16, buffer.size)).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}")
                    if (buffer.isNotEmpty()) {
                        buffer.removeAt(0)
                        progress = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing GVRET data", e)
        }
    }
    
    /**
     * Find position of GVRET command byte in buffer (matching C++ implementation)
     */
    private fun findCommandPosition(): Int {
        // Common GVRET command bytes from ESP32RET reference
        val candidates = listOf(0xF1, 0xF2, 0xF3, 0xF4, 0xE7, 0xE8, 0xE9)
        
        for (i in buffer.indices) {
            val byte = buffer[i].toInt() and 0xFF
            if (candidates.contains(byte)) {
                return i
            }
        }
        
        return -1
    }
    
    /**
     * Try to parse a CAN frame from buffer start (matching C++ implementation)
     */
    private fun tryParseFrame(): CANMessage? {
        if (buffer.isEmpty()) return null
        
        val cmd = buffer[0].toInt() and 0xFF
        
        // Heuristic 1: Common ESP32RET style: cmd(1) + chan(1) + id(4 LE) + dlc(1) + data + opt ts(4)
        if (cmd == 0xF1 || cmd == 0xF2 || cmd == 0xF3 || cmd == 0xF4) {
            if (buffer.size < 7) return null // Need cmd+chan+id4+dlc
            
            val channel = buffer[1].toInt() and 0xFF
            val id = ((buffer[2].toInt() and 0xFF) or
                     ((buffer[3].toInt() and 0xFF) shl 8) or
                     ((buffer[4].toInt() and 0xFF) shl 16) or
                     ((buffer[5].toInt() and 0xFF) shl 24))
            val dlc = buffer[6].toInt() and 0xFF
            
            // Support CAN-FD (DLC up to 64)
            if (dlc > 64) return null // Invalid DLC
            
            val headerLen = 7 // cmd + channel + id4 + dlc
            val frameTotal = headerLen + dlc
            
            if (buffer.size >= frameTotal) {
                // Parse frame data
                val frameData = buffer.slice(headerLen until headerLen + dlc).toByteArray()
                
                // Check for optional 4-byte timestamp after data (LE)
                val timestampPresent = buffer.size >= frameTotal + 4
                val bytesToConsume = if (timestampPresent) frameTotal + 4 else frameTotal
                
                // Remove consumed bytes
                repeat(bytesToConsume) {
                    if (buffer.isNotEmpty()) buffer.removeAt(0)
                }
                
                return CANMessage(id.toLong(), frameData, dlc)
            }
        }
        
        // Heuristic 2: Older style: [cmd][id3 BE][dlc][data...]
        if (buffer.size >= 5) { // cmd + id3 + dlc
            val dlc = buffer[4].toInt() and 0xFF
            if (dlc <= 64 && buffer.size >= 5 + dlc) {
                // Big-endian 3-byte ID
                val id = ((buffer[1].toInt() and 0xFF) shl 16) or
                        ((buffer[2].toInt() and 0xFF) shl 8) or
                        (buffer[3].toInt() and 0xFF)
                
                val frameData = buffer.slice(5 until 5 + dlc).toByteArray()
                
                // Remove consumed bytes
                repeat(5 + dlc) {
                    if (buffer.isNotEmpty()) buffer.removeAt(0)
                }
                
                return CANMessage(id.toLong(), frameData, dlc)
            }
        }
        
        // Heuristic 3: cmd + id4 BE + chan + dlc
        if (buffer.size >= 7) { // cmd + id4 + chan + dlc
            val dlc = buffer[6].toInt() and 0xFF
            if (dlc <= 64 && buffer.size >= 7 + dlc) {
                // Big-endian 4-byte ID
                val id = ((buffer[1].toInt() and 0xFF) shl 24) or
                        ((buffer[2].toInt() and 0xFF) shl 16) or
                        ((buffer[3].toInt() and 0xFF) shl 8) or
                        (buffer[4].toInt() and 0xFF)
                val channel = buffer[5].toInt() and 0xFF
                
                val frameData = buffer.slice(7 until 7 + dlc).toByteArray()
                
                // Remove consumed bytes
                repeat(7 + dlc) {
                    if (buffer.isNotEmpty()) buffer.removeAt(0)
                }
                
                return CANMessage(id.toLong(), frameData, dlc)
            }
        }
        
        return null
    }
    
    /**
     * Parse standard CAN frame from GVRET data
     */
    private fun parseCANFrame(data: ByteArray, offset: Int, length: Int): CANMessage? {
        try {
            if (length < 13) return null
            
            // GVRET CAN frame format:
            // Bytes 0-3: CAN ID (little endian)
            // Byte 4: Extended ID flag (bit 0), RTR flag (bit 1)
            // Byte 5: Data length (0-8)
            // Bytes 6-13: Data bytes
            
            val id = (data[offset].toInt() and 0xFF) or
                    ((data[offset + 1].toInt() and 0xFF) shl 8) or
                    ((data[offset + 2].toInt() and 0xFF) shl 16) or
                    ((data[offset + 3].toInt() and 0xFF) shl 24)
            
            val flags = data[offset + 4].toInt() and 0xFF
            val isExtended = (flags and 0x01) != 0
            val isRTR = (flags and 0x02) != 0
            
            val dataLength = data[offset + 5].toInt() and 0xFF
            val canData = ByteArray(8)
            
            for (i in 0 until minOf(dataLength, 8)) {
                canData[i] = data[offset + 6 + i]
            }
            
            return CANMessage(
                id = id.toLong(),
                data = canData,
                length = dataLength,
                timestamp = System.currentTimeMillis(),
                isExtended = isExtended,
                isRTR = isRTR
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing CAN frame from Macchina A0", e)
            return null
        }
    }
    
    /**
     * Parse CAN FD frame from GVRET data
     */
    private fun parseCANFDFrame(data: ByteArray, offset: Int, length: Int): CANMessage? {
        try {
            if (length < 15) return null
            
            // CAN FD frame format is similar but with additional fields
            // For now, treat as standard CAN frame
            return parseCANFrame(data, offset, length - 2)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing CAN FD frame from Macchina A0", e)
            return null
        }
    }
    
    /**
     * Set callback for CAN message reception
     */
    fun setCANMessageCallback(callback: (CANMessage) -> Unit) {
        canMessageCallback = callback
    }
    
    /**
     * Get received messages
     */
    fun getReceivedMessages(): List<CANMessage> {
        val messages = mutableListOf<CANMessage>()
        while (receivedMessages.isNotEmpty()) {
            receivedMessages.poll()?.let { messages.add(it) }
        }
        return messages
    }
    
    /**
     * Stop reading CAN messages
     */
    suspend fun stopReading() = withContext(Dispatchers.IO) {
        isReading = false
        Log.i(TAG, "Stopped reading CAN messages from Macchina A0")
    }
    
    /**
     * Disconnect from Macchina A0
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Disconnecting from Macchina A0")
            
            isReading = false
            
            // Disable CAN bus
            if (isConnected && outputStream != null) {
                try {
                    sendCommand(CMD_DISABLE_CANBUS, byteArrayOf())
                } catch (e: Exception) {
                    Log.w(TAG, "Error sending disable command during disconnect", e)
                }
            }
            
            // Close streams and socket
            inputStream?.close()
            outputStream?.close()
            socket?.close()
            
            // Reset state
            inputStream = null
            outputStream = null
            socket = null
            isConnected = false
            
            Log.i(TAG, "Disconnected from Macchina A0 successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during Macchina A0 disconnect", e)
        }
    }
    
    /**
     * Check if connected
     */
    fun isConnected(): Boolean = isConnected
    
    /**
     * Check if reading
     */
    fun isReading(): Boolean = isReading
}
