/*
 * OBD Monitor for Android - Header file
 * Adapted from ESP32 code for Polestar 2 monitoring
 */

#ifndef OBD_MONITOR_H
#define OBD_MONITOR_H

#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <mutex>
#include <atomic>
#include <queue>
#include <android/log.h>

#define LOG_TAG "OBDMonitor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// OBD-II PIDs
#define PID_VEHICLE_SPEED 0x0D
#define PID_CONTROL_MODULE_VOLTAGE 0x42
#define PID_AMBIENT_AIR_TEMPERATURE 0x46
#define PID_BATTERY_PACK_SOC 0x5B
#define PID_VIN 0x02

// CAN Modes
#define CAN_MODE_CURRENT 0x01
#define CAN_MODE_INFORMATION 0x09
#define CAN_MODE_CUSTOM 0x22

// CAN IDs
#define LONG_SEND_ID 0x18DB33F1
#define LONG_RECV_ID 0x18DAF100
#define LONG_RECV_MASK 0x1FFFFF00
#define SHORT_SEND_ID 0x7DF
#define SHORT_RECV_ID 0x7E8
#define SHORT_RECV_MASK 0x7F8
#define LONGBC_RECV_ID 0x1FFF0000
#define LONGBC_RECV_MASK 0x1FFFF000
#define ODOMETER_ID 0x1FFF0120 
#define GEAR_ID 0x1FFF00A0

// UDS CAN IDs for SOH (State of Health)
#define BECM_SEND_ID 0x1DD01635  // BECM (Battery Energy Control Module) address
#define BECM_RECV_ID 0x1EC6AE80  // Tester address for responses
#define UDS_REQUEST_DID 0x496D    // DID for SOH reading

// Number of PIDs to monitor
#define NUM_PIDS 5

// Structure to hold vehicle data (internal use with atomic members)
struct VehicleData {
    std::string vin;
    int soc = -1;           // State of Charge (%)
    float voltage = -1.0f;  // 12V battery voltage
    int ambient = -100;     // Ambient temperature (°C)
    int speed = -1;         // Vehicle speed (km/h)
    int odometer = -1;      // Odometer reading (km)
    char gear = 'U';        // Gear position (P/R/N/D)
    int rssi = -1;          // WiFi signal strength
    float soh = -1.0f;      // State of Health (%)
    
    std::atomic<bool> dirty{false};
    std::mutex data_mutex;
    
    // Default constructor
    VehicleData() = default;
};

// Structure for safe copying (no atomic members)
struct VehicleDataCopy {
    std::string vin;
    int soc = -1;           // State of Charge (%)
    float voltage = -1.0f;  // 12V battery voltage
    int ambient = -100;     // Ambient temperature (°C)
    int speed = -1;         // Vehicle speed (km/h)
    int odometer = -1;      // Odometer reading (km)
    char gear = 'U';        // Gear position (P/R/N/D)
    int rssi = -1;          // WiFi signal strength
    float soh = -1.0f;      // State of Health (%)
    
    // Default constructor
    VehicleDataCopy() = default;
};

// Structure for PID requests
struct PIDRequest {
    uint8_t mode;
    uint16_t pid;
};

// CAN Message structure
struct CANMessage {
    uint32_t id;           // CAN ID (11-bit or 29-bit)
    uint8_t data[8];       // Up to 8 bytes of data
    uint8_t length;        // Data length (0-8)
    uint64_t timestamp;    // Message timestamp
    bool isExtended;       // 29-bit ID flag
    bool isRTR;           // Remote Transmission Request flag
    
    CANMessage() : id(0), length(0), timestamp(0), isExtended(false), isRTR(false) {
        for (int i = 0; i < 8; i++) data[i] = 0;
    }
    
    CANMessage(uint32_t id, const uint8_t* data, uint8_t length, uint64_t timestamp = 0, bool extended = false, bool rtr = false)
        : id(id), length(length), timestamp(timestamp), isExtended(extended), isRTR(rtr) {
        for (int i = 0; i < 8; i++) {
            this->data[i] = (i < length) ? data[i] : 0;
        }
    }
};

// Callback function type for data updates
typedef void (*DataUpdateCallback)(const VehicleData& data);

// Callback function type for raw CAN messages
typedef void (*CANMessageCallback)(const CANMessage& message);

// CAN communication interface for Machinna A0 OBD reader
class CANInterface {
public:
    CANInterface();
    ~CANInterface();
    
    // Initialize CAN interface
    bool initialize();
    
    // Send CAN message
    bool sendMessage(uint32_t id, const uint8_t* data, uint8_t length, bool isExtended = false);
    
    // Receive CAN message (blocking)
    bool receiveMessage(CANMessage& message, int timeout_ms = 1000);
    
    // Add message from GVRET to buffer
    void addMessageFromGVRET(const CANMessage& message);
    
    // Check if interface is ready
    bool isReady() const { return ready; }
    
    // Close interface
    void close();

private:
    bool ready;
    
    // Message buffer for GVRET messages (optimized size)
    static constexpr size_t MAX_BUFFER_SIZE = 32; // Limit buffer size for memory efficiency
    std::queue<CANMessage> message_buffer;
    mutable std::mutex message_buffer_mutex;
    
    // Macchina A0 specific configuration
    void configureMachinnaA0();
};

class OBDMonitor {
public:
    OBDMonitor();
    ~OBDMonitor();
    
    // Initialize the monitor
    bool initialize();
    
    // Start monitoring
    bool startMonitoring();
    
    // Stop monitoring
    void stopMonitoring();
    
    // Set callback for data updates
    void setDataUpdateCallback(DataUpdateCallback callback);
    
    // Set callback for raw CAN messages
    void setCANMessageCallback(CANMessageCallback callback);
    
    // Get vehicle data copy without atomic/mutex members
    VehicleDataCopy getVehicleDataCopy();
    
    // Raw CAN data capture control
    void startRawCANCapture();
    void stopRawCANCapture();
    bool isRawCANCaptureActive() const { return raw_can_capture_active.load(); }
    bool isCANInterfaceReady() const { return can_interface.isReady(); }
    
    // Send data to MQTT (if configured)
    void sendToMQTT();
    
    // Check if monitoring is active
    bool isMonitoring() const { return monitoring_active.load(); }
    
    // Manual SOH request
    void requestSOH();
    
    // Connection management
    bool connectWithRetry(int max_retries = 5, int retry_delay_ms = 5000);
    bool isConnected() const { return connected.load(); }
    std::string getConnectionStatus() const { return connection_status; }
    
    // Update vehicle data (public method for external updates)
    void updateData(const std::string& field, const std::string& value);
    
    // Get CAN interface reference for message forwarding
    CANInterface& getCANInterface() { return can_interface; }

private:
    // Monitor thread function
    void monitorLoop();
    
    // Send CAN requests
    void sendCANRequests();
    
    // Send UDS request for SOH
    void sendSOHRequest();
    
    // Request real SOH from BECM
    bool requestRealSOHFromBECM(float& soh_value);
    
    // Process received CAN frames
    void processCANFrame(const uint8_t* data, size_t length, uint32_t id);
    
    // Decode Polestar 2 CAN frames using community-mapped signals
    void decodePolestarCANFrame(const CANMessage& message);
    
    // Parse OBD-II responses
    void parseOBDResponse(uint8_t mode, uint8_t pid, const uint8_t* data, size_t length);
    
    // Parse UDS responses (for SOH)
    void parseUDSResponse(const uint8_t* data, size_t length);
    
    // Parse broadcast frames (Polestar specific)
    void parseBroadcastFrame(uint32_t id, const uint8_t* data, size_t length);
    
    // MQTT functions
    bool connectToMQTT();
    void publishToMQTT(const std::string& topic, const std::string& message);
    
    // Connection management
    bool attemptConnection();
    void updateConnectionStatus(const std::string& status);
    
    VehicleData vehicle_data;
    DataUpdateCallback data_callback;
    CANMessageCallback can_message_callback;
    
    std::atomic<bool> monitoring_active{false};
    std::atomic<bool> connected{false};
    std::atomic<bool> raw_can_capture_active{false};
    std::thread monitor_thread;
    
    // Connection status tracking
    mutable std::mutex status_mutex;
    std::string connection_status;
    
    // PID request array
    PIDRequest pids[NUM_PIDS];
    
    // Timing
    std::chrono::steady_clock::time_point last_request_time;
    std::chrono::steady_clock::time_point last_data_time;
    
    // MQTT configuration
    bool mqtt_enabled;
    std::string mqtt_server;
    std::string mqtt_port;
    std::string mqtt_topics[7]; // soc, voltage, ambient, odo, gear, vin, rssi
    
    // CAN interface for real communication
    CANInterface can_interface;
};

// Global instance for JNI access
extern OBDMonitor* g_obd_monitor;

#endif // OBD_MONITOR_H
