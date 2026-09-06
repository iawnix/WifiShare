import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

// Keep the Android 16 test runtime out of the APK and out of the user's shared Maven cache.
val robolectricSdk by configurations.creating {
    isTransitive = false
}
val prepareRobolectricSdk by tasks.registering(Sync::class) {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("robolectric-sdk"))
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
        versionCode = 25
        versionName = "0.12.4"
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
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

tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricSdk)
    maxParallelForks = 1
    maxHeapSize = "1g"
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath)
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16.1")
    robolectricSdk("org.robolectric:android-all-instrumented:16-robolectric-13921718-i7")
}
