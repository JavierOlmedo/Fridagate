package com.hackpuntes.fridagate.ui.frida

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hackpuntes.fridagate.utils.FridaUtils
import com.hackpuntes.fridagate.utils.FridaUtils.FridaRelease
import com.hackpuntes.fridagate.utils.RootUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * FridaViewModel - Manages the state and business logic for the Frida screen.
 *
 * In MVVM (Model-View-ViewModel) architecture:
 *  - Model      = FridaUtils (data and operations)
 *  - View       = FridaScreen composable (UI, what the user sees)
 *  - ViewModel  = this class (state holder and bridge between Model and View)
 *
 * Why do we need a ViewModel?
 *  - It survives screen rotations (the composable is destroyed and recreated, but the ViewModel is not)
 *  - It separates UI logic from business logic (the composable only reads state and sends events)
 *  - It provides a clean way to run coroutines tied to the screen's lifecycle
 *
 * StateFlow vs LiveData:
 *  We use StateFlow here (instead of LiveData used in the original Frida Launcher)
 *  because StateFlow integrates better with Jetpack Compose.
 *  StateFlow always has a current value (unlike LiveData which can be null initially).
 *  Compose collects StateFlow with "collectAsState()" in the composable.
 */
class FridaViewModel : ViewModel() {

    // -------------------------------------------------------------------------
    // UI State — each property below is a piece of state that the UI observes.
    // When any of these change, Compose automatically recomposes the affected UI.
    // -------------------------------------------------------------------------

    /**
     * Whether a background operation is in progress (download, install, etc.).
     * When true, the UI shows a loading indicator and disables buttons.
     *
     * MutableStateFlow: internal, can be changed only from within this ViewModel
     * StateFlow (asStateFlow): exposed to the UI as read-only
     */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Whether frida-server is installed on the device */
    private val _isServerInstalled = MutableStateFlow(false)
    val isServerInstalled: StateFlow<Boolean> = _isServerInstalled.asStateFlow()

    /** Whether frida-server process is currently running */
    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    /** The version of the installed frida-server (e.g., "16.7.0") */
    private val _installedVersion = MutableStateFlow("Unknown")
    val installedVersion: StateFlow<String> = _installedVersion.asStateFlow()

    /** List of available Frida releases fetched from GitHub */
    private val _availableReleases = MutableStateFlow<List<FridaRelease>>(emptyList())
    val availableReleases: StateFlow<List<FridaRelease>> = _availableReleases.asStateFlow()

    /** The version selected by the user in the dropdown */
    private val _selectedVersion = MutableStateFlow("")
    val selectedVersion: StateFlow<String> = _selectedVersion.asStateFlow()

    /** Whether root access is available on the device */
    private val _isRootAvailable = MutableStateFlow(false)
    val isRootAvailable: StateFlow<Boolean> = _isRootAvailable.asStateFlow()

    /**
     * Log messages shown in the terminal-style text area.
     * Each entry is a single line with a timestamp prefix.
     * We use a List so Compose can efficiently detect changes.
     */
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    /** The last set of custom flags entered by the user (remembered across sessions) */
    private val _lastCustomFlags = MutableStateFlow("")
    val lastCustomFlags: StateFlow<String> = _lastCustomFlags.asStateFlow()

    // -------------------------------------------------------------------------
    // Initialization — runs once when the ViewModel is first created
    // -------------------------------------------------------------------------

    init {
        // Check root and server status as soon as the screen opens
        checkRootAndStatus()
        // Fetch available versions from GitHub in the background
        loadAvailableReleases()
    }

    // -------------------------------------------------------------------------
    // Public functions — called by the UI in response to user actions
    // -------------------------------------------------------------------------

    /**
     * Checks root availability and current frida-server status.
     * Called on init and when the user taps the Refresh button.
     *
     * viewModelScope.launch starts a coroutine that is automatically
     * cancelled when the ViewModel is cleared (screen is destroyed).
     */
    fun checkRootAndStatus() {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("Checking root access and server status...")

            // Check root access
            val rootAvailable = RootUtils.isRootAvailable()
            _isRootAvailable.value = rootAvailable

            if (!rootAvailable) {
                addLog("Root access NOT available — some features will be disabled")
                _isLoading.value = false
                return@launch
            }

            addLog("Root access available")

            // Check if frida-server binary exists
            val isInstalled = FridaUtils.isFridaServerInstalled()
            _isServerInstalled.value = isInstalled

            if (isInstalled) {
                // Read the installed version from the version file
                val version = FridaUtils.getInstalledFridaVersion()
                _installedVersion.value = version ?: "Unknown"

                // Check if the process is currently running
                val isRunning = FridaUtils.isFridaServerRunning()
                _isServerRunning.value = isRunning

                val statusText = if (isRunning) "running" else "stopped"
                addLog("Frida server ${_installedVersion.value} is installed and $statusText")
            } else {
                addLog("Frida server is not installed")
                _isServerRunning.value = false
                _installedVersion.value = "Not installed"
            }

            _isLoading.value = false
        }
    }

    /**
     * Fetches the list of available Frida releases from the GitHub API.
     * Populates the version dropdown in the UI.
     */
    fun loadAvailableReleases() {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("Fetching available Frida releases from GitHub...")

            val releases = FridaUtils.getAvailableFridaReleases()
            _availableReleases.value = releases

            if (releases.isNotEmpty()) {
                // Auto-select the latest version if nothing is selected yet
                if (_selectedVersion.value.isEmpty()) {
                    _selectedVersion.value = releases.first().version
                }
                addLog("Found ${releases.size} releases. Latest: ${releases.first().version}")
            } else {
                addLog("Could not fetch releases — check internet connection")
            }

            _isLoading.value = false
        }
    }

    /**
     * Updates the selected version when the user picks one from the dropdown.
     *
     * @param version The version string selected by the user (e.g., "16.7.0")
     */
    fun setSelectedVersion(version: String) {
        _selectedVersion.value = version
    }

    /**
     * Sets a custom version entered manually by the user.
     * Validates the format before accepting it.
     *
     * @param version Custom version string (e.g., "16.5.9")
     */
    fun setCustomVersion(version: String) {
        if (!FridaUtils.isValidVersionFormat(version)) {
            addLog("Invalid version format. Use format like: 16.5.9")
            return
        }
        _selectedVersion.value = version
        addLog("Custom version set: $version")
    }

    /**
     * Downloads, decompresses, and installs frida-server for the selected version.
     *
     * Full flow:
     *  1. Get the download URL for the selected version + device architecture
     *  2. Download the file (may take a while for large binaries)
     *  3. Decompress (.xz or .zip) and save to app's private directory
     *  4. Copy to /data/local/tmp/ and set execute permissions via root
     *  5. Clean up the temporary downloaded file
     *
     * @param context Needed to access the app's private files directory
     */
    fun downloadAndInstall(context: Context) {
        viewModelScope.launch {
            val version = _selectedVersion.value
            val architecture = FridaUtils.getDeviceArchitecture()

            if (version.isEmpty()) {
                addLog("No version selected")
                return@launch
            }

            _isLoading.value = true
            addLog("Fetching download URL for $version ($architecture)...")

            // Step 1: Get the download URL
            val url = FridaUtils.getFridaServerUrl(version, architecture)
            if (url == null) {
                addLog("ERROR: Could not find download URL for $version ($architecture)")
                _isLoading.value = false
                return@launch
            }

            addLog("Downloading frida-server $version...")

            // Step 2 & 3: Download and decompress
            val fridaFile = FridaUtils.downloadFridaServerFromUrl(context, url)
            if (fridaFile == null) {
                addLog("ERROR: Download failed")
                _isLoading.value = false
                return@launch
            }

            addLog("Installing frida-server to /data/local/tmp/...")

            // Step 4: Install via root
            val installed = FridaUtils.installFridaServer(fridaFile, version)

            if (installed) {
                _isServerInstalled.value = true
                _installedVersion.value = version
                addLog("Frida server $version installed successfully")
            } else {
                addLog("ERROR: Installation failed — check root access")
            }

            // Step 5: Clean up the temporary file from app storage
            try { fridaFile.delete() } catch (e: Exception) { /* ignore */ }

            _isLoading.value = false
        }
    }

    /** Starts frida-server with default settings (no extra flags) */
    fun startServer() {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("Starting frida-server...")

            val started = FridaUtils.startFridaServer()

            if (started) {
                _isServerRunning.value = true
                addLog("Frida server started successfully")
            } else {
                addLog("ERROR: Failed to start frida-server")
            }

            _isLoading.value = false
        }
    }

    /**
     * Starts frida-server with custom command-line flags.
     *
     * Sanitizes the input first to remove shell injection characters
     * like ; | & $ which could be used to run arbitrary commands.
     *
     * @param flags Raw flags string entered by the user
     */
    fun startServerWithCustomFlags(flags: String) {
        // Remove potentially dangerous shell characters before passing to su
        val sanitized = flags.replace(Regex("[;&|<>$`\\\\]"), "").trim()
        _lastCustomFlags.value = sanitized

        viewModelScope.launch {
            _isLoading.value = true
            addLog("Starting frida-server with flags: $sanitized")

            val started = FridaUtils.startFridaServerWithFlags(sanitized)

            if (started) {
                _isServerRunning.value = true
                addLog("Frida server started with flags: $sanitized")
            } else {
                addLog("ERROR: Failed to start frida-server with flags")
            }

            _isLoading.value = false
        }
    }

    /** Stops the running frida-server process */
    fun stopServer() {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("Stopping frida-server...")

            val stopped = FridaUtils.stopFridaServer()

            if (stopped) {
                _isServerRunning.value = false
                addLog("Frida server stopped successfully")
            } else {
                addLog("ERROR: Failed to stop frida-server")
            }

            _isLoading.value = false
        }
    }

    /** Stops the server (if running) and removes the binary from the device */
    fun uninstallServer() {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("Uninstalling frida-server...")

            val uninstalled = FridaUtils.uninstallFridaServer()

            if (uninstalled) {
                _isServerInstalled.value = false
                _isServerRunning.value = false
                _installedVersion.value = "Not installed"
                addLog("Frida server uninstalled successfully")
            } else {
                addLog("ERROR: Uninstallation failed")
            }

            _isLoading.value = false
        }
    }

    /** Clears all log entries from the log panel */
    fun clearLogs() {
        _logs.value = emptyList()
        addLog("Logs cleared")
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Appends a new line to the log with a timestamp prefix.
     *
     * Uses System.currentTimeMillis() to get the current time,
     * formats it as HH:mm:ss (hours:minutes:seconds).
     *
     * @param message The log message to append
     */
    private fun addLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        // Create a new list with the new entry appended
        // We never mutate the existing list — StateFlow needs a new object to detect the change
        _logs.value = _logs.value + "[$time] $message"
    }

    /**
     * Called automatically when the ViewModel is about to be destroyed.
     * Used to release the persistent root shell process.
     */
    override fun onCleared() {
        super.onCleared()
        RootUtils.closeSuProcess()
    }
}
