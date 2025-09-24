package Polestar.Companion

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import Polestar.Companion.databinding.FragmentCanDataBinding
import java.text.SimpleDateFormat
import java.util.*

class CANDataFragment : Fragment() {
    
    private var _binding: FragmentCanDataBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var canDataManager: CANDataManager
    private lateinit var canMessageAdapter: CANMessageAdapter
    private var isMonitoring = false
    
    companion object {
        private const val TAG = "CANDataFragment"
        private const val PERMISSION_REQUEST_CODE = 1001
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentCanDataBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        canDataManager = CANDataManager(requireContext())
        
        setupRecyclerView()
        setupButtons()
        loadSessionState()
        updateUI()
    }
    
    private fun setupRecyclerView() {
        canMessageAdapter = CANMessageAdapter()
        binding.recyclerViewCanMessages.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = canMessageAdapter
        }
    }
    
    private fun setupButtons() {
        binding.btnStartSession.setOnClickListener {
            startSession()
        }
        
        binding.btnStopSession.setOnClickListener {
            stopSession()
        }
        
        binding.btnClearData.setOnClickListener {
            clearData()
        }
        
        binding.btnExportData.setOnClickListener {
            exportData()
        }
        
        binding.btnRefreshData.setOnClickListener {
            refreshData()
        }
    }
    
    private fun startSession() {
        lifecycleScope.launch {
            // Check if CAN interface is available
            val mainActivity = activity as? MainActivity
            if (mainActivity?.isCANInterfaceReady() != true) {
                showCANError("CAN interface not available. Please ensure Macchina A0 OBD reader is connected.")
                return@launch
            }
            
            canDataManager.startSession()
            isMonitoring = true
            updateUI()
            startCANMonitoring()
            
            // Start native CAN capture
            mainActivity?.startRawCANCapture()
            
            Toast.makeText(context, "CAN capture session started - Reading from Machinna A0", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showCANError(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("CAN Communication Error")
            .setMessage("$message\n\nTroubleshooting:\n• Ensure Machinna A0 is connected\n• Check vehicle is running\n• Verify CAN interface is available")
            .setPositiveButton("OK") { dialog: android.content.DialogInterface, which: Int ->
                // Do nothing
            }
            .setCancelable(false)
            .show()
    }
    
    private fun stopSession() {
        lifecycleScope.launch {
            canDataManager.stopSession()
            isMonitoring = false
            updateUI()
            
            // Stop native CAN capture
            val mainActivity = activity as? MainActivity
            mainActivity?.stopRawCANCapture()
            
            Toast.makeText(context, "CAN capture session stopped", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun clearData() {
        lifecycleScope.launch {
            canDataManager.clearMessages()
            canMessageAdapter.clearMessages()
            updateUI()
            Toast.makeText(context, "All CAN data cleared", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun exportData() {
        lifecycleScope.launch {
            try {
                val filePath = canDataManager.exportToCSV()
                Toast.makeText(context, "Data exported to: $filePath", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun refreshData() {
        lifecycleScope.launch {
            val messages = canDataManager.getAllMessages()
            canMessageAdapter.updateMessages(messages)
            updateUI()
        }
    }
    
    private fun startCANMonitoring() {
        lifecycleScope.launch {
            while (isMonitoring) {
                // Real CAN monitoring is now handled by the native library
                // The native library will automatically capture CAN messages
                // and call the callback when raw capture is active
                
                // Just refresh the UI periodically to show new messages
                refreshData()
                delay(500) // Update every 500ms
            }
        }
    }
    
    private fun loadSessionState() {
        lifecycleScope.launch {
            canDataManager.loadSessionState()
            isMonitoring = canDataManager.isSessionActive()
            refreshData()
        }
    }
    
    private fun updateUI() {
        lifecycleScope.launch {
            val stats = canDataManager.getSessionStats()
            
            binding.textSessionStatus.text = if (stats.isActive) {
                "Session Active - Started at ${stats.getFormattedStartTime()}"
            } else {
                "Session Inactive"
            }
            
            binding.textMessageCount.text = "Messages: ${stats.totalMessages}"
            binding.textUniqueIds.text = "Unique IDs: ${stats.uniqueIds}"
            binding.textSessionDuration.text = "Duration: ${stats.getFormattedDuration()}"
            
            // Update button states
            binding.btnStartSession.isEnabled = !stats.isActive
            binding.btnStopSession.isEnabled = stats.isActive
            binding.btnClearData.isEnabled = stats.totalMessages > 0
            binding.btnExportData.isEnabled = stats.totalMessages > 0
        }
    }
    
    // Method to receive CAN messages from MainActivity
    fun addCANMessage(message: CANMessage) {
        lifecycleScope.launch {
            canDataManager.addMessage(message)
            // Update UI immediately
            val messages = canDataManager.getAllMessages()
            canMessageAdapter.updateMessages(messages)
            updateUI()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
