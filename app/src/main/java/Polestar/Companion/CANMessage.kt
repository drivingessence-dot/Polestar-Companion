package Polestar.Companion

import java.util.*

/**
 * Represents a raw CAN bus message
 */
data class CANMessage(
    val id: Long,                    // CAN ID (11-bit or 29-bit)
    val data: ByteArray,             // Up to 8 bytes of data
    val length: Int,                 // Data length (0-8)
    val timestamp: Long = System.currentTimeMillis(), // Message timestamp
    val isExtended: Boolean = false,  // 29-bit ID flag
    val isRTR: Boolean = false       // Remote Transmission Request flag
) {
    // Cache hex representations to avoid repeated string operations
    private val _dataAsHex by lazy { data.take(length).joinToString(" ") { "%02X".format(it) } }
    private val _idAsHex by lazy { if (isExtended) "%08X".format(id) else "%03X".format(id) }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CANMessage

        if (id != other.id) return false
        if (!data.contentEquals(other.data)) return false
        if (length != other.length) return false
        if (timestamp != other.timestamp) return false
        if (isExtended != other.isExtended) return false
        if (isRTR != other.isRTR) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + length
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + isExtended.hashCode()
        result = 31 * result + isRTR.hashCode()
        return result
    }

    /**
     * Get data as hex string (cached for performance)
     */
    fun getDataAsHex(): String = _dataAsHex

    /**
     * Get CAN ID as hex string (cached for performance)
     */
    fun getIdAsHex(): String = _idAsHex

    /**
     * Get formatted timestamp
     */
    fun getFormattedTimestamp(): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        return String.format(
            "%02d:%02d:%02d.%03d",
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
            calendar.get(Calendar.MILLISECOND)
        )
    }

    companion object {
        /**
         * Create CANMessage from native data
         */
        fun fromNative(id: Long, data: ByteArray, timestamp: Long, isExtended: Boolean, isRTR: Boolean): CANMessage {
            return CANMessage(
                id = id,
                data = data.copyOf(8), // Ensure 8 bytes
                length = data.size,
                timestamp = timestamp,
                isExtended = isExtended,
                isRTR = isRTR
            )
        }
    }
}
