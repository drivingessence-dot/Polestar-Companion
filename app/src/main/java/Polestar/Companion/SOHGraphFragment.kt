package Polestar.Companion

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Fragment displaying SOH (State of Health) line graph over time
 */
class SOHGraphFragment : Fragment() {
    
    private lateinit var lineChart: LineChart
    private lateinit var sohDataManager: SOHDataManager
    private var mainActivity: MainActivity? = null
    private lateinit var resetButton: android.widget.Button
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_soh_graph, container, false)
        lineChart = view.findViewById(R.id.line_chart)
        resetButton = view.findViewById(R.id.btn_reset_graph)
        sohDataManager = SOHDataManager(requireContext())
        mainActivity = activity as? MainActivity
        
        // Set the selected year in the data manager
        mainActivity?.let { 
            lifecycleScope.launch {
                sohDataManager.setCarYear(it.getSelectedCarYear())
            }
        }
        
        setupChart()
        setupResetButton()
        loadSOHData()
        setupPeriodicRefresh()
        
        return view
    }
    
    private fun setupChart() {
        // Basic chart setup
        lineChart.description.isEnabled = false
        lineChart.setTouchEnabled(true)
        lineChart.isDragEnabled = true
        lineChart.setScaleEnabled(true)
        lineChart.setPinchZoom(true)
        lineChart.setBackgroundColor(Color.TRANSPARENT)
        
        // X-axis setup
        val xAxis = lineChart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.granularity = 1f
        xAxis.isGranularityEnabled = true
        xAxis.setLabelCount(0, false) // Will be set dynamically in updateChart
        xAxis.setAvoidFirstLastClipping(true) // Ensure labels are visible
        xAxis.textColor = Color.parseColor("#FF4A90E2") // Bright blue text color
        
        // Y-axis setup
        val leftAxis = lineChart.axisLeft
        leftAxis.setDrawGridLines(true)
        leftAxis.gridColor = Color.parseColor("#FF666666") // Brighter grid lines for better contrast
        leftAxis.axisMinimum = 40f
        leftAxis.axisMaximum = 100f
        leftAxis.setLabelCount(7, true) // Show 7 labels: 40%, 50%, 60%, 70%, 80%, 90%, 100%
        leftAxis.textColor = Color.parseColor("#FF4A90E2") // Bright blue text color
        leftAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return "${value.toInt()}%"
            }
        }
        
        val rightAxis = lineChart.axisRight
        rightAxis.isEnabled = false
        
        // Legend setup
        val legend = lineChart.legend
        legend.isEnabled = true
        legend.textColor = Color.WHITE
        legend.textSize = 12f
    }
    
    private fun setupResetButton() {
        resetButton.setOnClickListener {
            showResetGraphConfirmationDialog()
        }
    }
    
    private fun showResetGraphConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Reset SOH Graph")
            .setMessage("Are you sure you want to reset the SOH graph?\n\nThis will permanently delete all SOH readings and cannot be undone.")
            .setPositiveButton("Yes, Reset Graph") { _, _ ->
                // User confirmed - proceed with reset
                lifecycleScope.launch {
                    // Clear all SOH data
                    sohDataManager.clearAllReadings()
                    // Refresh the chart
                    loadSOHData()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                // User cancelled - do nothing
            }
            .setCancelable(false)
            .show()
    }
    
    private fun loadSOHData() {
        lifecycleScope.launch {
            val readings = sohDataManager.getAllSOHReadings()
            updateChart(readings)
        }
    }
    
    private fun updateChart(readings: List<SOHReading>) {
        if (readings.isEmpty()) {
            // Show empty state
            lineChart.clear()
            lineChart.invalidate()
            updateStatistics(emptyList())
            return
        }
        
        // Separate manual readings from theoretical readings
        val manualReadings = readings.filter { reading ->
            !isTheoreticalReading(reading)
        }
        val theoreticalReadings = readings.filter { reading ->
            isTheoreticalReading(reading)
        }
        
        val entries = mutableListOf<Entry>()
        
        // Add theoretical readings (one plot point for each theoretical reading)
        if (theoreticalReadings.isNotEmpty()) {
            theoreticalReadings.forEachIndexed { index, reading ->
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = reading.timestamp
                val year = calendar.get(Calendar.YEAR)
                android.util.Log.d("SOHGraph", "Theoretical entry: year=$year, index=$index, SOH=${reading.sohValue}%")
                entries.add(Entry(index.toFloat(), reading.sohValue))
            }
        }
        
        // Add manual readings (grouped by month)
        if (manualReadings.isNotEmpty()) {
            val readingsByMonth = manualReadings.groupBy { reading ->
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = reading.timestamp
                "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.MONTH)}"
            }
            
            readingsByMonth.forEach { (monthKey, monthReadings) ->
                val latestReading = monthReadings.maxByOrNull { it.timestamp }!!
                android.util.Log.d("SOHGraph", "Manual entry: month=$monthKey, timestamp=${latestReading.timestamp}, SOH=${latestReading.sohValue}%")
                // Add manual readings after theoretical readings
                entries.add(Entry((theoreticalReadings.size + entries.size).toFloat(), latestReading.sohValue))
            }
        }
        
        // Add current month/year as a data point at the end of X-axis
        val currentCalendar = Calendar.getInstance()
        val currentYear = currentCalendar.get(Calendar.YEAR)
        val currentMonth = currentCalendar.get(Calendar.MONTH)
        
        // Calculate current SOH based on selected year and current year
        val selectedYear = mainActivity?.getSelectedCarYear() ?: 2021
        val currentSOH = if (currentYear <= selectedYear) {
            100f
        } else {
            val yearsSinceSelected = currentYear - selectedYear
            maxOf(40f, 100f - (yearsSinceSelected * 2f)) // 2% degradation per year, minimum 40%
        }
        
        // Add current month/year data point at the end
        val currentIndex = entries.size.toFloat()
        android.util.Log.d("SOHGraph", "Adding current month/year entry: year=$currentYear, month=$currentMonth, index=$currentIndex, SOH=${currentSOH}%")
        entries.add(Entry(currentIndex, currentSOH))
        
        // Sort all entries by index (X value)
        val sortedEntries = entries.sortedBy { it.x }
        
        // Create dataset
        val dataSet = LineDataSet(sortedEntries, "Battery SOH")
        dataSet.color = Color.parseColor("#FFD700") // Gold color
        dataSet.setCircleColor(Color.parseColor("#FFD700"))
        dataSet.lineWidth = 3f
        dataSet.circleRadius = 4f
        dataSet.setDrawCircleHole(false)
        dataSet.setDrawValues(false)
        dataSet.setDrawFilled(true)
        dataSet.fillColor = Color.parseColor("#33FFD700") // Semi-transparent gold
        dataSet.fillAlpha = 100
        
        // Create line data
        val lineData = LineData(dataSet)
        lineChart.data = lineData
        
        // Auto-fit the chart
        lineChart.fitScreen()
        lineChart.invalidate()
        
        // Set X-axis bounds to show proper range
        if (sortedEntries.isNotEmpty()) {
            val minIndex = sortedEntries.minOf { it.x }
            val maxIndex = sortedEntries.maxOf { it.x }
            
            lineChart.xAxis.axisMinimum = minIndex
            lineChart.xAxis.axisMaximum = maxIndex
            
            // Set label count to match the number of data points
            lineChart.xAxis.setLabelCount(sortedEntries.size, false)
            
            // Set dynamic formatter based on reading type and index
            lineChart.xAxis.valueFormatter = object : ValueFormatter() {
                private val yearFormat = SimpleDateFormat("yyyy", Locale.getDefault())
                private val monthFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())
                
                override fun getFormattedValue(value: Float): String {
                    val index = value.toInt()
                    
                    // Get the reading for this index
                    val reading = if (index < theoreticalReadings.size) {
                        theoreticalReadings[index]
                    } else if (index < theoreticalReadings.size + manualReadings.size) {
                        // This is a manual reading
                        val manualIndex = index - theoreticalReadings.size
                        val manualReadingsList = manualReadings.sortedBy { it.timestamp }
                        if (manualIndex < manualReadingsList.size) {
                            manualReadingsList[manualIndex]
                        } else null
                    } else {
                        // This is the current month/year data point
                        null
                    }
                    
                    return if (reading != null && !isTheoreticalReading(reading)) {
                        // Manual reading - show month and year
                        monthFormat.format(Date(reading.timestamp))
                    } else if (reading != null) {
                        // Theoretical reading - show year only
                        val calendar = Calendar.getInstance()
                        calendar.timeInMillis = reading.timestamp
                        yearFormat.format(Date(reading.timestamp))
                    } else {
                        // Current month/year data point
                        monthFormat.format(Date())
                    }
                }
            }
        }
        
        // Update statistics
        updateStatistics(readings)
        
        // Refresh the chart
        lineChart.invalidate()
    }
    
    private fun updateStatistics(readings: List<SOHReading>) {
        if (readings.isEmpty()) {
            view?.findViewById<android.widget.TextView>(R.id.text_total_readings)?.text = "Total Readings: 0"
            view?.findViewById<android.widget.TextView>(R.id.text_current_soh)?.text = "Current SOH: N/A"
            view?.findViewById<android.widget.TextView>(R.id.text_yearly_degradation)?.text = "No degradation data available"
            val averageTextView = view?.findViewById<android.widget.TextView>(R.id.text_average_yearly_degradation)
            averageTextView?.text = formatAverageDegradationText("N/A")
            return
        }
        
                // Update basic statistics
                val totalReadings = calculateTotalReadings(readings)
                val currentSOH = calculateCurrentSOH(readings)

                view?.findViewById<android.widget.TextView>(R.id.text_total_readings)?.text = "Total Readings: $totalReadings"
                view?.findViewById<android.widget.TextView>(R.id.text_current_soh)?.text = "Current SOH: ${currentSOH.toInt()}%"
        
        // Calculate yearly degradation
        val yearlyDegradation = calculateYearlyDegradation(readings)
        view?.findViewById<android.widget.TextView>(R.id.text_yearly_degradation)?.text = yearlyDegradation
        
        // Calculate average yearly degradation
        val averageYearlyDegradation = calculateAverageYearlyDegradation(readings)
        val averageTextView = view?.findViewById<android.widget.TextView>(R.id.text_average_yearly_degradation)
        averageTextView?.text = formatAverageDegradationText(averageYearlyDegradation)
    }
    
    private fun calculateYearlyDegradation(readings: List<SOHReading>): String {
        if (readings.size < 2) {
            return "Insufficient data for yearly analysis"
        }
        
        // Group readings by year
        val readingsByYear = readings.groupBy { reading ->
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = reading.timestamp
            calendar.get(Calendar.YEAR)
        }
        
        val degradationText = StringBuilder()
        val sortedYears = readingsByYear.keys.sorted()
        val selectedYear = mainActivity?.getSelectedCarYear() ?: 2021
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        
        // Filter out years before the selected year
        val relevantYears = sortedYears.filter { it >= selectedYear }
        
        if (relevantYears.size < 2) {
            return "No degradation data available for selected year"
        }
        
        // Only show degradation up to current real year
        val endIndex = relevantYears.indexOfFirst { it > currentYear }
        val actualEndIndex = if (endIndex == -1) relevantYears.size - 1 else endIndex - 1
        
        for (i in 0 until actualEndIndex) {
            val yearFrom = relevantYears[i]
            val yearTo = relevantYears[i + 1]
            
            val currentYearReadings = readingsByYear[yearFrom]!!
            val nextYearReadings = readingsByYear[yearTo]!!
            
            // Get the last reading of current year and first reading of next year
            val endOfCurrentYear = currentYearReadings.maxByOrNull { it.timestamp }!!
            val startOfNextYear = nextYearReadings.minByOrNull { it.timestamp }!!
            
            val degradation = endOfCurrentYear.sohValue - startOfNextYear.sohValue
            
            if (degradation > 0) {
                degradationText.append("$yearFrom → $yearTo: -${degradation.toInt()}%\n")
            } else if (degradation < 0) {
                degradationText.append("$yearFrom → $yearTo: +${(-degradation).toInt()}%\n")
            } else {
                degradationText.append("$yearFrom → $yearTo: No change\n")
            }
        }
        
        return if (degradationText.isEmpty()) {
            "No significant yearly changes detected"
        } else {
            degradationText.toString().trim()
        }
    }
    
    private fun calculateAverageYearlyDegradation(readings: List<SOHReading>): String {
        if (readings.size < 2) {
            return "Average Yearly Degradation: N/A"
        }
        
        // Group readings by year
        val readingsByYear = readings.groupBy { reading ->
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = reading.timestamp
            calendar.get(Calendar.YEAR)
        }
        
        val sortedYears = readingsByYear.keys.sorted()
        if (sortedYears.size < 2) {
            return "Average Yearly Degradation: N/A"
        }
        
        val degradationValues = mutableListOf<Float>()
        val selectedYear = mainActivity?.getSelectedCarYear() ?: 2021
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        
        // Filter out years before the selected year
        val relevantYears = sortedYears.filter { it >= selectedYear }
        
        if (relevantYears.size < 2) {
            return "N/A"
        }
        
        // Only show degradation up to current real year
        val endIndex = relevantYears.indexOfFirst { it > currentYear }
        val actualEndIndex = if (endIndex == -1) relevantYears.size - 1 else endIndex - 1
        
        for (i in 0 until actualEndIndex) {
            val yearFrom = relevantYears[i]
            val yearTo = relevantYears[i + 1]
            
            val currentYearReadings = readingsByYear[yearFrom]!!
            val nextYearReadings = readingsByYear[yearTo]!!
            
            // Get the last reading of current year and first reading of next year
            val endOfCurrentYear = currentYearReadings.maxByOrNull { it.timestamp }!!
            val startOfNextYear = nextYearReadings.minByOrNull { it.timestamp }!!
            
            val degradation = endOfCurrentYear.sohValue - startOfNextYear.sohValue
            degradationValues.add(degradation)
        }
        
        if (degradationValues.isEmpty()) {
            return "Average Yearly Degradation: N/A"
        }
        
        val averageDegradation = degradationValues.average()
        val formattedAverage = String.format("%.1f", averageDegradation)
        
        return if (averageDegradation > 0) {
            "-${formattedAverage}%"
        } else if (averageDegradation < 0) {
            "+${String.format("%.1f", -averageDegradation)}%"
        } else {
            "0.0%"
        }
    }
    
    private fun isTheoreticalReading(reading: SOHReading): Boolean {
        val selectedYear = mainActivity?.getSelectedCarYear() ?: 2021
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        
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

        // Check if this reading follows the theoretical pattern:
        // - 100% SOH from 2021 to selected year
        // - 2% degradation per year from selected year + 1 onwards
        val expectedSOH = when {
            readingYear >= 2021 && readingYear <= selectedYear -> {
                100.0f // Should be 100% from 2021 to selected year
            }
            readingYear > selectedYear -> {
                val yearsSinceSelectedYear = readingYear - selectedYear
                100.0f - (yearsSinceSelectedYear * 2.0f) // 2% degradation per year
            }
            else -> {
                reading.sohValue // Don't modify readings before 2021
            }
        }

        val tolerance = 0.1f // Small tolerance for floating point comparison
        return kotlin.math.abs(reading.sohValue - expectedSOH) <= tolerance
    }

    private fun calculateTotalReadings(readings: List<SOHReading>): Int {
        val selectedYear = mainActivity?.getSelectedCarYear() ?: 2021
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        
        // Count theoretical readings (from 2021 to current year)
        val theoreticalReadings = readings.filter { isTheoreticalReading(it) }
        
        // Count manual readings (user-added plot points)
        val manualReadings = readings.filter { !isTheoreticalReading(it) }
        
        // Theoretical readings should be: (current year - 2021 + 1) + (current year - selected year + 1)
        // But we'll count actual theoretical readings to be accurate
        val theoreticalCount = theoreticalReadings.size
        val manualCount = manualReadings.size
        
        return theoreticalCount + manualCount
    }

    private fun calculateCurrentSOH(readings: List<SOHReading>): Float {
        val selectedYear = mainActivity?.getSelectedCarYear() ?: 2021
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        
        // If current year is before or equal to selected year, SOH should be 100%
        if (currentYear <= selectedYear) {
            return 100.0f
        }
        
        // Calculate degradation from selected year + 1 to current year
        val yearsSinceSelectedYear = currentYear - selectedYear
        val degradation = yearsSinceSelectedYear * 2.0f // 2% per year
        val currentSOH = 100.0f - degradation
        
        // Don't go below 0%
        return maxOf(currentSOH, 0.0f)
    }
    
    private fun formatAverageDegradationText(percentageValue: String): SpannableString {
        val fullText = "Average Yearly Degradation: $percentageValue"
        val spannableString = SpannableString(fullText)
        
        // Get gold color from resources
        val goldColor = ContextCompat.getColor(requireContext(), R.color.gold_primary)
        
        // Find the start position of the percentage value
        val percentageStart = fullText.indexOf(percentageValue)
        val percentageEnd = percentageStart + percentageValue.length
        
        // Apply gold color to the percentage value
        spannableString.setSpan(
            ForegroundColorSpan(goldColor),
            percentageStart,
            percentageEnd,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        
        return spannableString
    }
    
    /**
     * Setup periodic refresh to update current month/year data point
     */
    private fun setupPeriodicRefresh() {
        // Refresh every minute to update current month/year data point
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val refreshRunnable = object : Runnable {
            override fun run() {
                refreshCurrentMonthYear()
                // Schedule next refresh in 1 minute
                handler.postDelayed(this, 60000)
            }
        }
        handler.postDelayed(refreshRunnable, 60000)
    }
    
    /**
     * Refresh only the current month/year data point without affecting existing plot points
     */
    private fun refreshCurrentMonthYear() {
        lifecycleScope.launch {
            val readings = sohDataManager.getAllSOHReadings()
            updateChart(readings)
        }
    }
    
    /**
     * Refresh the chart with new data
     */
    fun refreshChart() {
        // Update the selected year in the data manager
        mainActivity?.let { 
            lifecycleScope.launch {
                sohDataManager.setCarYear(it.getSelectedCarYear())
                loadSOHData()
            }
        } ?: loadSOHData()
    }
    
    companion object {
        fun newInstance(): SOHGraphFragment {
            return SOHGraphFragment()
        }
    }
}
