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
        versionCode = 42
        versionName = "3.5.0-data-debug"
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

 dependencies { implementation("org.opencv:opencv:4.10.0") }
