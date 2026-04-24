plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.iaw.lanshare"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.iaw.lanshare"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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

dependencies {
}
