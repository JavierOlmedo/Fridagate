package com.hackpuntes.fridagate.ui.dashboard

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hackpuntes.fridagate.data.AppPreferences
import com.hackpuntes.fridagate.utils.FridaUtils
import com.hackpuntes.fridagate.utils.ProxyUtils
import com.hackpuntes.fridagate.utils.RootUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DashboardViewModel - Manages the global status overview and one-tap actions.
 *
 * This ViewModel reads from both FridaUtils and ProxyUtils to give the user
 * a single view of the entire interception setup state.
 *
 * It also provides "ACTIVATE ALL" and "DEACTIVATE ALL" — convenience actions
 * that run the full setup or teardown sequence in a single coroutine.
 *
 * We define it here (in the same file as the screen) because it is only
 * used by DashboardScreen. When a ViewModel grows large, move it to its own file.
 */
class DashboardViewModel(context: Context) : ViewModel() {

    private val prefs = AppPreferences(context.applicationContext)

    // ── Status flags ──────────────────────────────────────────────────────────

    /** Whether the device has root access */
    private val _isRootAvailable = MutableStateFlow(false)
    val isRootAvailable: StateFlow<Boolean> = _isRootAvailable.asStateFlow()

    /** Whether frida-server is installed on the device */
    private val _isFridaInstalled = MutableStateFlow(false)
    val isFridaInstalled: StateFlow<Boolean> = _isFridaInstalled.asStateFlow()

    /** Whether frida-server process is running */
    private val _isFridaRunning = MutableStateFlow(false)
    val isFridaRunning: StateFlow<Boolean> = _isFridaRunning.asStateFlow()

    /** Whether iptables proxy rules are active */
    private val _isProxyActive = MutableStateFlow(false)
    val isProxyActive: StateFlow<Boolean> = _isProxyActive.asStateFlow()

    /** Whether Burp Suite is reachable at the saved IP/port */
    private val _isBurpReachable = MutableStateFlow(false)
    val isBurpReachable: StateFlow<Boolean> = _isBurpReachable.asStateFlow()

    /** Whether a background operation is in progress */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Combined log from all operations performed via the dashboard */
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    init {
        // Refresh all status values when the dashboard opens
        refreshStatus()
    }

    // ── Status refresh ────────────────────────────────────────────────────────

    /**
     * Checks the current state of all components and updates the status flags.
     * Called on init and when the user taps the Refresh button.
     */
    fun refreshStatus() {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("Refreshing system status...")

            // Check root
            val root = RootUtils.isRootAvailable()
            _isRootAvailable.value = root

            // Check Frida
            val fridaInstalled = FridaUtils.isFridaServerInstalled()
            _isFridaInstalled.value = fridaInstalled
            val fridaRunning = if (fridaInstalled) FridaUtils.isFridaServerRunning() else false
            _isFridaRunning.value = fridaRunning

            // Check proxy
            val proxyActive = ProxyUtils.isIptablesProxyEnabled()
            _isProxyActive.value = proxyActive

            // Check Burp reachability using saved settings
            val ip = prefs.burpIp.first()
            val port = prefs.burpHttpPort.first()
            val burpReachable = ProxyUtils.isBurpReachable(ip, port)
            _isBurpReachable.value = burpReachable

            addLog("Root: $root | Frida: $fridaRunning | Proxy: $proxyActive | Burp: $burpReachable")
            _isLoading.value = false
        }
    }

    // ── One-tap actions ───────────────────────────────────────────────────────

    /**
     * Runs the full interception setup in sequence:
     *  1. Start frida-server (bypasses SSL pinning)
     *  2. Enable iptables proxy (redirects all traffic to Burp)
     *
     * Uses a single coroutine so steps run in order, not in parallel.
     * Each step is logged so the user can follow the progress.
     */
    fun activateAll() {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("── ACTIVATE ALL ──────────────────")

            // Step 1: Start frida-server
            if (!_isFridaInstalled.value) {
                addLog("ERROR: Frida server is not installed — install it from the Frida tab first")
                _isLoading.value = false
                return@launch
            }

            if (_isFridaRunning.value) {
                addLog("Frida server already running — skipping")
            } else {
                addLog("Starting frida-server...")
                val started = FridaUtils.startFridaServer()
                _isFridaRunning.value = started
                if (started) addLog("Frida server started") else addLog("ERROR: Failed to start frida-server")
            }

            // Step 2: Enable iptables proxy
            val ip = prefs.burpIp.first()
            val httpPort = prefs.burpHttpPort.first()
            val httpsPort = prefs.burpHttpsPort.first()

            addLog("Enabling iptables proxy → $ip:$httpPort...")
            val proxyEnabled = ProxyUtils.enableIptablesProxy(ip, httpPort, httpsPort)
            _isProxyActive.value = proxyEnabled
            if (proxyEnabled) addLog("iptables proxy enabled") else addLog("ERROR: Failed to enable proxy")

            // Step 3: Verify Burp is reachable
            addLog("Checking Burp Suite at $ip:$httpPort...")
            val burpReachable = ProxyUtils.isBurpReachable(ip, httpPort)
            _isBurpReachable.value = burpReachable
            if (burpReachable) {
                addLog("Burp reachable — interception is ACTIVE")
            } else {
                addLog("WARNING: Burp not reachable — make sure Burp is running on your PC")
            }

            addLog("── DONE ──────────────────────────")
            _isLoading.value = false
        }
    }

    /**
     * Tears down the interception setup in sequence:
     *  1. Stop frida-server
     *  2. Disable iptables proxy
     */
    fun deactivateAll() {
        viewModelScope.launch {
            _isLoading.value = true
            addLog("── DEACTIVATE ALL ────────────────")

            // Step 1: Stop frida-server
            if (_isFridaRunning.value) {
                addLog("Stopping frida-server...")
                val stopped = FridaUtils.stopFridaServer()
                _isFridaRunning.value = !stopped
                if (stopped) addLog("Frida server stopped") else addLog("ERROR: Failed to stop frida-server")
            } else {
                addLog("Frida server not running — skipping")
            }

            // Step 2: Disable iptables proxy
            addLog("Disabling iptables proxy...")
            val disabled = ProxyUtils.disableIptablesProxy()
            _isProxyActive.value = !disabled
            if (disabled) addLog("iptables proxy disabled") else addLog("ERROR: Failed to disable proxy")

            // Step 3: Clear system proxy (http_proxy + global_http_proxy)
            // Without this, the proxy setting persists across reboots and the WiFi shows "no internet"
            addLog("Clearing system proxy...")
            ProxyUtils.clearSystemProxy()
            addLog("System proxy cleared")

            _isBurpReachable.value = false
            addLog("── DONE ──────────────────────────")
            _isLoading.value = false
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
        addLog("Logs cleared")
    }

    private fun addLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        _logs.value = _logs.value + "[$time] $message"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DashboardScreen - Global status overview and one-tap interception control.
 */
@Composable
fun DashboardScreen() {
    val context = LocalContext.current

    val viewModel: DashboardViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return DashboardViewModel(context) as T
            }
        }
    )

    val isRootAvailable by viewModel.isRootAvailable.collectAsState()
    val isFridaInstalled by viewModel.isFridaInstalled.collectAsState()
    val isFridaRunning by viewModel.isFridaRunning.collectAsState()
    val isProxyActive by viewModel.isProxyActive.collectAsState()
    val isBurpReachable by viewModel.isBurpReachable.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val logs by viewModel.logs.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Status overview card ──────────────────────────────────────────
            StatusOverviewCard(
                isRootAvailable = isRootAvailable,
                isFridaInstalled = isFridaInstalled,
                isFridaRunning = isFridaRunning,
                isProxyActive = isProxyActive,
                isBurpReachable = isBurpReachable
            )

            // ── One-tap action buttons ────────────────────────────────────────
            OneTabActionsCard(
                isLoading = isLoading,
                onActivateAll = { viewModel.activateAll() },
                onDeactivateAll = { viewModel.deactivateAll() },
                onRefresh = { viewModel.refreshStatus() }
            )

            // ── Log panel ─────────────────────────────────────────────────────
            DashboardLogPanel(
                logs = logs,
                onClear = { viewModel.clearLogs() }
            )
        }

        // Loading overlay
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * Card showing all component statuses as a grid of indicator rows.
 * Each row shows a colored dot + label + value.
 */
@Composable
private fun StatusOverviewCard(
    isRootAvailable: Boolean,
    isFridaInstalled: Boolean,
    isFridaRunning: Boolean,
    isProxyActive: Boolean,
    isBurpReachable: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "System Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            HorizontalDivider()

            // Each StatusIndicatorRow shows one component's status
            StatusIndicatorRow(label = "Root Access",     active = isRootAvailable,  activeText = "Available",  inactiveText = "Not available")
            StatusIndicatorRow(label = "Frida Installed", active = isFridaInstalled, activeText = "Yes",        inactiveText = "No")
            StatusIndicatorRow(label = "Frida Running",   active = isFridaRunning,   activeText = "Running",    inactiveText = "Stopped")
            StatusIndicatorRow(label = "Proxy (iptables)",active = isProxyActive,    activeText = "Active",     inactiveText = "Inactive")
            StatusIndicatorRow(label = "Burp Reachable",  active = isBurpReachable,  activeText = "Yes",        inactiveText = "No")
        }
    }
}

/**
 * A single status row with a colored indicator dot.
 *
 * @param label       Name of the component (e.g., "Frida Running")
 * @param active      Whether the component is in the active/good state
 * @param activeText  Text to show when active (e.g., "Running")
 * @param inactiveText Text to show when inactive (e.g., "Stopped")
 */
@Composable
private fun StatusIndicatorRow(
    label: String,
    active: Boolean,
    activeText: String,
    inactiveText: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Colored dot indicator
            Text(
                text = "●",
                color = if (active) Color(0xFF4CAF50) else Color(0xFFF44336),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (active) activeText else inactiveText,
                fontWeight = FontWeight.Bold,
                color = if (active) Color(0xFF4CAF50) else Color(0xFFF44336),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * Card with ACTIVATE ALL, DEACTIVATE ALL, and Refresh buttons.
 */
@Composable
private fun OneTabActionsCard(
    isLoading: Boolean,
    onActivateAll: () -> Unit,
    onDeactivateAll: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Quick Actions",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "ACTIVATE ALL starts frida-server and enables the iptables proxy in one tap.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            // ACTIVATE ALL — green, prominent
            Button(
                onClick = onActivateAll,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text(
                    text = "▶  ACTIVATE ALL",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            // DEACTIVATE ALL — red
            Button(
                onClick = onDeactivateAll,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
            ) {
                Text(
                    text = "■  DEACTIVATE ALL",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            // Refresh status
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                Text("Refresh Status")
            }
        }
    }
}

/**
 * Log panel for the dashboard — same pattern as the other screens.
 */
@Composable
private fun DashboardLogPanel(logs: List<String>, onClear: () -> Unit) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Log", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onClear) { Text("Clear") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color(0xFF1A1A1A), MaterialTheme.shapes.small)
                    .padding(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Text("No logs yet...", color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                } else {
                    LazyColumn(state = listState) {
                        items(logs) { line ->
                            Text(
                                text = line,
                                // White/grey for dashboard logs — neutral, not Frida green or Proxy blue
                                color = Color(0xFFCCCCCC),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
