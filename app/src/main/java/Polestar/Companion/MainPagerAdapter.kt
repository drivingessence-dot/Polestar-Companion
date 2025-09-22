package Polestar.Companion

import android.app.AlertDialog
import android.os.Bundle
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
 * Adapter for ViewPager2 to handle main page and SOH graph page
 */
class MainPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {
    
    override fun getItemCount(): Int = 2
    
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> MainContentFragment()
            1 -> SOHGraphFragment.newInstance()
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
            mainActivity?.requestSOH()
            mainActivity?.showToast("Requesting SOH from BECM...")
            // Update SOH display after a short delay to allow for response
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                mainActivity?.updateSOH()
                // Save SOH data and refresh graph
                mainActivity?.saveSOHDataAndRefreshGraph()
            }, 1000)
        }
        
        // Set up settings button
        binding.btnSettings.setOnClickListener {
            mainActivity?.openSettings()
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
    
    fun getFragmentBinding(): FragmentMainContentBinding = binding
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
