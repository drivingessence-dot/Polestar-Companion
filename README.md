# Polestar Companion

An Android application that connects to your 2021 Polestar 2 and monitors various vehicle parameters through OBD-II communication. This app is adapted from ESP32 code originally designed for MACCHINA A0 OBD-II adapters.

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/platform-Android-green.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=24)
[![Kotlin](https://img.shields.io/badge/language-Kotlin-blue.svg)](https://kotlinlang.org/)
[![C++](https://img.shields.io/badge/language-C%2B%2B-red.svg)](https://isocpp.org/)

## App Icon

The app features a modern, poppy icon with Scandinavian design elements:

- **Vibrant Colors**: Poppy orange (#FF6B35) background with electric green battery indicator
- **Battery Symbol**: Clean, minimalist battery design representing EV monitoring
- **Scandinavian Elements**: Nordic cross patterns, geometric accents, and clean lines
- **EV Lightning**: Golden lightning bolt symbolizing electric vehicle technology
- **Adaptive Design**: Supports Android adaptive icons with monochrome theming

## Features

The app monitors the following parameters from your Polestar 2:

- **VIN (Vehicle Identification Number)** - Unique vehicle identifier
- **Battery State of Charge (SOC)** - High voltage battery percentage
- **12V Battery Voltage** - Auxiliary battery voltage
- **Ambient Air Temperature** - External temperature reading
- **Vehicle Speed** - Current speed in km/h
- **Odometer Reading** - Total distance traveled
- **Gear Position** - Current gear (P/R/N/D)
- **WiFi Signal Strength** - Network connectivity quality

## Architecture

The app consists of:

- **Native C++ Library**: Handles OBD-II communication and data parsing
- **Android UI**: Displays vehicle data in real-time with Material Design cards
- **MQTT Integration**: Sends data to MQTT server (configurable)
- **Threaded Monitoring**: Background monitoring with configurable update intervals

## Setup Instructions

### 1. Configuration

1. Copy `app/src/main/cpp/config_template.h` to `app/src/main/cpp/config.h`
2. Edit `config.h` with your WiFi and MQTT settings:

```cpp
// WiFi Configuration
const char* ssid[2] = {"YourWiFiSSID1", "YourWiFiSSID2"};
const char* pass[2] = {"YourWiFiPassword1", "YourWiFiPassword2"};

// MQTT Configuration
const char* mqtt_server = "your.mqtt.server.com";
const char* mqtt_port = "1883";
```

### 2. Build Requirements

- Android Studio Arctic Fox or later
- Android NDK (Native Development Kit)
- Android SDK API level 24+ (Android 7.0)
- Target SDK API level 36

### 3. Permissions

The app requires the following permissions (already configured in AndroidManifest.xml):

- `INTERNET` - For MQTT communication
- `ACCESS_NETWORK_STATE` - Network status monitoring
- `ACCESS_WIFI_STATE` - WiFi connectivity
- `BLUETOOTH*` - For Bluetooth OBD adapters
- `ACCESS_COARSE_LOCATION` - Required for Bluetooth scanning

### 4. OBD-II Connection

The app supports various OBD-II connection methods:

- **USB OBD-II Adapter**: Direct USB connection to Android device
- **Bluetooth OBD-II Adapter**: Wireless Bluetooth connection
- **WiFi OBD-II Adapter**: Wireless WiFi connection

## Usage

1. **Launch the App**: Start the Polestar Companion app
2. **Initialize**: The app automatically initializes the OBD monitor
3. **Start Monitoring**: Tap "Start Monitoring" to begin data collection
4. **View Data**: Vehicle data is displayed in organized cards:
   - Vehicle Information (VIN, Odometer, Speed, Gear)
   - Battery Information (SOC, 12V Voltage)
   - Climate Information (Ambient Temperature)
   - Network Information (Signal Strength)
5. **Stop Monitoring**: Tap "Stop Monitoring" to halt data collection

## Technical Details

### OBD-II PIDs Monitored

| PID | Description | Mode |
|-----|-------------|------|
| 0x02 | VIN | 0x09 (Information) |
| 0x42 | Control Module Voltage | 0x01 (Current) |
| 0x46 | Ambient Air Temperature | 0x01 (Current) |
| 0x5B | Battery Pack SOC | 0x01 (Current) |
| 0x0D | Vehicle Speed | 0x01 (Current) |

### Polestar-Specific Broadcast Messages

- **Odometer**: ID `0x1FFF0120`
- **Gear Position**: ID `0x1FFF00A0`

### CAN Bus Configuration

- **Baud Rate**: 500 kbps
- **Frame Format**: Both 11-bit and 29-bit IDs supported
- **Update Interval**: 2 seconds per PID

## MQTT Integration

When configured, the app publishes data to MQTT topics:

- `polestar/battery/soc` - Battery state of charge
- `polestar/battery/voltage` - 12V battery voltage
- `polestar/climate/ambient` - Ambient temperature
- `polestar/vehicle/odometer` - Odometer reading
- `polestar/vehicle/gear` - Gear position
- `polestar/vehicle/vin` - Vehicle VIN
- `polestar/network/rssi` - WiFi signal strength

## Development

### Project Structure

```
app/src/main/
├── cpp/
│   ├── native-lib.cpp          # JNI interface
│   ├── obd_monitor.h          # OBD monitor header
│   ├── obd_monitor.cpp        # OBD monitor implementation
│   ├── config_template.h      # Configuration template
│   └── CMakeLists.txt         # Native build configuration
├── java/Polestar/Companion/
│   └── MainActivity.kt        # Main Android activity
└── res/layout/
    └── activity_main.xml      # UI layout
```

### Building

```bash
# Clone the repository
git clone <repository-url>
cd PolestarCompanion

# Open in Android Studio or build from command line
./gradlew assembleDebug
```

### Testing

The app includes simulation mode for testing without a physical vehicle connection. Test data is generated automatically when no OBD-II adapter is detected.

## Troubleshooting

### Common Issues

1. **"Failed to initialize OBD Monitor"**
   - Check if OBD-II adapter is connected
   - Verify USB/Bluetooth permissions are granted
   - Ensure adapter is compatible with Polestar 2

2. **No Data Received**
   - Verify vehicle is running or in accessory mode
   - Check OBD-II adapter connection
   - Ensure adapter is properly configured for CAN bus

3. **MQTT Connection Failed**
   - Verify WiFi connection
   - Check MQTT server configuration in `config.h`
   - Ensure MQTT server is accessible

### Debug Logging

Enable debug logging by viewing Android logs:

```bash
adb logcat | grep "OBDMonitor\|MainActivity"
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Test thoroughly with a Polestar 2
5. Submit a pull request

## License

This project is open source. Please check the license file for details.

## Disclaimer

This app is for educational and personal use only. Use at your own risk. The authors are not responsible for any damage to your vehicle or device.

## Acknowledgments

- Original ESP32 code by the MACCHINA A0 community
- Polestar 2 OBD-II reverse engineering community
- Android NDK and JNI documentation
