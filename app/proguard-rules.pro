# Polestar Companion - ProGuard optimization rules

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep CAN message classes for serialization
-keep class Polestar.Companion.CANMessage { *; }
-keep class Polestar.Companion.CanFrame { *; }

# Keep JNI methods
-keepclassmembers class Polestar.Companion.MainActivity {
    native <methods>;
}

# Optimize performance
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-dontpreverify

# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# Keep line numbers for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin specific optimizations
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**

# Keep coroutines
-keep class kotlinx.coroutines.** { *; }