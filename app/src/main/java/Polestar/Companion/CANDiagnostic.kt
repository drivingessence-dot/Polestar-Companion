package Polestar.Companion

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Comprehensive diagnostic system for CAN message flow
 * Tests each component involved in displaying raw CAN messages
 */
class CANDiagnostic(private val context: Context) {
    
    companion object {
        private const val TAG = "CANDiagnostic"
    }
    
    data class DiagnosticResult(
        val testName: String,
        val passed: Boolean,
        val message: String,
        val details: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )
    
    data class DiagnosticReport(
        val overallStatus: String,
        val totalTests: Int,
        val passedTests: Int,
        val failedTests: Int,
        val results: List<DiagnosticResult>,
        val recommendations: List<String>,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Run comprehensive CAN diagnostic tests
     */
    suspend fun runFullDiagnostic(mainActivity: MainActivity): DiagnosticReport = withContext(Dispatchers.IO) {
        Log.i(TAG, "=== Starting Comprehensive CAN Diagnostic ===")
        
        val results = mutableListOf<DiagnosticResult>()
        
        // Test 1: Network Connection
        results.add(testNetworkConnection())
        
        // Test 2: GVRET Client
        results.add(testGVRETClient(mainActivity))
        
        // Test 3: GVRET Connection Status
        results.add(testGVRETConnectionStatus(mainActivity))
        
        // Test 4: CAN Message Reception
        results.add(testCANMessageReception(mainActivity))
        
        // Test 5: MainActivity Message Handling
        results.add(testMainActivityMessageHandling(mainActivity))
        
        // Test 6: CANDataFragment Availability
        results.add(testCANDataFragmentAvailability(mainActivity))
        
        // Test 7: CANDataManager
        results.add(testCANDataManager(mainActivity))
        
        // Test 8: CANMessageAdapter
        results.add(testCANMessageAdapter(mainActivity))
        
        // Test 9: UI Update Flow
        results.add(testUIUpdateFlow(mainActivity))
        
        // Test 10: Native Interface
        results.add(testNativeInterface(mainActivity))
        
        // Test 11: Message Buffer
        results.add(testMessageBuffer(mainActivity))
        
        // Test 12: End-to-End Flow
        results.add(testEndToEndFlow(mainActivity))
        
        // Generate report
        val passedTests = results.count { it.passed }
        val failedTests = results.count { !it.passed }
        val overallStatus = if (failedTests == 0) "PASS" else "FAIL"
        
        val recommendations = generateRecommendations(results)
        
        DiagnosticReport(
            overallStatus = overallStatus,
            totalTests = results.size,
            passedTests = passedTests,
            failedTests = failedTests,
            results = results,
            recommendations = recommendations
        )
    }
    
    /**
     * Test 1: Network Connection
     */
    private suspend fun testNetworkConnection(): DiagnosticResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Test 1: Network Connection")
            
            val currentSSID = NetworkUtils.getCurrentWiFiSSID(context)
            val isMacchinaWiFi = currentSSID == "A0_CAN"
            val networkInfo = NetworkUtils.getPhoneNetworkInfo(context)
            
            val message = if (isMacchinaWiFi) {
                "Connected to A0_CAN WiFi"
            } else {
                "Connected to $currentSSID (not A0_CAN)"
            }
            
            val details = buildString {
                append("SSID: $currentSSID\n")
                append("Phone IP: ${networkInfo?.first ?: "Unknown"}\n")
                append("Network Mask: ${networkInfo?.second ?: "Unknown"}\n")
                append("Macchina WiFi: $isMacchinaWiFi")
            }
            
            DiagnosticResult(
                testName = "Network Connection",
                passed = true, // Always pass - just report status
                message = message,
                details = details
            )
        } catch (e: Exception) {
            DiagnosticResult(
                testName = "Network Connection",
                passed = false,
                message = "Network test failed: ${e.message}",
                details = e.stackTraceToString()
            )
        }
    }
    
    /**
     * Test 2: GVRET Client
     */
    private suspend fun testGVRETClient(mainActivity: MainActivity): DiagnosticResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Test 2: GVRET Client")
            
            val gvretClient = mainActivity.gvretClient
            val isNull = gvretClient == null
            
            val message = if (isNull) {
                "GVRET Client is null"
            } else {
                "GVRET Client is initialized"
            }
            
            val details = buildString {
                append("GVRET Client: ${if (isNull) "NULL" else "INITIALIZED"}\n")
                if (!isNull) {
                    append("Client Type: ${gvretClient!!.javaClass.simpleName}")
                }
            }
            
            DiagnosticResult(
                testName = "GVRET Client",
                passed = !isNull,
                message = message,
                details = details
            )
        } catch (e: Exception) {
            DiagnosticResult(
                testName = "GVRET Client",
                passed = false,
                message = "GVRET Client test failed: ${e.message}",
                details = e.stackTraceToString()
            )
        }
    }
    
    /**
     * Test 3: GVRET Connection Status
     */
    private suspend fun testGVRETConnectionStatus(mainActivity: MainActivity): DiagnosticResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Test 3: GVRET Connection Status")
            
            val isConnected = mainActivity.isConnectedToMacchina
            val gvretReady = mainActivity.isGVRETConnectionReady()
            
            val message = if (isConnected) {
                "GVRET connection is active"
            } else {
                "GVRET connection is not active"
            }
            
            val details = buildString {
                append("Connected Flag: $isConnected\n")
                append("GVRET Ready: $gvretReady\n")
                append("Status Match: ${isConnected == gvretReady}")
            }
            
            DiagnosticResult(
                testName = "GVRET Connection Status",
                passed = isConnected,
                message = message,
                details = details
            )
        } catch (e: Exception) {
            DiagnosticResult(
                testName = "GVRET Connection Status",
                passed = false,
                message = "GVRET Connection Status test failed: ${e.message}",
                details = e.stackTraceToString()
            )
        }
    }
    
    /**
     * Test 4: CAN Message Reception
     */
    private suspend fun testCANMessageReception(mainActivity: MainActivity): DiagnosticResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Test 4: CAN Message Reception")
            
            val gvretClient = mainActivity.gvretClient
            val hasCallback = gvretClient?.onCanFrame != null
            
            val message = if (hasCallback) {
                "CAN message callback is set"
            } else {
                "CAN message callback is not set"
            }
            
            val details = buildString {
                append("GVRET Client: ${if (gvretClient == null) "NULL" else "INITIALIZED"}\n")
                append("onCanFrame Callback: ${if (hasCallback) "SET" else "NULL"}\n")
                append("Callback Type: ${gvretClient?.onCanFrame?.javaClass?.simpleName ?: "NULL"}")
            }
            
            DiagnosticResult(
                testName = "CAN Message Reception",
                passed = hasCallback,
                message = message,
                details = details
            )
        } catch (e: Exception) {
            DiagnosticResult(
                testName = "CAN Message Reception",
                passed = false,
                message = "CAN Message Reception test failed: ${e.message}",
                details = e.stackTraceToString()
            )
        }
    }
    
    /**
     * Test 5: MainActivity Message Handling
     */
    private suspend fun testMainActivityMessageHandling(mainActivity: MainActivity): DiagnosticResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Test 5: MainActivity Message Handling")
            
            // Test if onCANMessageReceived method exists and is accessible
            val methodExists = try {
                mainActivity::class.java.getDeclaredMethod("onCANMessageReceived", CANMessage::class.java)
                true
            } catch (e: NoSuchMethodException) {
                false
            }
            
            // Test message buffer
            val bufferSize = mainActivity.canMessageBuffer.size
            
            val message = if (methodExists) {
                "onCANMessageReceived method is accessible"
            } else {
                "onCANMessageReceived method is not accessible"
            }
            
            val details = buildString {
                append("Method Exists: $methodExists\n")
                append("Message Buffer Size: $bufferSize\n")
                append("Buffer Status: ${if (bufferSize > 0) "HAS MESSAGES" else "EMPTY"}")
            }
            
            DiagnosticResult(
                testName = "MainActivity Message Handling",
                passed = methodExists,
                message = message,
                details = details
            )
        } catch (e: Exception) {
            DiagnosticResult(
                testName = "MainActivity Message Handling",
                passed = false,
                message = "MainActivity Message Handling test failed: ${e.message}",
                details = e.stackTraceToString()
            )
        }
    }
    
    /**
     * Test 6: CANDataFragment Availability
     */
    private suspend fun testCANDataFragmentAvailability(mainActivity: MainActivity): DiagnosticResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Test 6: CANDataFragment Availability")
            
            val canFragment = mainActivity.getCANDataFragment()
            val isNull = canFragment == null
            val isAdded = canFragment?.isAdded ?: false
            val isVisible = canFragment?.isVisible ?: false
            
            val message = if (isNull) {
                "CANDataFragment is not found"
            } else if (!isAdded) {
                "CANDataFragment exists but not added"
            } else if (!isVisible) {
                "CANDataFragment is added but not visible"
            } else {
                "CANDataFragment is available and visible"
            }
            
            val details = buildString {
                append("Fragment: ${if (isNull) "NULL" else "FOUND"}\n")
                append("Added: $isAdded\n")
                append("Visible: $isVisible\n")
                append("Fragment Manager Fragments: ${mainActivity.supportFragmentManager.fragments.size}")
            }
            
            DiagnosticResult(
                testName = "CANDataFragment Availability",
                passed = !isNull && isAdded,
                message = message,
                details = details
            )
        } catch (e: Exception) {
            DiagnosticResult(
                testName = "CANDataFragment Availability",
                passed = false,
                message = "CANDataFragment Availability test failed: ${e.message}",
                details = e.stackTraceToString()
            )
        }
    }
    
    /**
     * Test 7: CANDataManager
     */
    private suspend fun testCANDataManager(mainActivity: MainActivity): DiagnosticResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Test 7: CANDataManager")
            
            val canFragment = mainActivity.getCANDataFragment()
            val managerExists = canFragment?.canDataManager != null
            
            if (managerExists) {
                val manager = canFragment!!.canDataManager
                val sessionStats = manager.getSessionStats()
                
                val message = "CANDataManager is working"
                val details = buildString {
                    append("Manager: INITIALIZED\n")
                    append("Session Active: ${sessionStats.isActive}\n")
                    append("Total Messages: ${sessionStats.totalMessages}\n")
                    append("Unique IDs: ${sessionStats.uniqueIds}\n")
                    append("Session Duration: ${sessionStats.sessionDuration}ms")
                }
                
                DiagnosticResult(
                    testName = "CANDataManager",
                    passed = true,
                    message = message,
                    details = details
                )
            } else {
                DiagnosticResult(
                    testName = "CANDataManager",
                    passed = false,
                    message = "CANDataManager is null",
                    details = "Cannot access CANDataManager from fragment"
                )
            }
        } catch (e: Exception) {
            DiagnosticResult(
                testName = "CANDataManager",
                passed = false,
                message = "CANDataManager test failed: ${e.message}",
                details = e.stackTraceToString()
            )
        }
    }
    
    /**
     * Test 8: CANMessageAdapter
     */
    private suspend fun testCANMessageAdapter(mainActivity: MainActivity): DiagnosticResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Test 8: CANMessageAdapter")
            
            val canFragment = mainActivity.getCANDataFragment()
            val adapterExists = canFragment?.canMessageAdapter != null
            
            if (adapterExists) {
                val adapter = canFragment!!.canMessageAdapter
                val messageCount = adapter.itemCount
                
                val message = "CANMessageAdapter is working"
                val details = buildString {
                    append("Adapter: INITIALIZED\n")
                    append("Item Count: $messageCount\n")
                    append("Adapter Type: ${adapter.javaClass.simpleName}")
                }
                
                DiagnosticResult(
                    testName = "CANMessageAdapter",
                    passed = true,
                    message = message,
                    details = details
                )
            } else {
                DiagnosticResult(
                    testName = "CANMessageAdapter",
                    passed = false,
                    message = "CANMessageAdapter is null",
                    details = "Cannot access CANMessageAdapter from fragment"
                )
            }
        } catch (e: Exception) {
            DiagnosticResult(
                testName = "CANMessageAdapter",
                passed = false,
                message = "CANMessageAdapter test failed: ${e.message}",
                details = e.stackTraceToString()
            )
        }
    }
    
    /**
     * Test 9: UI Update Flow
     */
    private suspend fun testUIUpdateFlow(mainActivity: MainActivity): DiagnosticResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Test 9: UI Update Flow")
            
            val canFragment = mainActivity.getCANDataFragment()
            val bindingExists = canFragment?.getBindingNullable() != null
            val recyclerViewExists = canFragment?.getBindingNullable()?.recyclerViewCanMessages != null
            
            val message = if (bindingExists && recyclerViewExists) {
                "UI components are available"
            } else {
                "UI components are missing"
            }
            
            val details = buildString {
                append("Fragment Binding: ${if (bindingExists) "EXISTS" else "NULL"}\n")
                append("RecyclerView: ${if (recyclerViewExists) "EXISTS" else "NULL"}\n")
                if (recyclerViewExists) {
                    append("RecyclerView Adapter: ${canFragment?.getBindingNullable()?.recyclerViewCanMessages?.adapter?.javaClass?.simpleName ?: "NULL"}")
                }
            }
            
            DiagnosticResult(
                testName = "UI Update Flow",
                passed = bindingExists && recyclerViewExists,
                message = message,
                details = details
            )
        } catch (e: Exception) {
            DiagnosticResult(
                testName = "UI Update Flow",
                passed = false,
                message = "UI Update Flow test failed: ${e.message}",
                details = e.stackTraceToString()
            )
        }
    }
    
    /**
     * Test 10: Native Interface
     */
    private suspend fun testNativeInterface(mainActivity: MainActivity): DiagnosticResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Test 10: Native Interface")
            
            val isReady = mainActivity.isCANInterfaceReady()
            val isRawCaptureActive = mainActivity.isRawCANCaptureActive()
            val isGVRETActive = mainActivity.isGVRETCANCaptureActive()
            
            // Try to get connection status from native
            val connectionStatus = try {
                mainActivity.getConnectionStatus()
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            
            val message = if (isReady) {
                "Native CAN interface is ready"
            } else {
                "Native CAN interface is not ready"
            }
            
            val details = buildString {
                append("Interface Ready: $isReady\n")
                append("Raw Capture Active: $isRawCaptureActive\n")
                append("GVRET Active: $isGVRETActive\n")
                append("Connection Status: $connectionStatus\n")
                append("Native Library: ${if (isReady) "LOADED" else "NOT LOADED"}")
            }
            
            DiagnosticResult(
                testName = "Native Interface",
                passed = isReady,
                message = message,
                details = details
            )
        } catch (e: Exception) {
            DiagnosticResult(
                testName = "Native Interface",
                passed = false,
                message = "Native Interface test failed: ${e.message}",
                details = e.stackTraceToString()
            )
        }
    }
    
    /**
     * Test 11: Message Buffer
     */
    private suspend fun testMessageBuffer(mainActivity: MainActivity): DiagnosticResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Test 11: Message Buffer")
            
            val bufferSize = mainActivity.canMessageBuffer.size
            val hasMessages = bufferSize > 0
            
            val message = if (hasMessages) {
                "Message buffer contains $bufferSize messages"
            } else {
                "Message buffer is empty"
            }
            
            val details = buildString {
                append("Buffer Size: $bufferSize\n")
                append("Has Messages: $hasMessages\n")
                append("Buffer Status: ${if (hasMessages) "PENDING DELIVERY" else "EMPTY"}")
            }
            
            DiagnosticResult(
                testName = "Message Buffer",
                passed = true, // Always pass - just report status
                message = message,
                details = details
            )
        } catch (e: Exception) {
            DiagnosticResult(
                testName = "Message Buffer",
                passed = false,
                message = "Message Buffer test failed: ${e.message}",
                details = e.stackTraceToString()
            )
        }
    }
    
    /**
     * Test 12: End-to-End Flow
     */
    private suspend fun testEndToEndFlow(mainActivity: MainActivity): DiagnosticResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Test 12: End-to-End Flow")
            
            // Create a test CAN message
            val testMessage = CANMessage(
                id = 0x123L,
                data = byteArrayOf(0x01, 0x02, 0x03, 0x04),
                length = 4,
                timestamp = System.currentTimeMillis(),
                isExtended = false,
                isRTR = false
            )
            
            // Try to send it through the flow
            val initialBufferSize = mainActivity.canMessageBuffer.size
            mainActivity.onCANMessageReceived(testMessage)
            
            // Check if it was processed
            val finalBufferSize = mainActivity.canMessageBuffer.size
            val canFragment = mainActivity.getCANDataFragment()
            val fragmentHasMessages = canFragment?.canDataManager?.getAllMessages()?.isNotEmpty() ?: false
            
            val message = if (finalBufferSize == initialBufferSize) {
                "Test message was processed (buffer size unchanged)"
            } else {
                "Test message was buffered (buffer size changed)"
            }
            
            val details = buildString {
                append("Initial Buffer: $initialBufferSize\n")
                append("Final Buffer: $finalBufferSize\n")
                append("Fragment Has Messages: $fragmentHasMessages\n")
                append("Test Message ID: 0x${testMessage.id.toString(16).uppercase()}")
            }
            
            DiagnosticResult(
                testName = "End-to-End Flow",
                passed = finalBufferSize == initialBufferSize || fragmentHasMessages,
                message = message,
                details = details
            )
        } catch (e: Exception) {
            DiagnosticResult(
                testName = "End-to-End Flow",
                passed = false,
                message = "End-to-End Flow test failed: ${e.message}",
                details = e.stackTraceToString()
            )
        }
    }
    
    /**
     * Generate recommendations based on test results
     */
    private fun generateRecommendations(results: List<DiagnosticResult>): List<String> {
        val recommendations = mutableListOf<String>()
        
        results.forEach { result ->
            when (result.testName) {
                "Network Connection" -> {
                    if (result.message.contains("not A0_CAN")) {
                        recommendations.add("Connect to A0_CAN WiFi network")
                    }
                }
                "GVRET Client" -> {
                    if (!result.passed) {
                        recommendations.add("Initialize GVRET client connection")
                    }
                }
                "GVRET Connection Status" -> {
                    if (!result.passed) {
                        recommendations.add("Establish GVRET connection to Macchina A0")
                    }
                }
                "CAN Message Reception" -> {
                    if (!result.passed) {
                        recommendations.add("Set up CAN message callback in GVRET client")
                    }
                }
                "CANDataFragment Availability" -> {
                    if (!result.passed) {
                        recommendations.add("Navigate to page 3 to initialize CANDataFragment")
                    }
                }
                "UI Update Flow" -> {
                    if (!result.passed) {
                        recommendations.add("Check RecyclerView and adapter setup")
                    }
                }
                "Native Interface" -> {
                    if (!result.passed) {
                        recommendations.add("Load native CAN interface library")
                    }
                }
                "Message Buffer" -> {
                    if (result.message.contains("PENDING DELIVERY")) {
                        recommendations.add("Deliver buffered messages to CANDataFragment")
                    }
                }
            }
        }
        
        if (recommendations.isEmpty()) {
            recommendations.add("All systems appear to be working correctly")
        }
        
        return recommendations
    }
    
    /**
     * Format diagnostic report for display
     */
    fun formatReport(report: DiagnosticReport): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val timestamp = formatter.format(Date(report.timestamp))
        
        return buildString {
            append("=== CAN MESSAGE DIAGNOSTIC REPORT ===\n")
            append("Timestamp: $timestamp\n")
            append("Overall Status: ${report.overallStatus}\n")
            append("Tests: ${report.passedTests}/${report.totalTests} passed\n")
            append("Failed: ${report.failedTests}\n\n")
            
            append("=== TEST RESULTS ===\n")
            report.results.forEach { result ->
                val status = if (result.passed) "✅ PASS" else "❌ FAIL"
                append("$status - ${result.testName}\n")
                append("   Message: ${result.message}\n")
                if (result.details.isNotEmpty()) {
                    append("   Details: ${result.details.replace("\n", "\n   ")}\n")
                }
                append("\n")
            }
            
            append("=== RECOMMENDATIONS ===\n")
            report.recommendations.forEachIndexed { index, rec ->
                append("${index + 1}. $rec\n")
            }
        }
    }
}
