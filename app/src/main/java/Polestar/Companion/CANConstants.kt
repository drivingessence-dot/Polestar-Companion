package Polestar.Companion

/**
 * CAN Bus Constants and PID Definitions
 * Based on working Arduino implementation for Polestar 2
 */
object CANConstants {
    
    // PID Definitions
    const val PID_VEHICLE_SPEED = 0x0D
    const val PID_CONTROL_MODULE_VOLTAGE = 0x42
    const val PID_AMBIENT_AIR_TEMPERATURE = 0x46
    const val PID_BATTERY_PACK_SOC = 0x5B
    const val PID_VIN = 0x02
    
    // CAN Mode Definitions
    const val CAN_MODE_CURRENT = 0x01
    const val CAN_MODE_INFORMATION = 0x09
    const val CAN_MODE_CUSTOM = 0x22
    
    // 11-bit CAN IDs (Standard OBD-II)
    const val SHORT_SEND_ID = 0x7DFL
    const val SHORT_RECV_ID = 0x7E8L
    const val SHORT_RECV_MASK = 0x7F8L
    
    // 29-bit CAN IDs (Extended addressing)
    const val LONG_SEND_ID = 0x18DB33F1L
    const val LONG_RECV_ID = 0x18DAF100L
    const val LONG_RECV_MASK = 0x1FFFFF00L
    
    // Polestar-specific broadcast IDs
    const val LONGBC_RECV_ID = 0x1FFF0000L
    const val LONGBC_RECV_MASK = 0x1FFFF000L
    const val ODOMETER_ID = 0x1FFF0120L
    const val GEAR_ID = 0x1FFF00A0L
    
    // Response Mode Codes
    const val MODE_CURRENT_RESPONSE = 0x41
    const val MODE_INFORMATION_RESPONSE = 0x49
    
    // Multi-frame Response Codes
    const val SINGLE_FRAME = 0x00
    const val FIRST_FRAME = 0x10
    const val CONSECUTIVE_FRAME = 0x20
    
    // Gear Translation (P, R, N, D)
    val GEAR_TRANSLATE = charArrayOf('P', 'R', 'N', 'D')
    
    // PID Request List (matches working example)
    val PID_REQUESTS = listOf(
        Pair(CAN_MODE_INFORMATION, PID_VIN),
        Pair(CAN_MODE_CURRENT, PID_CONTROL_MODULE_VOLTAGE),
        Pair(CAN_MODE_CURRENT, PID_AMBIENT_AIR_TEMPERATURE),
        Pair(CAN_MODE_CURRENT, PID_BATTERY_PACK_SOC),
        Pair(CAN_MODE_CURRENT, PID_VEHICLE_SPEED)
    )
    
    /**
     * Build CAN request frame data for PID request
     */
    fun buildPIDRequest(mode: Int, pid: Int): ByteArray {
        val data = ByteArray(8)
        
        // Set unused bytes to 0
        for (i in 3 until 8) {
            data[i] = 0x00
        }
        
        when (mode) {
            CAN_MODE_CURRENT, CAN_MODE_INFORMATION -> {
                data[0] = 2  // 2 more bytes to follow
                data[1] = mode.toByte()
                data[2] = pid.toByte()
            }
            CAN_MODE_CUSTOM -> {
                data[0] = 3  // 3 more bytes to follow
                data[1] = mode.toByte()
                data[2] = ((pid shr 8) and 0xFF).toByte()
                data[3] = (pid and 0xFF).toByte()
            }
        }
        
        return data
    }
    
    /**
     * Build flow control request for multi-frame responses
     */
    fun buildFlowRequest(senderId: Long): ByteArray {
        val data = ByteArray(8)
        
        // Set unused bytes to 0
        for (i in 1 until 8) {
            data[i] = 0x00
        }
        
        // Flow control frame request
        data[0] = 0x30
        
        return data
    }
    
    /**
     * Convert sender ID to flow request ID
     */
    fun getFlowRequestId(senderId: Long): Long {
        return if (senderId > 0x7FF) {
            // 29-bit: swap the 2 least significant bytes
            (senderId and 0xFFFF0000L) or ((senderId and 0xFFL) shl 8) or ((senderId and 0xFF00L) shr 8)
        } else {
            // 11-bit: decrease by 8
            senderId - 8
        }
    }
    
    /**
     * Parse single frame response
     */
    fun parseSingleFrame(data: ByteArray): Triple<Int, Int, Int> {
        val length = data[0].toInt() and 0xFF
        val mode = data[1].toInt() and 0xFF
        val pid = data[2].toInt() and 0xFF
        return Triple(length, mode, pid)
    }
    
    /**
     * Parse first frame of multi-frame response
     */
    fun parseFirstFrame(data: ByteArray): Triple<Int, Int, Int> {
        val length = ((data[1].toInt() and 0xFF) or ((data[0].toInt() and 0x0F) shl 8)) - 6
        val mode = data[2].toInt() and 0xFF
        val pid = data[3].toInt() and 0xFF
        return Triple(length, mode, pid)
    }
    
    /**
     * Parse consecutive frame
     */
    fun parseConsecutiveFrame(data: ByteArray): Int {
        return data[0].toInt() and 0x0F
    }
    
    /**
     * Get frame type from first byte
     */
    fun getFrameType(data: ByteArray): Int {
        return (data[0].toInt() and 0xF0) shr 4
    }
}
