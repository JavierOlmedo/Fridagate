plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The version is defined once, as appVersion in gradle.properties.
// versionCode is derived from it (MAJOR * 10000 + MINOR * 100 + PATCH), so 1.0.4 -> 10004
// and it always grows when the version does.
val appVersion: String = providers.gradleProperty("appVersion").get()
val appVersionCode: Int = run {
    val parts = appVersion.split(".").map { it.toIntOrNull() }
    require(parts.size == 3 && parts.all { it != null && it >= 0 } && parts[1]!! < 100 && parts[2]!! < 100) {
        "appVersion must look like 1.2.3 (minor and patch below 100), got '$appVersion'"
    }
    parts[0]!! * 10_000 + parts[1]!! * 100 + parts[2]!!
}

android {
    namespace = "com.hackpuntes.fridagate"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hackpuntes.fridagate"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Signing for the GitHub release workflow, which sets FRIDAGATE_KEYSTORE.
    // Local builds keep using Android Studio's "Generate Signed App Bundle / APK" wizard.
    val ciKeystore = providers.environmentVariable("FRIDAGATE_KEYSTORE").orNull
    if (ciKeystore != null) {
        signingConfigs {
            create("ci") {
                storeFile = file(ciKeystore)
                storePassword = providers.environmentVariable("FRIDAGATE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("FRIDAGATE_KEY_ALIAS").orNull ?: "fridagate"
                keyPassword = providers.environmentVariable("FRIDAGATE_KEY_PASSWORD").orNull ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            if (ciKeystore != null) signingConfig = signingConfigs.getByName("ci")
            // R8 removes unused code and resources (most Material icons) and optimizes the rest.
            // Classes read by reflection are kept in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME is shown in the About screen
        buildConfig = true
    }
}

dependencies {
    // Versions live in gradle/libs.versions.toml

    // --- Core Android ---
    // Basic Kotlin extensions for Android (adds useful shortcuts)
    implementation(libs.androidx.core.ktx)
    // Lifecycle-aware coroutine scope (auto-cancels when activity is destroyed)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Lets us use Compose inside an Activity
    implementation(libs.androidx.activity.compose)

    // --- Jetpack Compose ---
    // BOM = Bill of Materials: manages all Compose library versions automatically
    // so we don't have to specify a version number for each Compose library
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)             // Core UI components
    implementation(libs.androidx.compose.ui.graphics)    // Drawing/graphics support
    implementation(libs.androidx.compose.ui.tooling.preview) // Preview in Android Studio
    implementation(libs.androidx.compose.material3)      // Material Design 3 components

    // Material Icons Extended: includes ALL Material icons (Refresh, KeyboardArrowDown, etc.)
    // R8 removes the ones the app doesn't use from release builds
    implementation(libs.androidx.compose.material.icons.extended)

    // --- Navigation ---
    // Handles navigation between screens (tabs) in Compose
    implementation(libs.androidx.navigation.compose)

    // --- ViewModel ---
    // ViewModel survives screen rotations and holds UI state
    // The -compose variant adds special Compose integration
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // --- Coroutines ---
    // Allows running async code (network, file I/O) without blocking the UI thread
    implementation(libs.kotlinx.coroutines.android)

    // --- Networking ---
    // OkHttp: HTTP client used to download files and call APIs
    implementation(libs.okhttp)

    // --- JSON Parsing ---
    // Gson: converts JSON strings into Kotlin data classes and vice versa
    implementation(libs.gson)

    // --- Compression ---
    // XZ: decompresses .xz files (Frida server binaries are distributed as .xz)
    implementation(libs.xz)

    // --- Persistent Storage ---
    // DataStore: modern replacement for SharedPreferences
    // Used to save user settings (Burp IP, port, etc.) across app restarts
    implementation(libs.androidx.datastore.preferences)

    // --- Testing ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Print skipped and failed unit tests in the console, so CI logs show them
tasks.withType<Test>().configureEach {
    testLogging {
        events("skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
