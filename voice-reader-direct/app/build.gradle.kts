plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "au.com.roningroup.voicereader.direct"
    compileSdk = 35

    defaultConfig {
        applicationId = "au.com.roningroup.voicereader.direct"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.2.aar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
}
