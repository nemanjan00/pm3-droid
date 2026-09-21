// Explicit import: in the Kotlin DSL `java` resolves to Gradle's own `java`
// extension, which shadows the java.* package.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing, when secrets/release.properties exists (see
// tools/make-release-key.sh). Absent -- on a fresh clone, or in CI, where the
// key arrives as secrets instead -- the release build stays unsigned rather
// than failing, so anyone can still build and diff the output.
val releaseKeystoreProperties: Properties? =
    file("../secrets/release.properties").takeIf { it.exists() }?.let { propertiesFile ->
        Properties().apply { propertiesFile.inputStream().use { load(it) } }
    }

android {
    namespace = "io.github.nemanjan00.pm3"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.nemanjan00.pm3"
        // API 26: the client is built against android-26, and the USB host
        // and BLE APIs this relies on are all present from there.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Only the ABIs native/build-pm3.sh produces a client for. Shipping
            // an x86 APK without a client in it would install and then fail at
            // the first exec.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (releaseKeystoreProperties != null) {
            create("release") {
                storeFile = file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
                // v1 off: minSdk is 26, so every target supports v2/v3, and
                // a v1 signature is the weaker one attackers target.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystoreProperties != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        jniLibs {
            // The client is an executable, not a library: it must exist as a
            // real file on disk for exec(), not be mapped out of the APK.
            useLegacyPackaging = true
        }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // A per-ABI split keeps the download near 20 MB instead of carrying both
    // ~6 MB clients plus a 9 MB resource archive for every device.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    testOptions {
        unitTests {
            // TagParser scrapes the client's printed output; the samples in
            // the tests are real client text, so these run on the JVM with no
            // device and no Android framework stubs needed.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.usbserial)

    testImplementation(libs.junit)
    // The android.jar stub's org.json does nothing under
    // isReturnDefaultValues, so JSONObject silently parses to empty. The real
    // implementation makes the framing tests exercise real parsing.
    testImplementation(libs.json)
}
