package com.hackpuntes.fridagate.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * AppPreferences - Persistent storage for user settings using DataStore.
 *
 * DataStore is the modern replacement for SharedPreferences. It stores
 * key-value pairs on disk and survives app restarts, but unlike SharedPreferences:
 *  - It is fully async (uses Kotlin Flows instead of blocking reads)
 *  - It is safe to call from coroutines
 *  - It never blocks the main thread
 *
 * How DataStore works:
 *  - Data is stored as a file on the device's internal storage
 *  - You read data as a Flow (a stream that emits a new value whenever data changes)
 *  - You write data using the edit() suspend function inside a coroutine
 *
 * Extension property pattern:
 *  The "val Context.dataStore" below is a Kotlin extension property.
 *  It adds a "dataStore" property to any Context object.
 *  "by preferencesDataStore(name)" creates and manages the actual storage file.
 *  The "name" parameter becomes the filename on disk.
 */

// This creates a DataStore file named "fridagate_settings" in the app's data directory
// It is defined at the top level (outside the class) because DataStore should be a singleton
// — only one instance should exist for the entire app to avoid conflicts
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fridagate_settings"
)

/**
 * Repository class that provides read/write access to persisted settings.
 *
 * @param context Android context, used to access the DataStore file
 */
class AppPreferences(private val context: Context) {

    /**
     * Keys - defines the key names for each stored preference.
     *
     * Keys are typed: stringPreferencesKey stores a String,
     * intPreferencesKey stores an Int. This prevents type mismatches at runtime.
     *
     * Think of these like column names in a database table.
     */
    private object Keys {
        val BURP_IP = stringPreferencesKey("burp_ip")
        val BURP_HTTP_PORT = intPreferencesKey("burp_http_port")
        val BURP_HTTPS_PORT = intPreferencesKey("burp_https_port")
        val FRIDA_SELECTED_VERSION = stringPreferencesKey("frida_selected_version")
    }

    // -------------------------------------------------------------------------
    // Burp Suite IP address
    // -------------------------------------------------------------------------

    /**
     * A Flow that emits the saved Burp IP address whenever it changes.
     *
     * Flow is like a stream of values over time:
     *  - When you first observe it, it emits the current saved value
     *  - If the value changes (via saveBurpIp), it emits the new value automatically
     *
     * The .map {} transforms each emitted Preferences object into just the IP string.
     * The "?: DEFAULT_IP" is the Elvis operator — it returns the default if the value is null
     * (null means the key hasn't been saved yet).
     */
    val burpIp: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[Keys.BURP_IP] ?: DEFAULT_BURP_IP
    }

    /**
     * Saves the Burp IP address to persistent storage.
     *
     * edit() is a suspend function that opens the DataStore for writing.
     * The lambda receives a MutablePreferences object where we set the value.
     * The write is atomic — either it fully succeeds or fully fails.
     *
     * @param ip The IP address to save (e.g., "192.168.1.100")
     */
    suspend fun saveBurpIp(ip: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.BURP_IP] = ip
        }
    }

    // -------------------------------------------------------------------------
    // Burp Suite ports
    // -------------------------------------------------------------------------

    /** Flow that emits the saved HTTP proxy port (default: 8080) */
    val burpHttpPort: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[Keys.BURP_HTTP_PORT] ?: DEFAULT_HTTP_PORT
    }

    /** Saves the HTTP proxy port */
    suspend fun saveBurpHttpPort(port: Int) {
        context.dataStore.edit { preferences ->
            preferences[Keys.BURP_HTTP_PORT] = port
        }
    }

    /** Flow that emits the saved HTTPS proxy port (default: 8443) */
    val burpHttpsPort: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[Keys.BURP_HTTPS_PORT] ?: DEFAULT_HTTPS_PORT
    }

    /** Saves the HTTPS proxy port */
    suspend fun saveBurpHttpsPort(port: Int) {
        context.dataStore.edit { preferences ->
            preferences[Keys.BURP_HTTPS_PORT] = port
        }
    }

    // -------------------------------------------------------------------------
    // Frida version selection
    // -------------------------------------------------------------------------

    /** Flow that emits the last selected Frida version */
    val fridaSelectedVersion: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[Keys.FRIDA_SELECTED_VERSION] ?: ""
    }

    /** Saves the selected Frida version */
    suspend fun saveFridaSelectedVersion(version: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.FRIDA_SELECTED_VERSION] = version
        }
    }

    // -------------------------------------------------------------------------
    // Default values
    // -------------------------------------------------------------------------

    companion object {
        const val DEFAULT_BURP_IP = "192.168.100.224"
        const val DEFAULT_HTTP_PORT = 8080
        const val DEFAULT_HTTPS_PORT = 8080
    }
}
