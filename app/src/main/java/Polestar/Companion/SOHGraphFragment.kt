package Polestar.Companion

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_soh_graph, container, false)
        lineChart = view.findViewById(R.id.line_chart)
        sohDataManager = SOHDataManager(requireContext())
        
        setupChart()
        loadSOHData()
        
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
        xAxis.valueFormatter = object : ValueFormatter() {
            private val dateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())
            override fun getFormattedValue(value: Float): String {
                val timestamp = value.toLong()
                return dateFormat.format(Date(timestamp))
            }
        }
        
        // Y-axis setup
        val leftAxis = lineChart.axisLeft
        leftAxis.setDrawGridLines(true)
        leftAxis.axisMinimum = 0f
        leftAxis.axisMaximum = 100f
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
        
        // Create entries for the chart
        val entries = readings.mapIndexed { index, reading ->
            Entry(index.toFloat(), reading.sohValue)
        }
        
        // Create dataset
        val dataSet = LineDataSet(entries, "Battery SOH")
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
        
        // Set custom X-axis labels for time
        val xAxis = lineChart.xAxis
        xAxis.valueFormatter = object : ValueFormatter() {
            private val dateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())
            override fun getFormattedValue(value: Float): String {
                val index = value.toInt()
                if (index >= 0 && index < readings.size) {
                    return dateFormat.format(readings[index].date)
                }
                return ""
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
            view?.findViewById<android.widget.TextView>(R.id.text_min_soh)?.text = "Min: N/A"
            view?.findViewById<android.widget.TextView>(R.id.text_max_soh)?.text = "Max: N/A"
            view?.findViewById<android.widget.TextView>(R.id.text_yearly_degradation)?.text = "No degradation data available"
            return
        }
        
        // Update basic statistics
        val totalReadings = readings.size
        val minSOH = readings.minOf { it.sohValue }
        val maxSOH = readings.maxOf { it.sohValue }
        
        view?.findViewById<android.widget.TextView>(R.id.text_total_readings)?.text = "Total Readings: $totalReadings"
        view?.findViewById<android.widget.TextView>(R.id.text_min_soh)?.text = "Min: ${minSOH.toInt()}%"
        view?.findViewById<android.widget.TextView>(R.id.text_max_soh)?.text = "Max: ${maxSOH.toInt()}%"
        
        // Calculate yearly degradation
        val yearlyDegradation = calculateYearlyDegradation(readings)
        view?.findViewById<android.widget.TextView>(R.id.text_yearly_degradation)?.text = yearlyDegradation
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
        
        for (i in 0 until sortedYears.size - 1) {
            val currentYear = sortedYears[i]
            val nextYear = sortedYears[i + 1]
            
            val currentYearReadings = readingsByYear[currentYear]!!
            val nextYearReadings = readingsByYear[nextYear]!!
            
            // Get the last reading of current year and first reading of next year
            val endOfCurrentYear = currentYearReadings.maxByOrNull { it.timestamp }!!
            val startOfNextYear = nextYearReadings.minByOrNull { it.timestamp }!!
            
            val degradation = endOfCurrentYear.sohValue - startOfNextYear.sohValue
            
            if (degradation > 0) {
                degradationText.append("$currentYear → $nextYear: -${degradation.toInt()}%\n")
            } else if (degradation < 0) {
                degradationText.append("$currentYear → $nextYear: +${(-degradation).toInt()}%\n")
            } else {
                degradationText.append("$currentYear → $nextYear: No change\n")
            }
        }
        
        return if (degradationText.isEmpty()) {
            "No significant yearly changes detected"
        } else {
            degradationText.toString().trim()
        }
    }
    
    /**
     * Refresh the chart with new data
     */
    fun refreshChart() {
        loadSOHData()
    }
    
    companion object {
        fun newInstance(): SOHGraphFragment {
            return SOHGraphFragment()
        }
    }
}
