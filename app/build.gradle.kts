plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.linetracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.linetracker"
        minSdk = 26
        targetSdk = 35
        versionCode = 35
        versionName = "3.4-debug"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
