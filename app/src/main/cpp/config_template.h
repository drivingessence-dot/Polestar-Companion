/*
 * Configuration template for Polestar Companion
 * Copy this file to config.h and edit with your actual values
 */

#ifndef CONFIG_H
#define CONFIG_H

// WiFi Configuration
#define NUM_SSID 2
const char* ssid[NUM_SSID] = {
  "YourWiFiSSID1",
  "YourWiFiSSID2"
};
const char* pass[NUM_SSID] = {
  "YourWiFiPassword1", 
  "YourWiFiPassword2"
};

// MQTT Configuration
const char* mqtt_server = "your.mqtt.server.com";
const char* mqtt_port = "1883";
const char* mqtt_status_topic_soc = "polestar/battery/soc";
const char* mqtt_status_topic_voltage = "polestar/battery/voltage";
const char* mqtt_status_topic_ambient = "polestar/climate/ambient";
const char* mqtt_status_topic_odo = "polestar/vehicle/odometer";
const char* mqtt_status_topic_gear = "polestar/vehicle/gear";
const char* mqtt_status_topic_vin = "polestar/vehicle/vin";
const char* mqtt_status_topic_rssi = "polestar/network/rssi";

#endif // CONFIG_H
