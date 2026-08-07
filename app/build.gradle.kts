plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nosmai.livekit.example"
    compileSdk = 35

    // 28.2.x is a broken install on some machines (missing source.properties,
    // fails with CXX1101). 29.0.14206865 is what the Nosmai SDK is built with.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        // Nosmai licence keys are bound to an application id — set this to the
        // id your key was issued for.
        applicationId = "com.your.app"
        // LiveKit's WebRTC requires 24+.
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // The Nosmai SDK only ships arm64. Without this filter the build pulls
        // in other ABIs that have no native library and fails at runtime.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    // Nosmai — camera, filters, AR. Bundled as a local .aar.
    implementation(files("libs/nosmai-release.aar"))

    // LiveKit. Brings its own WebRTC build (io.livekit:livekit-android-webrtc),
    // which is what supplies org.webrtc.* here.
    implementation("io.livekit:livekit-android:2.9.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
