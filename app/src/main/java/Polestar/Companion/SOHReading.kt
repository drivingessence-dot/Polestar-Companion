package Polestar.Companion

import java.util.Date

/**
 * Data class representing a single SOH (State of Health) reading
 */
data class SOHReading(
    val timestamp: Long = System.currentTimeMillis(),
    val sohValue: Float,
    val date: Date = Date(timestamp)
) {
    companion object {
        fun fromJson(json: String): SOHReading? {
            return try {
                val parts = json.split(",")
                if (parts.size >= 2) {
                    SOHReading(
                        timestamp = parts[0].toLong(),
                        sohValue = parts[1].toFloat()
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        }
        
        fun toJson(reading: SOHReading): String {
            return "${reading.timestamp},${reading.sohValue}"
        }
    }
}

