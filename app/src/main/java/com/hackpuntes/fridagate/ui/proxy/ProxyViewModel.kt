package com.hackpuntes.fridagate.ui.proxy

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hackpuntes.fridagate.data.AppPreferences
import com.hackpuntes.fridagate.utils.ProxyUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ProxyViewModel - Manages the state and business logic for the Proxy screen.
 *
 * Responsibilities:
 *  - Load and save Burp Suite connection settings (IP, ports) via AppPreferences
 *  - Enable/disable the iptables transparent proxy
 *  - Enable/disable the system-level HTTP proxy
 *  - Test connectivity to Burp Suite
 *  - Trigger Burp CA certificate installation
 *  - Maintain a log of operations shown in the UI
 *
 * @param context Needed to instantiate AppPreferences (which needs Context for DataStore)
 *
 * Note: Passing Context to a ViewModel is generally discouraged because ViewModels
 * outlive Activities — but here we use applicationContext (not Activity context)
 * which is safe because it lives as long as the app process itself.
 */
class ProxyViewModel(context: Context) : ViewModel() {

    // AppPreferences instance for reading/writing persistent settings
    // We use applicationContext to avoid leaking the Activity
    private val prefs = AppPreferences(context.applicationContext)

    // -------------------------------------------------------------------------
    // Connection settings state
    // -------------------------------------------------------------------------

    /** The Burp Suite IP address entered by the user */
    private val _burpIp = MutableStateFlow(AppPreferences.DEFAULT_BURP_IP)
    val burpIp: StateFlow<String> = _burpIp.asStateFlow()

    /** The HTTP proxy port (typically 8080) */
    private val _httpPort = MutableStateFlow(AppPreferences.DEFAULT_HTTP_PORT)
    val httpPort: StateFlow<Int> = _httpPort.asStateFlow()

    /** The HTTPS proxy port (typically 8443) */
    private val _httpsPort = MutableStateFlow(AppPreferences.DEFAULT_HTTPS_PORT)
    val httpsPort: StateFlow<Int> = _httpsPort.asStateFlow()

    // -------------------------------------------------------------------------
    // Proxy status state
    // -------------------------------------------------------------------------

    /** Whether the iptables transparent proxy rules are currently active */
    private val _isIptablesEnabled = MutableStateFlow(false)
    val isIptablesEnabled: StateFlow<Boolean> = _isIptablesEnabled.asStateFlow()

    /** Whether the Android system proxy is currently set */
    private val _isSystemProxyEnabled = MutableStateFlow(false)
    val isSystemProxyEnabled: StateFlow<Boolean> = _isSystemProxyEnabled.asStateFlow()

    /**
     * Whether Burp Suite is reachable at the configured IP:port.
     * null = not yet tested, true = reachable, false = not reachable
     */
    private val _isBurpReachable = MutableStateFlow<Boolean?>(null)
    val isBurpReachable: StateFlow<Boolean?> = _isBurpReachable.asStateFlow()

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
        // Load saved settings from DataStore when the ViewModel is created
        loadSavedSettings()
        // Check the current proxy status (iptables rules may already be active)
        checkProxyStatus()
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
    private fun loadSavedSettings() {
        viewModelScope.launch {
            // Read each setting once and update the corresponding StateFlow
            _burpIp.value = prefs.burpIp.first()
            _httpPort.value = prefs.burpHttpPort.first()
            _httpsPort.value = prefs.burpHttpsPort.first()
            addLog("Settings loaded — Burp: ${_burpIp.value}:${_httpPort.value}")
        }
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
     * Checks the current state of both proxy methods.
     * Called on init and when the user taps Refresh.
     */
    fun checkProxyStatus() {
        viewModelScope.launch {
            _isLoading.value = true

            // Check iptables rules
            val iptablesActive = ProxyUtils.isIptablesProxyEnabled()
            _isIptablesEnabled.value = iptablesActive

            // Check system proxy
            val systemProxy = ProxyUtils.getSystemProxy()
            _isSystemProxyEnabled.value = systemProxy != null

            addLog("Proxy status — iptables: $iptablesActive, system: ${systemProxy ?: "none"}")
            _isLoading.value = false
        }
    }

    /**
     * Toggles the iptables transparent proxy on or off.
     *
     * If enabling: applies DNAT rules to redirect ports 80 and 443 to Burp.
     * If disabling: flushes the NAT OUTPUT and POSTROUTING chains.
     *
     * @param enable true to enable, false to disable
     */
    fun toggleIptablesProxy(enable: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true

            if (enable) {
                addLog("Enabling iptables proxy → ${_burpIp.value}:${_httpPort.value}/${_httpsPort.value}...")
                val success = ProxyUtils.enableIptablesProxy(
                    burpIp = _burpIp.value,
                    httpPort = _httpPort.value,
                    httpsPort = _httpsPort.value
                )
                _isIptablesEnabled.value = success
                if (success) {
                    addLog("iptables proxy enabled — all HTTP/HTTPS traffic redirected to Burp")
                } else {
                    addLog("ERROR: Failed to enable iptables proxy — check root access")
                }
            } else {
                addLog("Disabling iptables proxy...")
                val success = ProxyUtils.disableIptablesProxy()
                _isIptablesEnabled.value = !success
                if (success) {
                    addLog("iptables proxy disabled — traffic flows normally")
                } else {
                    addLog("ERROR: Failed to disable iptables proxy")
                }
            }

            _isLoading.value = false
        }
    }

    /**
     * Toggles the Android system proxy on or off.
     *
     * @param enable true to set the system proxy, false to clear it
     */
    fun toggleSystemProxy(enable: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true

            if (enable) {
                addLog("Setting system proxy → ${_burpIp.value}:${_httpPort.value}...")
                val success = ProxyUtils.setSystemProxy(_burpIp.value, _httpPort.value)
                _isSystemProxyEnabled.value = success
                if (success) {
                    addLog("System proxy set — apps that respect proxy will use Burp")
                } else {
                    addLog("ERROR: Failed to set system proxy")
                }
            } else {
                addLog("Clearing system proxy...")
                val success = ProxyUtils.clearSystemProxy()
                _isSystemProxyEnabled.value = !success
                if (success) {
                    addLog("System proxy cleared")
                } else {
                    addLog("ERROR: Failed to clear system proxy")
                }
            }

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
     * Downloads and installs Burp's CA certificate into the Android system trust store.
     * After installation, a reboot may be required for all apps to recognize the certificate.
     */
    fun installBurpCertificate() {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("Downloading Burp CA certificate from http://${_burpIp.value}:${_httpPort.value}/cert...")

            val installed = ProxyUtils.installBurpCertificate(_burpIp.value, _httpPort.value)

            if (installed) {
                addLog("Burp CA certificate installed successfully")
                addLog("A reboot may be required for all apps to trust the certificate")
            } else {
                addLog("ERROR: Certificate installation failed")
                addLog("Make sure Burp proxy is running and system proxy is active")
            }

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

    /** Appends a timestamped message to the log list */
    private fun addLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        _logs.value = _logs.value + "[$time] $message"
    }
}
