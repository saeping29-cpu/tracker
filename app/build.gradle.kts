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
        versionCode = 25
        versionName = "2.5"
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
