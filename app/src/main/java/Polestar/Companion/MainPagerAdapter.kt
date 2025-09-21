package Polestar.Companion

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    
    fun getFragmentBinding(): FragmentMainContentBinding = binding
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
