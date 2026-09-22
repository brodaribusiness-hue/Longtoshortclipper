plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.shortsclipper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shortsclipper"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            // arm64-v8a for modern phones, armeabi-v7a for still-common
            // 32-bit devices, and x86_64 for emulator testing.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/native/CMakeLists.txt")
            version = "3.22.1"
        }
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
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Playback
    implementation("androidx.media3:media3-exoplayer:1.4.0")
    implementation("androidx.media3:media3-ui:1.4.0")
    // Export (Transformer) + effects (crop/pan/zoom)
    implementation("androidx.media3:media3-transformer:1.4.0")
    implementation("androidx.media3:media3-effect:1.4.0")
    implementation("androidx.media3:media3-common:1.4.0")

    // On-device face detection (bundled, no Google Play services required, no cloud)
    implementation("com.google.mlkit:face-detection:16.1.6")

    // Local transcription: whisper.cpp via JNI (native sources under src/main/native)
    // Model files are managed at runtime by ModelManager (official whisper.cpp ggml models).

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // Emulator smoke coverage: launch and inspect the Compose UI plus load the
    // packaged x86_64 native library, without relying on cloud services.
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
