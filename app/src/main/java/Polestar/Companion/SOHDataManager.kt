package Polestar.Companion

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

/**
 * Manages storage and retrieval of SOH readings
 */
class SOHDataManager(private val context: Context) {
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("soh_data", Context.MODE_PRIVATE)
    
    private val sohReadingsKey = "soh_readings"
    private val baselineAddedKey = "baseline_added"
    private val maxReadings = 1000 // Limit to prevent memory issues
    
    /**
     * Add a new SOH reading
     */
    suspend fun addSOHReading(sohValue: Float) = withContext(Dispatchers.IO) {
        val reading = SOHReading(sohValue = sohValue)
        val readings = getAllSOHReadings().toMutableList()
        
        // Add new reading
        readings.add(reading)
        
        // Sort by timestamp (oldest first)
        readings.sortBy { it.timestamp }
        
        // Remove theoretical degradation points that come after the new reading
        val cleanedReadings = removeTheoreticalPointsAfterReading(readings, reading)
        
        // Keep only the most recent readings if we exceed the limit
        val finalReadings = if (cleanedReadings.size > maxReadings) {
            val toRemove = cleanedReadings.size - maxReadings
            cleanedReadings.drop(toRemove) // Remove oldest readings
        } else {
            cleanedReadings
        }
        
        // Save to preferences
        val jsonStrings = finalReadings.map { SOHReading.toJson(it) }
        sharedPreferences.edit()
            .putStringSet(sohReadingsKey, jsonStrings.toSet())
            .apply()
    }
    
    /**
     * Remove theoretical degradation points that come after a manual reading
     */
    private fun removeTheoreticalPointsAfterReading(readings: List<SOHReading>, newReading: SOHReading): List<SOHReading> {
        val carYear = getCarYearFromVIN() ?: 2020
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        
        return readings.filter { reading ->
            // Keep the reading if it's before the new reading
            if (reading.timestamp < newReading.timestamp) {
                true
            } else if (reading.timestamp == newReading.timestamp) {
                // Keep the new reading
                true
            } else {
                // For readings after the new reading, only keep them if they're not theoretical
                // Theoretical readings are those that follow the 2% per year pattern
                !isTheoreticalReading(reading, carYear, currentYear)
            }
        }
    }
    
    /**
     * Check if a reading is theoretical (follows 2% per year degradation pattern)
     */
    private fun isTheoreticalReading(reading: SOHReading, carYear: Int, currentYear: Int): Boolean {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = reading.timestamp
        
        // Check if this reading falls on January 1st of a year
        val isNewYear = calendar.get(Calendar.MONTH) == Calendar.JANUARY && 
                       calendar.get(Calendar.DAY_OF_MONTH) == 1 &&
                       calendar.get(Calendar.HOUR_OF_DAY) == 0 &&
                       calendar.get(Calendar.MINUTE) == 0 &&
                       calendar.get(Calendar.SECOND) == 0
        
        if (!isNewYear) return false
        
        val readingYear = calendar.get(Calendar.YEAR)
        
        // Check if this reading follows the theoretical degradation pattern
        val expectedSOH = 100.0f - ((readingYear - carYear) * 2.0f)
        val tolerance = 0.1f // Small tolerance for floating point comparison
        
        return kotlin.math.abs(reading.sohValue - expectedSOH) <= tolerance
    }
    
    /**
     * Get all SOH readings (including baseline if needed)
     */
    suspend fun getAllSOHReadings(): List<SOHReading> = withContext(Dispatchers.IO) {
        // Ensure baseline is added
        ensureBaselineAdded()
        
        // Extend degradation curve if needed (e.g., new year)
        extendDegradationCurveIfNeeded()
        
        val jsonStrings = sharedPreferences.getStringSet(sohReadingsKey, emptySet()) ?: emptySet()
        jsonStrings.mapNotNull { SOHReading.fromJson(it) }
            .sortedBy { it.timestamp }
    }
    
    /**
     * Extend degradation curve if we're in a new year and haven't added manual readings
     */
    private suspend fun extendDegradationCurveIfNeeded() = withContext(Dispatchers.IO) {
        val carYear = getCarYearFromVIN() ?: 2020
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        
        val jsonStrings = sharedPreferences.getStringSet(sohReadingsKey, emptySet()) ?: emptySet()
        val readings = jsonStrings.mapNotNull { SOHReading.fromJson(it) }
            .sortedBy { it.timestamp }
        
        // Find the latest theoretical reading
        val latestTheoreticalReading = readings.filter { reading ->
            isTheoreticalReading(reading, carYear, currentYear)
        }.maxByOrNull { it.timestamp }
        
        if (latestTheoreticalReading != null) {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = latestTheoreticalReading.timestamp
            val latestYear = calendar.get(Calendar.YEAR)
            
            // If we're in a new year, add the next theoretical point
            if (currentYear > latestYear) {
                val newYear = latestYear + 1
                val newSOH = latestTheoreticalReading.sohValue - 2.0f
                if (newSOH >= 0f) {
                    calendar.set(newYear, 0, 1, 0, 0, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    
                    val newReading = SOHReading(
                        timestamp = calendar.timeInMillis,
                        sohValue = newSOH
                    )
                    
                    val newJsonStrings = jsonStrings.toMutableSet()
                    newJsonStrings.add(SOHReading.toJson(newReading))
                    
                    sharedPreferences.edit()
                        .putStringSet(sohReadingsKey, newJsonStrings)
                        .apply()
                }
            }
        }
    }
    
    /**
     * Ensure the baseline 100% SOH point and degradation curve are added
     */
    private suspend fun ensureBaselineAdded() = withContext(Dispatchers.IO) {
        val baselineAdded = sharedPreferences.getBoolean(baselineAddedKey, false)
        if (!baselineAdded) {
            // Add baseline point representing car when new (100% SOH)
            val carYear = getCarYearFromVIN() ?: 2020 // Default to 2020 if VIN not available
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            
            // Generate degradation curve from car year to current year
            val degradationReadings = generateDegradationCurve(carYear, currentYear)
            
            val jsonStrings = sharedPreferences.getStringSet(sohReadingsKey, emptySet()) ?: emptySet()
            val newJsonStrings = jsonStrings.toMutableSet()
            
            // Add all degradation curve points
            degradationReadings.forEach { reading ->
                newJsonStrings.add(SOHReading.toJson(reading))
            }
            
            sharedPreferences.edit()
                .putStringSet(sohReadingsKey, newJsonStrings)
                .putBoolean(baselineAddedKey, true)
                .apply()
        }
    }
    
    /**
     * Generate degradation curve with 2% per year from car year to current year
     */
    private fun generateDegradationCurve(carYear: Int, currentYear: Int): List<SOHReading> {
        val readings = mutableListOf<SOHReading>()
        
        // Start with 100% SOH at car delivery
        val baselineTimestamp = getCarDeliveryTimestamp(carYear)
        readings.add(SOHReading(
            timestamp = baselineTimestamp,
            sohValue = 100.0f
        ))
        
        // Add yearly degradation points (2% per year)
        var currentSOH = 100.0f
        val calendar = Calendar.getInstance()
        
        for (year in carYear + 1..currentYear) {
            currentSOH -= 2.0f // 2% degradation per year
            if (currentSOH < 0f) currentSOH = 0f // Don't go below 0%
            
            calendar.set(year, 0, 1, 0, 0, 0) // January 1st of each year
            calendar.set(Calendar.MILLISECOND, 0)
            
            readings.add(SOHReading(
                timestamp = calendar.timeInMillis,
                sohValue = currentSOH
            ))
        }
        
        return readings
    }
    
    /**
     * Get car year from VIN (10th character represents model year)
     */
    private fun getCarYearFromVIN(): Int? {
        return try {
            // Try to get VIN from shared preferences (stored from vehicle data)
            val vin = sharedPreferences.getString("vehicle_vin", null)
            if (vin != null && vin.length >= 10) {
                val yearChar = vin[9] // 10th character (0-indexed)
                val year = when (yearChar) {
                    'A' -> 2010
                    'B' -> 2011
                    'C' -> 2012
                    'D' -> 2013
                    'E' -> 2014
                    'F' -> 2015
                    'G' -> 2016
                    'H' -> 2017
                    'J' -> 2018
                    'K' -> 2019
                    'L' -> 2020
                    'M' -> 2021
                    'N' -> 2022
                    'P' -> 2023
                    'R' -> 2024
                    'S' -> 2025
                    'T' -> 2026
                    'V' -> 2027
                    'W' -> 2028
                    'X' -> 2029
                    'Y' -> 2030
                    '1' -> 2031
                    '2' -> 2032
                    '3' -> 2033
                    '4' -> 2034
                    '5' -> 2035
                    '6' -> 2036
                    '7' -> 2037
                    '8' -> 2038
                    '9' -> 2039
                    else -> null
                }
                year
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Get timestamp representing when car was delivered (start of model year)
     */
    private fun getCarDeliveryTimestamp(year: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.set(year, 0, 1, 0, 0, 0) // January 1st of the year
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
    
    /**
     * Get SOH readings within a date range
     */
    suspend fun getSOHReadingsInRange(startDate: Date, endDate: Date): List<SOHReading> = withContext(Dispatchers.IO) {
        getAllSOHReadings().filter { reading ->
            reading.date >= startDate && reading.date <= endDate
        }
    }
    
    /**
     * Clear all SOH readings
     */
    suspend fun clearAllReadings() = withContext(Dispatchers.IO) {
        sharedPreferences.edit()
            .remove(sohReadingsKey)
            .remove(baselineAddedKey) // Reset baseline flag so it gets recreated
            .apply()
    }
    
    /**
     * Clear only theoretical readings (keep manual readings)
     */
    suspend fun clearTheoreticalReadings() = withContext(Dispatchers.IO) {
        val carYear = getCarYearFromVIN() ?: 2020
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        
        val jsonStrings = sharedPreferences.getStringSet(sohReadingsKey, emptySet()) ?: emptySet()
        val readings = jsonStrings.mapNotNull { SOHReading.fromJson(it) }
        
        // Keep only manual readings (non-theoretical)
        val manualReadings = readings.filter { reading ->
            !isTheoreticalReading(reading, carYear, currentYear)
        }
        
        val manualJsonStrings = manualReadings.map { SOHReading.toJson(it) }
        sharedPreferences.edit()
            .putStringSet(sohReadingsKey, manualJsonStrings.toSet())
            .apply()
    }
    
    /**
     * Get the latest SOH reading
     */
    suspend fun getLatestSOHReading(): SOHReading? = withContext(Dispatchers.IO) {
        getAllSOHReadings().maxByOrNull { it.timestamp }
    }
    
    /**
     * Get statistics about SOH readings
     */
    suspend fun getSOHStatistics(): SOHStatistics = withContext(Dispatchers.IO) {
        val readings = getAllSOHReadings()
        if (readings.isEmpty()) {
            SOHStatistics(0, 0f, 0f, 0f, null, null)
        } else {
            val values = readings.map { it.sohValue }
            SOHStatistics(
                totalReadings = readings.size,
                averageSOH = values.average().toFloat(),
                minSOH = values.minOrNull() ?: 0f,
                maxSOH = values.maxOrNull() ?: 0f,
                firstReading = readings.firstOrNull(),
                lastReading = readings.lastOrNull()
            )
        }
    }
}

/**
 * Statistics about SOH readings
 */
data class SOHStatistics(
    val totalReadings: Int,
    val averageSOH: Float,
    val minSOH: Float,
    val maxSOH: Float,
    val firstReading: SOHReading?,
    val lastReading: SOHReading?
)
