#include <jni.h>
#include <string>
#include "obd_monitor.h"

// Global OBD monitor instance
static OBDMonitor* obd_monitor = nullptr;

// Global reference to MainActivity for CAN message callbacks
static jobject g_main_activity = nullptr;
static JavaVM* g_jvm = nullptr;

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
        jobject this_obj) {
    
    // Store global reference to MainActivity for CAN message callbacks
    if (g_main_activity == nullptr) {
        g_main_activity = env->NewGlobalRef(this_obj);
        env->GetJavaVM(&g_jvm);
    }
    
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
        json << "\"gear\":\"" << (data.gear != 'U' ? std::string(1, data.gear) : "") << "\",";
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

// CAN message callback for raw capture
void onCANMessage(const CANMessage& message) {
    LOGI("Raw CAN Message - ID: 0x%X, Data: %02X %02X %02X %02X %02X %02X %02X %02X, Length: %d", 
         message.id, 
         message.data[0], message.data[1], message.data[2], message.data[3],
         message.data[4], message.data[5], message.data[6], message.data[7],
         message.length);
    
    // Call Java callback to add message to CANDataFragment
    if (g_main_activity != nullptr && g_jvm != nullptr) {
        LOGI("Calling Java callback for CAN message ID: 0x%X", message.id);
        
        try {
            JNIEnv* env;
            if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) {
                // Create CANMessage object
                jclass canMessageClass = env->FindClass("Polestar/Companion/CANMessage");
                if (canMessageClass != nullptr) {
                    LOGI("Found CANMessage class");
                    // Use the companion object method instead of constructor
                    jmethodID fromNativeMethod = env->GetStaticMethodID(canMessageClass, "fromNative", "(J[BJZZ)LPolestar/Companion/CANMessage;");
                    if (fromNativeMethod != nullptr) {
                        LOGI("Found fromNative method");
                        // Create byte array for data
                        jbyteArray dataArray = env->NewByteArray(message.length);
                        if (dataArray != nullptr) {
                            env->SetByteArrayRegion(dataArray, 0, message.length, (jbyte*)message.data);
                            
                            // Create CANMessage object using companion method
                            jobject canMessageObj = env->CallStaticObjectMethod(canMessageClass, fromNativeMethod,
                                (jlong)message.id,
                                dataArray,
                                (jlong)message.timestamp,
                                (jboolean)message.isExtended,
                                (jboolean)message.isRTR
                            );
                            
                            if (canMessageObj != nullptr) {
                                LOGI("Created CANMessage object successfully");
                                // Call MainActivity.onCANMessageReceived()
                                jclass mainActivityClass = env->GetObjectClass(g_main_activity);
                                if (mainActivityClass != nullptr) {
                                    jmethodID onCANMessageMethod = env->GetMethodID(mainActivityClass, "onCANMessageReceived", "(LPolestar/Companion/CANMessage;)V");
                                    if (onCANMessageMethod != nullptr) {
                                        LOGI("Calling MainActivity.onCANMessageReceived()");
                                        env->CallVoidMethod(g_main_activity, onCANMessageMethod, canMessageObj);
                                        LOGI("Successfully called Java callback");
                                    } else {
                                        LOGE("Could not find onCANMessageReceived method");
                                    }
                                    
                                    // Clean up local references
                                    env->DeleteLocalRef(mainActivityClass);
                                } else {
                                    LOGE("Could not get MainActivity class");
                                }
                            } else {
                                LOGE("Failed to create CANMessage object");
                            }
                            
                            // Clean up local references
                            env->DeleteLocalRef(canMessageObj);
                            env->DeleteLocalRef(dataArray);
                        } else {
                            LOGE("Failed to create byte array");
                        }
                        env->DeleteLocalRef(canMessageClass);
                    } else {
                        LOGE("Could not find fromNative method");
                    }
                } else {
                    LOGE("Could not find CANMessage class");
                }
            } else {
                LOGE("Could not get JNI environment");
            }
        } catch (const std::exception& e) {
            LOGE("Exception in onCANMessage: %s", e.what());
        } catch (...) {
            LOGE("Unknown exception in onCANMessage");
        }
    } else {
        LOGE("g_main_activity or g_jvm is null - cannot call Java callback");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_Polestar_Companion_MainActivity_startRawCANCapture(
        JNIEnv* env,
        jobject /* this */) {
    
    LOGI("=== STARTING RAW CAN CAPTURE ===");
    
    try {
        if (obd_monitor != nullptr) {
            LOGI("OBD Monitor exists, setting CAN message callback");
            obd_monitor->setCANMessageCallback(onCANMessage);
            
            LOGI("Starting raw CAN capture in OBD Monitor");
            obd_monitor->startRawCANCapture();
            
            LOGI("Raw CAN capture started successfully");
        } else {
            LOGE("OBD Monitor is NULL - cannot start raw CAN capture");
        }
    } catch (const std::exception& e) {
        LOGE("Exception in startRawCANCapture: %s", e.what());
    } catch (...) {
        LOGE("Unknown exception in startRawCANCapture");
    }
    
    LOGI("=== RAW CAN CAPTURE SETUP COMPLETE ===");
}

extern "C" JNIEXPORT void JNICALL
Java_Polestar_Companion_MainActivity_stopRawCANCapture(
        JNIEnv* env,
        jobject /* this */) {
    LOGI("=== STOPPING RAW CAN CAPTURE ===");
    
    try {
        if (obd_monitor != nullptr) {
            LOGI("OBD Monitor exists, stopping raw CAN capture");
            obd_monitor->stopRawCANCapture();
            LOGI("Raw CAN capture stopped successfully");
        } else {
            LOGE("OBD Monitor is NULL - cannot stop raw CAN capture");
        }
    } catch (const std::exception& e) {
        LOGE("Exception in stopRawCANCapture: %s", e.what());
    } catch (...) {
        LOGE("Unknown exception in stopRawCANCapture");
    }
    
    LOGI("=== RAW CAN CAPTURE STOP COMPLETE ===");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_Polestar_Companion_MainActivity_isCANInterfaceReady(
        JNIEnv* env,
        jobject /* this */) {
    LOGI("=== CHECKING CAN INTERFACE STATUS ===");
    
    try {
        if (obd_monitor != nullptr) {
            bool isReady = obd_monitor->isCANInterfaceReady();
            LOGI("CAN interface ready: %s", isReady ? "true" : "false");
            return (jboolean)isReady;
        } else {
            LOGE("OBD Monitor is NULL - returning false");
            return JNI_FALSE;
        }
    } catch (const std::exception& e) {
        LOGE("Exception in isCANInterfaceReady: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("Unknown exception in isCANInterfaceReady");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_Polestar_Companion_MainActivity_isRawCANCaptureActive(
        JNIEnv* env,
        jobject /* this */) {
    LOGI("=== CHECKING RAW CAN CAPTURE STATUS ===");
    
    try {
        if (obd_monitor != nullptr) {
            bool isActive = obd_monitor->isRawCANCaptureActive();
            LOGI("Raw CAN capture active: %s", isActive ? "true" : "false");
            return (jboolean)isActive;
        } else {
            LOGE("OBD Monitor is NULL - returning false");
            return JNI_FALSE;
        }
    } catch (const std::exception& e) {
        LOGE("Exception in isRawCANCaptureActive: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("Unknown exception in isRawCANCaptureActive");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_Polestar_Companion_MainActivity_updateVehicleDataNative(
        JNIEnv* env,
        jobject /* this */,
        jstring field,
        jstring value) {
    
    if (obd_monitor == nullptr) {
        LOGE("OBD monitor is null in updateVehicleDataNative");
        return;
    }
    
    try {
        const char* fieldStr = env->GetStringUTFChars(field, nullptr);
        const char* valueStr = env->GetStringUTFChars(value, nullptr);
        
        if (fieldStr != nullptr && valueStr != nullptr) {
            LOGI("Updating vehicle data: %s = %s", fieldStr, valueStr);
            obd_monitor->updateData(std::string(fieldStr), std::string(valueStr));
        }
        
        env->ReleaseStringUTFChars(field, fieldStr);
        env->ReleaseStringUTFChars(value, valueStr);
    } catch (const std::exception& e) {
        LOGE("Exception in updateVehicleDataNative: %s", e.what());
    } catch (...) {
        LOGE("Unknown exception in updateVehicleDataNative");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_Polestar_Companion_MainActivity_forwardCANMessageFromGVRET(
        JNIEnv* env,
        jobject /* this */,
        jlong id,
        jbyteArray data,
        jlong timestamp,
        jboolean isExtended,
        jboolean isRTR) {
    
    LOGI("=== FORWARDING CAN MESSAGE FROM GVRET TO NATIVE INTERFACE ===");
    
    try {
        if (obd_monitor != nullptr) {
            // Create CANMessage object
            CANMessage message;
            message.id = static_cast<uint32_t>(id);
            message.timestamp = timestamp;
            message.isExtended = isExtended;
            message.isRTR = isRTR;
            
            // Copy data from Java byte array
            jsize dataLength = env->GetArrayLength(data);
            message.length = static_cast<uint8_t>(std::min(dataLength, 8)); // CAN max 8 bytes
            
            jbyte* dataBytes = env->GetByteArrayElements(data, nullptr);
            if (dataBytes != nullptr) {
                for (int i = 0; i < message.length; i++) {
                    message.data[i] = static_cast<uint8_t>(dataBytes[i] & 0xFF);
                }
                // Clear remaining bytes
                for (int i = message.length; i < 8; i++) {
                    message.data[i] = 0;
                }
                env->ReleaseByteArrayElements(data, dataBytes, JNI_ABORT);
                
                LOGI("Forwarding CAN message - ID: 0x%X, Data: %02X %02X %02X %02X %02X %02X %02X %02X, Length: %d", 
                     message.id, 
                     message.data[0], message.data[1], message.data[2], message.data[3],
                     message.data[4], message.data[5], message.data[6], message.data[7],
                     message.length);
                
                // Add message to CAN interface buffer
                obd_monitor->getCANInterface().addMessageFromGVRET(message);
                
                LOGI("Successfully forwarded CAN message to native interface");
            } else {
                LOGE("Failed to get byte array elements");
            }
        } else {
            LOGE("OBD Monitor is NULL - cannot forward CAN message");
        }
    } catch (const std::exception& e) {
        LOGE("Exception in forwardCANMessageFromGVRET: %s", e.what());
    } catch (...) {
        LOGE("Unknown exception in forwardCANMessageFromGVRET");
    }
    
    LOGI("=== CAN MESSAGE FORWARDING COMPLETE ===");
}