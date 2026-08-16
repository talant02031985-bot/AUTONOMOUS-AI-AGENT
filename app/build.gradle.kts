plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kg.autonomous.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "kg.autonomous.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.0"
    }

    signingConfigs {
        create("ayana") {
            storeFile = file("ayana-release.jks")
            storePassword = System.getenv("AYANA_KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("AYANA_KEY_ALIAS") ?: ""
            keyPassword = System.getenv("AYANA_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("ayana")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation(files("libs/sherpa-onnx-1.13.5.aar"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
