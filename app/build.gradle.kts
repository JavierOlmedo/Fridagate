plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
        versionCode = 1
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }
}

dependencies {
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
    // This already contains material-icons-core, so we only need this one dependency
    implementation("androidx.compose.material:material-icons-extended")

    // --- Navigation ---
    // Handles navigation between screens (tabs) in Compose
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // --- ViewModel ---
    // ViewModel survives screen rotations and holds UI state
    // The -compose variant adds special Compose integration
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // --- Coroutines ---
    // Allows running async code (network, file I/O) without blocking the UI thread
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // --- Networking ---
    // OkHttp: HTTP client used to download files and call APIs
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // --- JSON Parsing ---
    // Gson: converts JSON strings into Kotlin data classes and vice versa
    implementation("com.google.code.gson:gson:2.10.1")

    // --- Compression ---
    // XZ: decompresses .xz files (Frida server binaries are distributed as .xz)
    implementation("org.tukaani:xz:1.9")

    // --- Persistent Storage ---
    // DataStore: modern replacement for SharedPreferences
    // Used to save user settings (Burp IP, port, etc.) across app restarts
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // --- Testing ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}