package Polestar.Companion

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.*

class MachinnaA0ConnectionManager(private val context: Context) {
    
    companion object {
        private const val TAG = "MachinnaA0Connection"
        
        // Machinna A0 Bluetooth UUID (standard SPP UUID)
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        
        // Machinna A0 WiFi settings
        private const val WIFI_IP = "192.168.0.10"  // Default Machinna A0 IP
        private const val WIFI_PORT = 35000         // Default Machinna A0 port
        
        // Connection timeout
        private const val CONNECTION_TIMEOUT = 5000 // 5 seconds
    }
    
    enum class ConnectionType {
        BLUETOOTH, WIFI, NONE
    }
    
    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }
    
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var wifiSocket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    
    private var currentConnectionType = ConnectionType.NONE
    private var connectionState = ConnectionState.DISCONNECTED
    
    init {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    }
    
    suspend fun connectBluetooth(deviceAddress: String? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            if (bluetoothAdapter == null) {
                Log.e(TAG, "Bluetooth not supported on this device")
                return@withContext false
            }
            
            if (!bluetoothAdapter!!.isEnabled) {
                Log.e(TAG, "Bluetooth is not enabled")
                return@withContext false
            }
            
            connectionState = ConnectionState.CONNECTING
            Log.i(TAG, "Connecting to Macchina A0 via Bluetooth...")
            
            val device = if (deviceAddress != null) {
                Log.i(TAG, "Using specified device address: $deviceAddress")
                bluetoothAdapter!!.getRemoteDevice(deviceAddress)
            } else {
                // Try to find Macchina A0 device automatically
                Log.i(TAG, "Auto-detecting Macchina A0 device...")
                findMachinnaA0Device()
            }
            
            if (device == null) {
                Log.e(TAG, "Macchina A0 device not found")
                connectionState = ConnectionState.ERROR
                return@withContext false
            }
            
            Log.i(TAG, "Attempting to connect to: ${device.name} (${device.address})")
            bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            bluetoothSocket!!.connect()
            
            inputStream = bluetoothSocket!!.inputStream
            outputStream = bluetoothSocket!!.outputStream
            
            currentConnectionType = ConnectionType.BLUETOOTH
            connectionState = ConnectionState.CONNECTED
            
            Log.i(TAG, "Successfully connected to Macchina A0 via Bluetooth")
            return@withContext true
            
        } catch (e: IOException) {
            Log.e(TAG, "Bluetooth connection failed", e)
            connectionState = ConnectionState.ERROR
            disconnect()
            return@withContext false
        }
    }
    
    suspend fun connectWiFi(ipAddress: String = WIFI_IP, port: Int = WIFI_PORT): Boolean = withContext(Dispatchers.IO) {
        try {
            connectionState = ConnectionState.CONNECTING
            Log.i(TAG, "Connecting to Macchina A0 via WiFi at $ipAddress:$port...")
            
            wifiSocket = Socket()
            wifiSocket!!.connect(java.net.InetSocketAddress(ipAddress, port), CONNECTION_TIMEOUT)
            
            inputStream = wifiSocket!!.getInputStream()
            outputStream = wifiSocket!!.getOutputStream()
            
            currentConnectionType = ConnectionType.WIFI
            connectionState = ConnectionState.CONNECTED
            
            Log.i(TAG, "Successfully connected to Macchina A0 via WiFi")
            return@withContext true
            
        } catch (e: IOException) {
            Log.e(TAG, "WiFi connection failed", e)
            connectionState = ConnectionState.ERROR
            disconnect()
            return@withContext false
        }
    }
    
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            inputStream?.close()
            outputStream?.close()
            
            when (currentConnectionType) {
                ConnectionType.BLUETOOTH -> {
                    bluetoothSocket?.close()
                    bluetoothSocket = null
                }
                ConnectionType.WIFI -> {
                    wifiSocket?.close()
                    wifiSocket = null
                }
                ConnectionType.NONE -> { /* Nothing to disconnect */ }
            }
            
            inputStream = null
            outputStream = null
            currentConnectionType = ConnectionType.NONE
            connectionState = ConnectionState.DISCONNECTED
            
            Log.i(TAG, "Disconnected from Machinna A0")
            
        } catch (e: IOException) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }
    
    suspend fun sendData(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            if (connectionState != ConnectionState.CONNECTED || outputStream == null) {
                Log.e(TAG, "Not connected to Machinna A0")
                return@withContext false
            }
            
            outputStream!!.write(data)
            outputStream!!.flush()
            
            Log.d(TAG, "Sent ${data.size} bytes to Machinna A0")
            return@withContext true
            
        } catch (e: IOException) {
            Log.e(TAG, "Failed to send data to Machinna A0", e)
            connectionState = ConnectionState.ERROR
            return@withContext false
        }
    }
    
    suspend fun receiveData(bufferSize: Int = 1024): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (connectionState != ConnectionState.CONNECTED || inputStream == null) {
                Log.e(TAG, "Not connected to Machinna A0")
                return@withContext null
            }
            
            val buffer = ByteArray(bufferSize)
            val bytesRead = inputStream!!.read(buffer)
            
            if (bytesRead > 0) {
                val data = buffer.copyOf(bytesRead)
                Log.d(TAG, "Received ${data.size} bytes from Machinna A0")
                return@withContext data
            }
            
            return@withContext null
            
        } catch (e: IOException) {
            Log.e(TAG, "Failed to receive data from Machinna A0", e)
            connectionState = ConnectionState.ERROR
            return@withContext null
        }
    }
    
    private fun findMachinnaA0Device(): BluetoothDevice? {
        val bondedDevices = bluetoothAdapter!!.bondedDevices
        
        Log.d(TAG, "Searching for Macchina A0 in ${bondedDevices.size} bonded devices")
        
        // Look for Machinna A0 by name patterns (more comprehensive)
        for (device in bondedDevices) {
            val deviceName = device.name?.lowercase()
            val deviceAddress = device.address
            
            Log.d(TAG, "Checking device: $deviceName ($deviceAddress)")
            
            if (deviceName != null && (
                deviceName.contains("macchina") ||
                deviceName.contains("machinna") || // Keep old misspelling for compatibility
                deviceName.contains("a0") ||
                deviceName.contains("macchina a0") ||
                deviceName.contains("macchina-a0") ||
                deviceName.contains("macchina_a0") ||
                deviceName.contains("obd") ||
                deviceName.contains("elm327") ||
                deviceName.contains("elm") ||
                deviceName.contains("obdii") ||
                deviceName.contains("obd-ii") ||
                deviceName.contains("car") ||
                deviceName.contains("diagnostic") ||
                deviceName.contains("bluetooth") ||
                deviceName.contains("bt") ||
                // Common OBD adapter names
                deviceName.contains("vgate") ||
                deviceName.contains("konnwei") ||
                deviceName.contains("foxwell") ||
                deviceName.contains("autel") ||
                deviceName.contains("launch") ||
                // Generic patterns
                deviceName.contains("obd") ||
                deviceName.contains("can") ||
                deviceName.contains("ecu")
            )) {
                Log.i(TAG, "Found potential Macchina A0 device: ${device.name} ($deviceAddress)")
                return device
            }
        }
        
        // If no specific match found, try to find any device that might be an OBD adapter
        Log.w(TAG, "No specific Macchina A0 device found, checking all devices...")
        for (device in bondedDevices) {
            val deviceName = device.name?.lowercase()
            if (deviceName != null && deviceName.isNotEmpty()) {
                Log.i(TAG, "Available device: ${device.name} ($device.address)")
            }
        }
        
        Log.w(TAG, "No Macchina A0 device found in bonded devices")
        return null
    }
    
    fun getConnectionType(): ConnectionType = currentConnectionType
    fun getConnectionState(): ConnectionState = connectionState
    fun isConnected(): Boolean = connectionState == ConnectionState.CONNECTED
    
    fun getAvailableBluetoothDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }
    
    fun debugListAllDevices(): String {
        val devices = getAvailableBluetoothDevices()
        val sb = StringBuilder()
        sb.append("=== Available Bluetooth Devices ===\n")
        sb.append("Total devices: ${devices.size}\n\n")
        
        devices.forEachIndexed { index, device ->
            sb.append("Device $index:\n")
            sb.append("  Name: ${device.name ?: "Unknown"}\n")
            sb.append("  Address: ${device.address}\n")
            sb.append("  Bond State: ${device.bondState}\n")
            sb.append("  Type: ${device.type}\n")
            sb.append("  UUIDs: ${device.uuids?.joinToString() ?: "None"}\n")
            sb.append("\n")
        }
        
        return sb.toString()
    }
    
    fun isBluetoothAvailable(): Boolean {
        return bluetoothAdapter != null && bluetoothAdapter!!.isEnabled
    }
    
    fun isWiFiAvailable(): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.isWifiEnabled
    }
}
