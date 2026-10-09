package com.hackpuntes.fridagate.ui.proxy

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hackpuntes.fridagate.data.AppPreferences
import com.hackpuntes.fridagate.utils.InputValidator
import com.hackpuntes.fridagate.utils.InstalledApps
import com.hackpuntes.fridagate.utils.ProxyUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ProxyViewModel - Manages the state and business logic for the Proxy screen.
 *
 * Responsibilities:
 *  - Load and save Burp Suite connection settings (IP, ports) via AppPreferences
 *  - Enable/disable the iptables transparent proxy
 *  - Enable/disable the system-level HTTP proxy
 *  - Test connectivity to Burp Suite
 *  - Install Burp's CA certificate and show whether the system trusts it
 *  - Maintain a log of operations shown in the UI
 *
 * @param context Needed to instantiate AppPreferences (which needs Context for DataStore)
 *
 * Note: Passing Context to a ViewModel is generally discouraged because ViewModels
 * outlive Activities — but here we use applicationContext (not Activity context)
 * which is safe because it lives as long as the app process itself.
 */
class ProxyViewModel(context: Context) : ViewModel() {

    // We use applicationContext to avoid leaking the Activity
    private val appContext = context.applicationContext

    // AppPreferences instance for reading/writing persistent settings
    private val prefs = AppPreferences(appContext)

    // -------------------------------------------------------------------------
    // Connection settings state
    // -------------------------------------------------------------------------

    /** The Burp Suite IP address entered by the user */
    private val _burpIp = MutableStateFlow(AppPreferences.DEFAULT_BURP_IP)
    val burpIp: StateFlow<String> = _burpIp.asStateFlow()

    /** Burp listener port that receives redirected HTTP (port 80) traffic */
    private val _httpPort = MutableStateFlow(AppPreferences.DEFAULT_HTTP_PORT)
    val httpPort: StateFlow<Int> = _httpPort.asStateFlow()

    /** Burp listener port that receives redirected HTTPS (port 443) traffic */
    private val _httpsPort = MutableStateFlow(AppPreferences.DEFAULT_HTTPS_PORT)
    val httpsPort: StateFlow<Int> = _httpsPort.asStateFlow()

    // -------------------------------------------------------------------------
    // Proxy status state
    // -------------------------------------------------------------------------

    /** Whether the iptables transparent proxy rules are currently active */
    private val _isIptablesEnabled = MutableStateFlow(false)
    val isIptablesEnabled: StateFlow<Boolean> = _isIptablesEnabled.asStateFlow()

    /** App selected as iptables target: package name, or "" for every app */
    private val _targetPackage = MutableStateFlow("")
    val targetPackage: StateFlow<String> = _targetPackage.asStateFlow()

    /**
     * What the live rules redirect: null when inactive, "" for every app,
     * otherwise the package name of the only redirected app
     */
    private val _activeTarget = MutableStateFlow<String?>(null)
    val activeTarget: StateFlow<String?> = _activeTarget.asStateFlow()

    /** Installed non-system apps, for the target picker */
    private val _installedApps = MutableStateFlow<List<InstalledApps.AppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledApps.AppInfo>> = _installedApps.asStateFlow()

    /** Whether the Android system proxy is currently set */
    private val _isSystemProxyEnabled = MutableStateFlow(false)
    val isSystemProxyEnabled: StateFlow<Boolean> = _isSystemProxyEnabled.asStateFlow()

    /**
     * Whether Burp Suite is reachable at the configured IP:port.
     * null = not yet tested, true = reachable, false = not reachable
     */
    private val _isBurpReachable = MutableStateFlow<Boolean?>(null)
    val isBurpReachable: StateFlow<Boolean?> = _isBurpReachable.asStateFlow()

    /**
     * Whether the CA that Burp is serving right now is trusted by the system.
     * null = unknown (Burp not reachable, so its current CA can't be fetched)
     */
    private val _isCertInstalled = MutableStateFlow<Boolean?>(null)
    val isCertInstalled: StateFlow<Boolean?> = _isCertInstalled.asStateFlow()

    /** Whether a background operation is running (shows loading indicator) */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Log messages for the terminal-style log panel */
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    init {
        // In order: the status checks need the saved Burp address, not the default one
        viewModelScope.launch {
            loadSavedSettings()
            refreshStatus()
        }
        viewModelScope.launch {
            _installedApps.value = withContext(Dispatchers.IO) { InstalledApps.load(appContext) }
        }
    }

    // -------------------------------------------------------------------------
    // Settings management
    // -------------------------------------------------------------------------

    /**
     * Loads the previously saved Burp settings from DataStore.
     *
     * .first() collects only the first emission from a Flow and then cancels.
     * This is the idiomatic way to do a one-shot read from DataStore.
     */
    private suspend fun loadSavedSettings() {
        _burpIp.value = prefs.burpIp.first()
        _httpPort.value = prefs.burpHttpPort.first()
        _httpsPort.value = prefs.burpHttpsPort.first()
        _targetPackage.value = prefs.proxyTargetPackage.first()
        addLog("Settings loaded — Burp: ${_burpIp.value}:${_httpPort.value}")
    }

    /**
     * Updates the Burp IP in memory and saves it to DataStore.
     *
     * @param ip The new IP address string
     */
    fun updateBurpIp(ip: String) {
        _burpIp.value = ip
        viewModelScope.launch {
            prefs.saveBurpIp(ip)
        }
    }

    /**
     * Updates the HTTP port in memory and saves it to DataStore.
     * Converts the String from the text field to Int, ignoring invalid input.
     *
     * @param port The port number as a String (from the user's text field)
     */
    fun updateHttpPort(port: String) {
        val portInt = port.toIntOrNull() ?: return // Ignore if not a valid number
        _httpPort.value = portInt
        viewModelScope.launch {
            prefs.saveBurpHttpPort(portInt)
        }
    }

    /**
     * Updates the HTTPS port in memory and saves it to DataStore.
     *
     * @param port The port number as a String
     */
    fun updateHttpsPort(port: String) {
        val portInt = port.toIntOrNull() ?: return
        _httpsPort.value = portInt
        viewModelScope.launch {
            prefs.saveBurpHttpsPort(portInt)
        }
    }

    // -------------------------------------------------------------------------
    // Proxy control
    // -------------------------------------------------------------------------

    /**
     * Checks the current state of both proxy methods and of the CA certificate.
     * Called when the user taps Refresh.
     */
    fun checkProxyStatus() {
        viewModelScope.launch { refreshStatus() }
    }

    private suspend fun refreshStatus() {
        _isLoading.value = true

        // Check iptables rules
        val iptablesActive = updateRedirectState()

        // Check system proxy
        val systemProxy = ProxyUtils.getSystemProxy()
        _isSystemProxyEnabled.value = systemProxy != null

        // Check whether the system trusts the CA Burp is serving now
        val certInstalled = ProxyUtils.isBurpCertInstalled(_burpIp.value, _httpPort.value)
        _isCertInstalled.value = certInstalled

        val certText = when (certInstalled) {
            true -> "trusted"
            false -> "not installed"
            null -> "unknown (Burp unreachable)"
        }
        addLog("Proxy status — iptables: $iptablesActive, system: ${systemProxy ?: "none"}, CA: $certText")
        _isLoading.value = false
    }

    /**
     * Changes the app whose traffic is redirected ("" for every app).
     * If the redirect is on, the rules are applied again for the new target.
     */
    fun setTargetPackage(packageName: String) {
        _targetPackage.value = packageName
        viewModelScope.launch {
            prefs.saveProxyTargetPackage(packageName)
            if (_isIptablesEnabled.value) {
                addLog("Target changed — applying the iptables rules again")
                toggleIptablesProxy(true)
            }
        }
    }

    /**
     * Toggles the iptables transparent proxy on or off.
     *
     * If enabling: redirects TCP 80/443 to Burp and blocks QUIC / IPv6 web traffic.
     * If disabling: removes Fridagate's own chains, leaving other rules untouched.
     *
     * @param enable true to enable, false to disable
     */
    fun toggleIptablesProxy(enable: Boolean) {
        if (enable && !validateBurpSettings()) return
        viewModelScope.launch {
            _isLoading.value = true

            if (enable) {
                val target = _targetPackage.value
                val targetUid = if (target.isEmpty()) null else InstalledApps.uidOf(appContext, target)
                if (target.isNotEmpty() && targetUid == null) {
                    addLog("ERROR: $target is not installed — choose another target app")
                    _isLoading.value = false
                    return@launch
                }
                val who = if (targetUid == null) "every app" else target
                addLog("Enabling iptables proxy for $who → ${_burpIp.value}:${_httpPort.value}/${_httpsPort.value}...")
                val result = ProxyUtils.enableIptablesProxy(
                    burpIp = _burpIp.value,
                    httpPort = _httpPort.value,
                    httpsPort = _httpsPort.value,
                    targetUid = targetUid
                )
                if (result.success) {
                    addLog("iptables proxy enabled — TCP 80/443 of $who goes to Burp")
                    addLog("Burp listener must have 'Support invisible proxying' enabled")
                    if (result.message.isNotEmpty()) addLog("WARNING: ${result.message}")
                } else {
                    addLog("ERROR: ${result.message}")
                }
            } else {
                addLog("Disabling iptables proxy...")
                val result = ProxyUtils.disableIptablesProxy()
                if (result.success) {
                    addLog("iptables proxy disabled — traffic flows normally")
                } else {
                    addLog("ERROR: ${result.message}")
                }
            }

            // Show what is really active, whatever the outcome
            updateRedirectState()
            _isLoading.value = false
        }
    }

    /**
     * Toggles the Android system proxy on or off.
     *
     * @param enable true to set the system proxy, false to clear it
     */
    fun toggleSystemProxy(enable: Boolean) {
        if (enable && !validateBurpSettings()) return
        viewModelScope.launch {
            _isLoading.value = true

            if (enable) {
                addLog("Setting system proxy → ${_burpIp.value}:${_httpPort.value}...")
                val success = ProxyUtils.setSystemProxy(_burpIp.value, _httpPort.value)
                if (success) {
                    addLog("System proxy set — apps that respect proxy will use Burp")
                } else {
                    addLog("ERROR: Failed to set system proxy")
                }
            } else {
                addLog("Clearing system proxy...")
                val success = ProxyUtils.clearSystemProxy()
                if (success) {
                    addLog("System proxy cleared")
                } else {
                    addLog("ERROR: Failed to clear system proxy")
                }
            }

            _isSystemProxyEnabled.value = ProxyUtils.getSystemProxy() != null
            _isLoading.value = false
        }
    }

    /**
     * Tests whether Burp Suite is reachable by opening a TCP socket.
     * Updates _isBurpReachable with the result.
     */
    fun testBurpConnection() {
        viewModelScope.launch {
            _isLoading.value = true
            _isBurpReachable.value = null // Reset to "testing..." state
            addLog("Testing connection to ${_burpIp.value}:${_httpPort.value}...")

            val reachable = ProxyUtils.isBurpReachable(_burpIp.value, _httpPort.value)
            _isBurpReachable.value = reachable

            if (reachable) {
                addLog("Burp Suite is reachable at ${_burpIp.value}:${_httpPort.value}")
            } else {
                addLog("Cannot reach Burp Suite — verify IP, port, and that Burp is running")
            }

            _isLoading.value = false
        }
    }

    /**
     * Downloads Burp's CA certificate and adds it to the Android system trust store.
     * The certificate lives in memory: it must be installed again after a reboot.
     */
    fun installBurpCertificate() {
        if (!validateBurpSettings()) return
        viewModelScope.launch {
            _isLoading.value = true
            addLog("Installing Burp CA from http://${_burpIp.value}:${_httpPort.value}/cert...")

            val result = ProxyUtils.installBurpCertificate(_burpIp.value, _httpPort.value)

            if (result.success) {
                addLog(result.message)
                addLog("Restart target apps so they load the new CA")
                addLog("Not persistent: install it again after every reboot")
            } else {
                addLog("ERROR: ${result.message}")
            }

            _isCertInstalled.value = ProxyUtils.isBurpCertInstalled(_burpIp.value, _httpPort.value)
            _isLoading.value = false
        }
    }

    /** Clears all log entries */
    fun clearLogs() {
        _logs.value = emptyList()
        addLog("Logs cleared")
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Reads the live iptables rules into the UI state. Returns whether the redirect is on. */
    private suspend fun updateRedirectState(): Boolean {
        val state = ProxyUtils.redirectState()
        _isIptablesEnabled.value = state.active
        _activeTarget.value = when {
            !state.active -> null
            state.targetUid == null -> ""
            else -> withContext(Dispatchers.IO) { InstalledApps.packageOf(appContext, state.targetUid) }
                ?: "uid ${state.targetUid}"
        }
        return state.active
    }

    /** Logs an error and returns false if the Burp IP or ports are not usable */
    private fun validateBurpSettings(): Boolean {
        if (!InputValidator.isValidIpv4(_burpIp.value)) {
            addLog("ERROR: '${_burpIp.value}' is not a valid IPv4 address")
            return false
        }
        if (!InputValidator.isValidPort(_httpPort.value) || !InputValidator.isValidPort(_httpsPort.value)) {
            addLog("ERROR: Ports must be between 1 and 65535")
            return false
        }
        return true
    }

    /** Appends a timestamped message to the log list */
    private fun addLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        _logs.value = _logs.value + "[$time] $message"
    }
}
