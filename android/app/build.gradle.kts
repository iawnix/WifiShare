import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

val releaseSigningPropertiesFile = rootProject.file("signing.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningValue(propertyName: String, environmentName: String): String {
    return System.getenv(environmentName)?.trim().orEmpty()
        .ifBlank { releaseSigningProperties.getProperty(propertyName, "").trim() }
}

val releaseStorePath = releaseSigningValue("storeFile", "WIFISHARE_SIGNING_STORE_FILE")
val releaseStorePassword = releaseSigningValue("storePassword", "WIFISHARE_SIGNING_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("keyAlias", "WIFISHARE_SIGNING_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("keyPassword", "WIFISHARE_SIGNING_KEY_PASSWORD")
val releaseStoreFile = releaseStorePath.takeIf(String::isNotBlank)?.let {
    rootProject.file(it)
}
val releaseSigningReady = releaseStoreFile?.isFile == true &&
    releaseStorePassword.isNotBlank() &&
    releaseKeyAlias.isNotBlank() &&
    releaseKeyPassword.isNotBlank()

android {
    namespace = "io.iaw.lanshare"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.iaw.lanshare"
        minSdk = 29
        targetSdk = 35
        versionCode = 21
        versionName = "0.12.0"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
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

val validateReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fails release packaging unless a dedicated signing key is configured."
    doLast {
        if (!releaseSigningReady) {
            throw GradleException(
                "Release signing is not configured. Run scripts/configure_release_signing.sh " +
                    "or provide the WIFISHARE_SIGNING_* environment variables.",
            )
        }
    }
}

tasks.configureEach {
    if (name == "packageRelease" || name == "bundleRelease") {
        dependsOn(validateReleaseSigning)
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.json:json:20240303")
}
