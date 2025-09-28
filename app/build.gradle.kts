plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "Polestar.Companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "Polestar.Companion"
        minSdk = 24
        targetSdk = 36
        versionCode = 8
        versionName = "0.8.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Pixel 8 Pro optimizations with advanced ARM64 features and 16KB support
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        
        // Advanced performance optimizations
        multiDexEnabled = true
        vectorDrawables.useSupportLibrary = true
        
        // Enable R8 optimizations
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }

    buildTypes {
        debug {
            // Optimize debug builds for Pixel 8 Pro
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            
            // Debug-specific optimizations
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            
            // Advanced release optimizations
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            // Pixel 8 Pro release optimizations
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        // Advanced Kotlin performance optimizations for Pixel 8 Pro
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlin.ExperimentalStdlibApi",
            "-opt-in=kotlin.time.ExperimentalTime",
            "-Xjvm-default=all",                    // Enable JVM default methods
            "-Xbackend-threads=0"                    // Use all available CPU cores
        )
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Core Android libraries with performance optimizations
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    
    // Modern Android features for Pixel 8 Pro
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    
    // ViewPager2 for swipe pages
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    
    // Graph plotting library
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    
    // Performance and memory optimizations
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // JSON processing with performance optimizations
    implementation(libs.moshi)
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    
    // Advanced coroutines for better performance
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    
    // Modern Android APIs
    implementation("androidx.window:window:1.2.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    
    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}