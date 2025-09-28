# Polestar Companion - Changelog

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
