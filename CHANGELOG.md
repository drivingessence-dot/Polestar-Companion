# Polestar Companion - Changelog

## Version 0.8.6 - CAN Data Capture & Protocol Improvements
**Release Date:** December 2024

### 🚀 New Features
- **Native CAN Data Capture** - Page 3 now successfully captures raw CAN messages from Polestar 2
- **Real-time CAN Message Parsing** - Live parsing of vehicle speed, battery SOC, voltage, and temperature
- **Enhanced Macchina A0 Firmware** - Updated firmware with WS2812B LED support and improved JSON handling
- **Serial Connection LED Indication** - Visual feedback for USB serial connections

### 🔧 Technical Improvements
- **Fixed JSON Mode Communication** - Resolved "Unknown GVRET cmd" warnings by properly implementing JSON protocol
- **Enhanced GvretClient** - Added fallback mechanisms and timeout handling for robust communication
- **Improved CAN Message Handling** - Better parsing of OBD-II PIDs and diagnostic responses
- **Updated Firmware LED Logic** - Corrected LED pin assignments and implemented FastLED library support

### 📊 CAN Data Analysis
#### Successfully Captured Data
- **Battery SOC:** 66.7% (PID 0x5B)
- **Vehicle Speed:** 0 km/h (PID 0x0D) - stationary
- **Ambient Temperature:** 13°C (PID 0x46)
- **System Voltage:** 13.7V (PID 0x42)
- **VIN Information:** Partial capture (PID 0x02)

#### CAN Message Format
All messages captured from CAN ID `18DAF110` (ECU diagnostic responses)
- Real-time OBD-II PID responses
- Proper extended frame handling
- Timestamp accuracy for data logging

### 🐛 Bug Fixes
- **Fixed "Unknown GVRET cmd" warnings** - Proper JSON mode initialization and command handling
- **Resolved SocketTimeoutException** - Added timeout handling and fallback to binary mode
- **Fixed LED compilation errors** - Corrected ESP32CAN library method calls
- **Improved connection stability** - Better error handling and reconnection logic

### 🔌 Hardware Improvements
- **WS2812B LED Support** - Proper FastLED library integration for Macchina A0
- **Serial Activity Indication** - LED feedback for USB serial connections
- **Client Timeout Handling** - Automatic disconnection of stale TCP clients
- **Enhanced Debug Mode** - Improved serial command handling and status reporting

### 📱 App Integration
- **Page 3 Native CAN Interface** - Fully functional raw CAN data capture
- **Real-time Data Display** - Live updating of vehicle parameters
- **Export Functionality** - CAN data export in CSV format
- **Connection Status Monitoring** - Visual feedback for Macchina A0 connectivity

### 🔄 Protocol Support
- **JSON Mode (Port 35000)** - Parsed and raw CAN messages
- **GVRET Mode (Port 23)** - Traditional binary protocol (fallback)
- **OBD-II PIDs** - Standard diagnostic parameter requests
- **UDS Services** - Unified Diagnostic Services for advanced diagnostics

---

## Version 0.8.2 - Enhanced Macchina A0 Client & JSON Support
**Release Date:** December 2024

### 🚀 New Features
- **Enhanced Macchina A0 Client** with dual protocol support
- **JSON Mode Communication** - Connect to port 35000 for parsed JSON and raw CAN frames
- **GVRET Mode Communication** - Traditional binary protocol on port 23
- **Real-time Control Commands** - Enable/disable streaming, set filters, request status
- **Smart Message Routing** - Automatic handling of parsed vs raw CAN messages

### 🔧 Technical Improvements
- **Added Gson dependency** for JSON parsing
- **Enhanced GvretClient class** with JSON mode support
- **Dual mode connection** - JSON (port 35000) and GVRET (port 23)
- **Improved CAN message handling** with JSON format support
- **Better error handling** and user feedback

### 📡 New Control Methods
#### Connection Methods
- `connectToMacchinaJSONMode()` - Connect in JSON mode
- `connectToMacchinaGVRETMode()` - Connect in GVRET mode

#### Streaming Control
- `enableRawCANStreaming()` - Enable raw CAN data streaming
- `disableRawCANStreaming()` - Disable raw CAN data streaming

#### Filter Management
- `setCANFilter(ids)` - Set custom CAN ID filter
- `setOBDIIFilter()` - Set common OBD-II filter
- `clearCANFilter()` - Clear filter (allow all messages)

#### Status & Diagnostics
- `requestMacchinaStatus()` - Request status from Macchina A0

### 🐛 Bug Fixes
- **Fixed compilation errors** in GvretClient class
- **Resolved property reference issues** (inputStream → input, outputStream → output)
- **Fixed coroutine scope issues** in JSON command sending
- **Corrected connection status checks** using socket.isConnected

### 📋 JSON Message Support
#### Parsed Messages
```json
{
  "type": "parsed",
  "VIN": "YV1LFA0AC9A123456",
  "SoC": 85,
  "Voltage": 12.6,
  "Ambient": 22,
  "ODO": 12345,
  "Gear": "D",
  "Speed": 65
}
```

#### Raw CAN Messages
```json
{
  "type": "raw",
  "id": "0x7E8",
  "ext": false,
  "rtr": false,
  "len": 8,
  "data": [65, 13, 255, 255, 255, 255, 255, 255],
  "ts": 1234567890
}
```

### 🔄 Backward Compatibility
- **All existing GVRET functionality preserved**
- **Existing MainActivity integration unchanged**
- **Traditional GVRET protocol still supported**
- **No breaking changes to existing code**

---

## Version 0.8.1 - Code Consolidation & Bug Fixes
**Release Date:** December 2024

### 🐛 Bug Fixes
- **Fixed compilation errors** caused by duplicate function definitions
- **Resolved overload resolution ambiguity** for `requestAllEssentialPIDs()` and `requestVehicleSOH()`
- **Eliminated conflicting overloads** that prevented successful compilation

### 🔧 Code Improvements
- **Consolidated duplicate functions** across the codebase
- **Created NetworkUtils.kt** - Centralized utility class for network operations
- **Removed ~200+ lines** of duplicate code
- **Improved code maintainability** with single source of truth for each function

### 📦 Function Consolidations
#### Parsing Functions
- `parseVehicleSpeed()` - Consolidated 2 versions into 1
- `parseBatterySOC()` - Consolidated 2 versions into 1

#### Network Functions
- `getPhoneNetworkInfo()` - Consolidated 3 duplicates into 1 centralized version
- `intToIp()` - Consolidated 3 duplicates into 1 centralized version
- `getCurrentWiFiSSID()` - Consolidated 2 duplicates into 1 centralized version
- `scanForMacchinaA0()` - Consolidated 3 duplicates into 1 centralized version
- `scanLocalNetwork()` - Consolidated 2 duplicates into 1 centralized version
- `testBasicConnectivity()` - Consolidated 2 duplicates into 1 centralized version

#### GVRET Functions
- `requestPID()`, `requestVIN()`, `requestSOH()` - Identified and prepared for consolidation
- `startPeriodicPIDPolling()`, `stopPeriodicPIDPolling()` - Identified and prepared for consolidation

### 🎯 Benefits
- ✅ **Eliminated compilation conflicts** - No more overload resolution ambiguity
- ✅ **Improved maintainability** - Single source of truth for each function
- ✅ **Reduced code duplication** - Cleaner, more organized codebase
- ✅ **Better organization** - Network functions centralized in NetworkUtils
- ✅ **Consistent behavior** - All components use the same implementations

### 📁 Files Modified
- `app/build.gradle.kts` - Updated version to 0.8.1
- `app/src/main/java/Polestar/Companion/MainActivity.kt` - Removed duplicate functions
- `app/src/main/java/Polestar/Companion/NetworkUtils.kt` - **NEW** centralized network utility class
- All function calls updated to use consolidated versions

### 🔄 Technical Details
- **Version Code:** 8
- **Version Name:** 0.8.1
- **Target SDK:** 36
- **Min SDK:** 24
- **Compilation:** ✅ No linter errors
- **Build Status:** Ready for compilation

---

## Previous Versions

### Version 1.0 - Initial Release
- Initial Polestar Companion app release
- Basic CAN message monitoring
- SOH tracking functionality
- GVRET protocol support
