#include <jni.h>
#include <string>
#include "obd_monitor.h"

// Global OBD monitor instance
static OBDMonitor* obd_monitor = nullptr;

// Data update callback
void onDataUpdate(const VehicleData& data) {
    // This will be called when vehicle data is updated
    LOGI("Vehicle data updated - SOC: %d%%, Voltage: %.2fV, Ambient: %d°C", 
         data.soc, data.voltage, data.ambient);
}

extern "C" JNIEXPORT jstring JNICALL
Java_Polestar_Companion_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Polestar Companion - OBD Monitor Ready";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_Polestar_Companion_MainActivity_initializeOBDMonitor(
        JNIEnv* env,
        jobject /* this */) {
    
    if (obd_monitor == nullptr) {
        obd_monitor = new OBDMonitor();
        if (obd_monitor->initialize()) {
            obd_monitor->setDataUpdateCallback(onDataUpdate);
            return JNI_TRUE;
        } else {
            delete obd_monitor;
            obd_monitor = nullptr;
            return JNI_FALSE;
        }
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_Polestar_Companion_MainActivity_startOBDMonitoring(
        JNIEnv* env,
        jobject /* this */) {
    
    if (obd_monitor != nullptr) {
        return obd_monitor->startMonitoring() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_Polestar_Companion_MainActivity_stopOBDMonitoring(
        JNIEnv* env,
        jobject /* this */) {
    
    if (obd_monitor != nullptr) {
        obd_monitor->stopMonitoring();
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_Polestar_Companion_MainActivity_getVehicleData(
        JNIEnv* env,
        jobject /* this */) {
    
    if (obd_monitor != nullptr) {
        VehicleDataCopy data = obd_monitor->getVehicleDataCopy();
        
        std::stringstream json;
        json << "{";
        json << "\"vin\":\"" << data.vin << "\",";
        json << "\"soc\":" << data.soc << ",";
        json << "\"voltage\":" << data.voltage << ",";
        json << "\"ambient\":" << data.ambient << ",";
        json << "\"speed\":" << data.speed << ",";
        json << "\"odometer\":" << data.odometer << ",";
        json << "\"gear\":\"" << data.gear << "\",";
        json << "\"rssi\":" << data.rssi << ",";
        json << "\"soh\":" << data.soh;
        json << "}";
        
        return env->NewStringUTF(json.str().c_str());
    }
    
    return env->NewStringUTF("{}");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_Polestar_Companion_MainActivity_isMonitoringActive(
        JNIEnv* env,
        jobject /* this */) {
    
    if (obd_monitor != nullptr) {
        return obd_monitor->isMonitoring() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_Polestar_Companion_MainActivity_getConnectionStatus(
        JNIEnv* env,
        jobject /* this */) {
    
    if (obd_monitor != nullptr) {
        std::string status = obd_monitor->getConnectionStatus();
        return env->NewStringUTF(status.c_str());
    }
    
    return env->NewStringUTF("Not Initialized");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_Polestar_Companion_MainActivity_isConnected(
        JNIEnv* env,
        jobject /* this */) {
    
    if (obd_monitor != nullptr) {
        return obd_monitor->isConnected() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_Polestar_Companion_MainActivity_requestSOH(
        JNIEnv* env,
        jobject /* this */) {
    
    if (obd_monitor != nullptr) {
        obd_monitor->requestSOH();
    }
}