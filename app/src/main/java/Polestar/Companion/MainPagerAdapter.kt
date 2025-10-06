package Polestar.Companion

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import Polestar.Companion.databinding.FragmentMainContentBinding

/**
 * Adapter for ViewPager2 to handle main page, SOH graph page, and CAN data page
 */
class MainPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {
    
    override fun getItemCount(): Int = 3
    
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> MainContentFragment()
            1 -> SOHGraphFragment.newInstance()
            2 -> CANDataFragment()
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }
}

/**
 * Fragment containing the main content (dashboard)
 */
class MainContentFragment : Fragment() {
    
    private var _binding: FragmentMainContentBinding? = null
    private val binding get() = _binding!!
    private var mainActivity: MainActivity? = null
    private var currentSelectedYear: Int = 2021 // Track current year to avoid dialog on initial setup
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentMainContentBinding.inflate(inflater, container, false)
        mainActivity = activity as? MainActivity
        
        // Setup UI interactions
        setupUI()
        
        return binding.root
    }
    
    private fun setupUI() {
        // Set up year spinner
        setupYearSpinner()
        
        // Set up button click listeners
        binding.btnStartMonitoring.setOnClickListener {
            mainActivity?.startMonitoring()
        }
        
        binding.btnStopMonitoring.setOnClickListener {
            mainActivity?.stopMonitoring()
        }
        
        binding.btnRefreshData.setOnClickListener {
            mainActivity?.updateVehicleData()
        }
        
        binding.btnGetSoh.setOnClickListener {
            // Check if we have a GVRET connection before requesting SOH
            if (mainActivity?.isConnectedToMacchina == true) {
                mainActivity?.requestVehicleSOH()
                mainActivity?.showToast("Requesting SOH from BECM...")
                
                // Update SOH display after a delay to allow for response
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    mainActivity?.updateSOH()
                    // Save SOH data and refresh graph
                    mainActivity?.saveSOHDataAndRefreshGraph()
                }, 2000) // Increased delay to 2 seconds for BECM response
            } else {
                mainActivity?.showToast("Not connected to Macchina A0. Please connect first.")
                Log.w("MainContentFragment", "Cannot request SOH - not connected to Macchina A0")
            }
        }
        
        // Set up settings button
        binding.btnSettings.setOnClickListener {
            mainActivity?.openSettings()
        }
        
        // Set up connection settings button
        binding.btnConnectionSettings.setOnClickListener {
            mainActivity?.openConnectionSettings()
        }
    }
    
    private fun setupYearSpinner() {
        // Create years array from 2021 to 2030
        val years = (2021..2030).map { it.toString() }.toTypedArray()
        
        // Create adapter for spinner
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            years
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        // Set adapter to spinner
        binding.spinnerYear.adapter = adapter
        
        // Set default selection to 2021 (index 0)
        binding.spinnerYear.setSelection(0)
        
        // Initialize the display text
        binding.textSelectedYear.text = "2021 (choose yours)"
        
        // Set up listener for year changes
        binding.spinnerYear.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedYear = years[position].toInt()
                
                // Only show confirmation dialog if year actually changed (not initial setup)
                if (selectedYear != currentSelectedYear) {
                    showYearChangeConfirmationDialog(selectedYear)
                } else {
                    // Initial setup - just update display and notify MainActivity
                    binding.textSelectedYear.text = "$selectedYear (choose yours)"
                    mainActivity?.onYearChanged(selectedYear)
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
    }
    
    private fun showYearChangeConfirmationDialog(selectedYear: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle("Change Car Year")
            .setMessage("Are you sure you want to change the car year to $selectedYear?\n\nThis will reset the SOH graph and regenerate the baseline data.")
            .setPositiveButton("Yes, Change Year") { _, _ ->
                // User confirmed - proceed with year change
                binding.textSelectedYear.text = "$selectedYear (choose yours)"
                currentSelectedYear = selectedYear
                mainActivity?.onYearChanged(selectedYear)
            }
            .setNegativeButton("Cancel") { _, _ ->
                // User cancelled - revert spinner to previous selection
                val years = (2021..2030).map { it.toString() }.toTypedArray()
                val previousIndex = years.indexOf(currentSelectedYear.toString())
                if (previousIndex >= 0) {
                    binding.spinnerYear.setSelection(previousIndex)
                }
            }
            .setCancelable(false)
            .show()
    }
    
    fun getSelectedYear(): Int {
        val selectedPosition = binding.spinnerYear.selectedItemPosition
        return 2021 + selectedPosition // Years start from 2021
    }
    
    fun getFragmentBinding(): FragmentMainContentBinding? = _binding
    
    /**
     * Update real-time CAN data display
     */
    fun updateRealTimeData(canData: Map<String, Any>) {
        _binding?.let { binding ->
            try {
                // Update CAN status and message count
                val messageCount = canData["message_count"] as? Int ?: 0
                val isConnected = canData["connected"] as? Boolean ?: false
                
                binding.textCanStatus.text = if (isConnected) "CAN Status: Active" else "CAN Status: Disconnected"
                binding.textMessageCount.text = "Messages: $messageCount"
                
                // Update vehicle data with real-time values
                updateVehicleDataDisplay(canData)
                
                // Update battery data
                updateBatteryDataDisplay(canData)
                
                // Update climate data
                updateClimateDataDisplay(canData)
                
                // Update last update timestamp
                binding.textLastUpdate.text = "Just now"
                
            } catch (e: Exception) {
                android.util.Log.e("MainContentFragment", "Error updating real-time data", e)
            }
        }
    }
    
    private fun updateVehicleDataDisplay(canData: Map<String, Any>) {
        _binding?.let { binding ->
            // Update speed
            val speed = canData["speed"] as? Double ?: canData["speed"] as? Int ?: 0.0
            val speedKmh = if (speed is Int) speed.toDouble() else speed
            binding.textSpeed.text = "🏃 Speed: ${String.format("%.1f", speedKmh)} km/h"
            
            // Update gear
            val gear = canData["gear"] as? String ?: "P"
            binding.textGear.text = "⚙️ Gear: $gear"
            
            // Update VIN (if available)
            val vin = canData["vin"] as? String
            if (!vin.isNullOrEmpty()) {
                binding.textVin.text = "🆔 VIN: $vin"
            }
            
            // Update odometer
            val odometer = canData["odometer"] as? Int ?: canData["odometer"] as? Double ?: 0
            val odometerKm = if (odometer is Double) odometer.toInt() else odometer
            binding.textOdometer.text = "📏 Odometer: ${odometerKm} km"
        }
    }
    
    private fun updateBatteryDataDisplay(canData: Map<String, Any>) {
        _binding?.let { binding ->
            // Update SOC (State of Charge - HV Battery)
            val soc = canData["battery_soc"] as? Double ?: canData["soc"] as? Double ?: 66.7
            binding.textSoc.text = "🔋 SOC: ${String.format("%.1f", soc)}%"
            
            // Update battery progress bar
            binding.progressBatterySoc.progress = soc.toInt()
            
            // Update 12V battery voltage (separate from HV battery)
            val voltage12v = canData["voltage_12v"] as? Double ?: canData["voltage"] as? Double ?: 13.7
            binding.textVoltage.text = "⚡ 12V Battery: ${String.format("%.1f", voltage12v)}V"
        }
    }
    
    private fun updateClimateDataDisplay(canData: Map<String, Any>) {
        _binding?.let { binding ->
            // Update ambient temperature
            val ambient = canData["ambient"] as? Int ?: canData["ambient_temp"] as? Int ?: 13
            binding.textAmbient.text = "🌡️ Ambient: ${ambient}°C"
        }
    }
    
    
    /**
     * Update SOH display
     */
    fun updateSOHDisplay(sohValue: Double) {
        _binding?.let { binding ->
            binding.textSoh.text = "Battery SOH: ${String.format("%.2f", sohValue)}%"
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
