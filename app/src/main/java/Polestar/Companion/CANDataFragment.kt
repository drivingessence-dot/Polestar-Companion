package Polestar.Companion

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
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
        
        // Deliver any buffered CAN messages from MainActivity
        val mainActivity = activity as? MainActivity
        mainActivity?.deliverBufferedCANMessages()
        
        Log.d(TAG, "CANDataFragment onViewCreated completed")
    }
    
    private fun setupRecyclerView() {
        canMessageAdapter = CANMessageAdapter()
        binding.recyclerViewCanMessages.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = canMessageAdapter
            
            // Enable smooth scrolling for better performance
            setHasFixedSize(true)
            setItemViewCacheSize(50)
            
            // Add scroll listener for auto-scroll control
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
                    
                    // If user scrolled up, disable auto-scroll
                    if (firstVisiblePosition > 0) {
                        canMessageAdapter.setAutoScroll(false)
                    } else {
                        canMessageAdapter.setAutoScroll(true)
                    }
                }
            })
        }
    }
    
    private fun setupButtons() {
        
        binding.btnStopSession.setOnClickListener {
            stopSession()
        }
        
        
        binding.btnExportData.setOnClickListener {
            exportData()
        }
        
        binding.btnRefreshData.setOnClickListener {
            refreshData()
        }
        
        // Test messages button
        binding.btnTestMessages.setOnClickListener {
            Log.d(TAG, "Test Messages button clicked")
            val mainActivity = activity as? MainActivity
            mainActivity?.testCANMessageFlow()
        }
        
            // Add debug button for GVRET status
            binding.btnTestMessages.setOnLongClickListener {
                Log.d(TAG, "Long press on test messages button - debugging GVRET status")
                val mainActivity = activity as? MainActivity
                mainActivity?.debugGVRETStatus()
                true
            }

            // Add long press for connection diagnostics
            binding.btnRefreshData.setOnLongClickListener {
                Log.d(TAG, "Long press on refresh button - showing connection diagnostics")
                val mainActivity = activity as? MainActivity
                val diagnostics = "Connection Diagnostics:\n" +
                        "GVRET Client: ${mainActivity?.gvretClient != null}\n" +
                        "Connected: ${mainActivity?.isConnectedToMacchina}\n" +
                        "Fragment Ready: ${canMessageAdapter != null}\n" +
                        "Message Count: ${canMessageAdapter?.getMessageCount() ?: 0}"
                
                AlertDialog.Builder(requireContext())
                    .setTitle("Connection Diagnostics")
                    .setMessage(diagnostics)
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                    .show()
                true
            }
            
            // Add double-tap for comprehensive CAN data flow diagnostic
            var lastTapTime = 0L
            binding.btnClearData.setOnClickListener {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTapTime < 500) { // Double tap within 500ms
                    Log.d(TAG, "Double tap on clear button - running CAN data flow diagnostic")
                    val mainActivity = activity as? MainActivity
                    mainActivity?.diagnoseCANDataFlow()
                } else {
                    clearData()
                }
                lastTapTime = currentTime
            }
            
            // Add triple-tap for force starting CAN monitoring
            var tapCount = 0
            var lastTapTime2 = 0L
            binding.btnStartSession.setOnClickListener {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTapTime2 < 1000) { // Within 1 second
                    tapCount++
                    if (tapCount >= 3) { // Triple tap
                        Log.d(TAG, "Triple tap on start button - force starting CAN monitoring")
                        val mainActivity = activity as? MainActivity
                        mainActivity?.forceStartCANMonitoring()
                        tapCount = 0
                    }
                } else {
                    tapCount = 1
                }
                lastTapTime2 = currentTime
                
                // Normal single tap behavior
                if (tapCount == 1) {
                    startSession()
                }
            }
    }
    
    fun startSession() {
        Log.d(TAG, "=== CANDataFragment.startSession() called ===")
        
        lifecycleScope.launch {
            try {
                // Check if binding is still valid
                if (_binding == null) {
                    Log.e(TAG, "Binding is null in startSession - fragment may be destroyed")
                    return@launch
                }
                
                // Check if CAN interface is available
                val mainActivity = activity as? MainActivity
                Log.d(TAG, "MainActivity reference: ${if (mainActivity != null) "found" else "null"}")
                
                val isGVRETReady = mainActivity?.isGVRETConnectionReady()
                Log.d(TAG, "GVRET connection ready: $isGVRETReady")
                
                if (isGVRETReady != true) {
                    Log.e(TAG, "GVRET connection not available - showing error dialog")
                    showCANError("GVRET connection not available. Please ensure Macchina A0 is connected via WiFi and the app is connected.")
                    return@launch
                }
                
                Log.d(TAG, "Starting CAN data manager session")
                canDataManager.startSession()
                isMonitoring = true
                updateUI()
                startCANMonitoring()
                
                // Wait a bit for everything to initialize
                delay(100)
                
                // GVRET connection is already active and reading CAN messages
                Log.d(TAG, "GVRET WiFi connection is active and reading CAN messages")
                
                Toast.makeText(context, "CAN capture session started - Reading from Macchina A0 via WiFi GVRET", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "=== CAN session started successfully ===")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting CAN session", e)
                Toast.makeText(context, "Error starting CAN session: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showCANError(message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("CAN Communication Error")
            .setMessage("$message\n\nTroubleshooting:\n• Ensure Macchina A0 is connected via WiFi\n• Check you're connected to Macchina A0's WiFi network\n• Verify GVRET connection is active\n• Check vehicle is running")
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
            
            // GVRET connection continues to run - just stop the session
            Log.d(TAG, "Stopped CAN session - GVRET connection remains active")
            
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
    
    // Method to enable/disable auto-scroll
    fun setAutoScroll(enabled: Boolean) {
        canMessageAdapter.setAutoScroll(enabled)
        if (enabled) {
            binding.recyclerViewCanMessages.smoothScrollToPosition(0)
        }
    }
    
    // Method to get current message count
    fun getMessageCount(): Int = canMessageAdapter.getMessageCount()
    
    // Method to get latest message
    fun getLatestMessage(): CANMessage? = canMessageAdapter.getLatestMessage()
    
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
            try {
                val messages = canDataManager.getAllMessages()
                
                // Check if binding is still valid
                if (_binding == null) {
                    Log.w(TAG, "Binding is null in refreshData - fragment may be destroyed")
                    return@launch
                }
                
                canMessageAdapter.updateMessages(messages)
                updateUI()
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing data", e)
            }
        }
    }
    
    private fun startCANMonitoring() {
        lifecycleScope.launch {
            try {
                while (isMonitoring) {
                    // Check if binding is still valid
                    if (_binding == null) {
                        Log.w(TAG, "Binding is null in startCANMonitoring - stopping monitoring")
                        break
                    }
                    
                    // Real CAN monitoring is now handled by the native library
                    // The native library will automatically capture CAN messages
                    // and call the callback when raw capture is active
                    
                    // Just refresh the UI periodically to show new messages
                    refreshData()
                    delay(500) // Update every 500ms
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in CAN monitoring loop", e)
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
            try {
                val stats = canDataManager.getSessionStats()
                
                // Check if binding is still valid
                if (_binding == null) {
                    Log.w(TAG, "Binding is null in updateUI - fragment may be destroyed")
                    return@launch
                }
                
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
            } catch (e: Exception) {
                Log.e(TAG, "Error updating UI", e)
            }
        }
    }
    
    // Method to receive CAN messages from MainActivity
    fun addCANMessage(message: CANMessage) {
        Log.d(TAG, "CANDataFragment.addCANMessage called: ID=${message.getIdAsHex()}, Data=${message.getDataAsHex()}")
        
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Adding message to CANDataManager")
                canDataManager.addMessage(message)
                
                // Check if binding is still valid
                if (_binding == null) {
                    Log.w(TAG, "Binding is null in addCANMessage - fragment may be destroyed")
                    return@launch
                }
                
                // Add message directly to adapter for real-time display
                canMessageAdapter.addMessage(message)
                
                // Auto-scroll to top if enabled
                if (canMessageAdapter.isAutoScrollEnabled()) {
                    binding.recyclerViewCanMessages.smoothScrollToPosition(0)
                }
                
                updateUI()
                
                Log.d(TAG, "CAN message added to adapter. Total messages: ${canMessageAdapter.getMessageCount()}")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding CAN message", e)
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
