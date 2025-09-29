# Polestar Companion - Changelog

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
