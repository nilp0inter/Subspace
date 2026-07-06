// sleepwalker-core: pure Kotlin protocol/BLE library.
//
// Owns command frame encoding, CRC insertion, symbolic HID usage mapping,
// BLE UUID constants, MTU-aware write sizing, and status notification
// parsing. No Android framework dependencies at the protocol layer; only
// BLE transport uses Android Bluetooth APIs.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.sleepwalker.core"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        minSdk = 26 // BLE peripheral mode + runtime permissions
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    // BLE transport uses Android Bluetooth APIs only; no extra deps.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}