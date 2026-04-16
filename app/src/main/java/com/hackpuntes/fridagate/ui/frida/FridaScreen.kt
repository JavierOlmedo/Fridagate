package com.hackpuntes.fridagate.ui.frida

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hackpuntes.fridagate.utils.FridaUtils

/**
 * FridaScreen - The Frida tab UI built with Jetpack Compose.
 *
 * In Compose, the UI is a tree of @Composable functions.
 * Each composable describes what the UI looks like for a given state.
 * When the state changes, Compose automatically re-renders only the affected parts.
 *
 * This screen is split into smaller composable functions for readability:
 *  - FridaScreen         → root composable, holds the ViewModel and state
 *  - StatusCard          → shows installed/running status
 *  - VersionSelector     → dropdown to pick a Frida version
 *  - ActionButtons       → install, start, stop, uninstall buttons
 *  - LogPanel            → scrollable terminal-style log
 *  - CustomFlagsDialog   → dialog for entering custom flags
 */
@Composable
fun FridaScreen(
    // The ViewModel is provided by the Compose runtime and survives recompositions
    viewModel: FridaViewModel = viewModel()
) {
    // collectAsState() turns a StateFlow into a Compose State object
    // Every time the StateFlow emits a new value, the composable recomposes
    val isLoading by viewModel.isLoading.collectAsState()
    val isInstalled by viewModel.isServerInstalled.collectAsState()
    val isRunning by viewModel.isServerRunning.collectAsState()
    val installedVersion by viewModel.installedVersion.collectAsState()
    val availableReleases by viewModel.availableReleases.collectAsState()
    val selectedVersion by viewModel.selectedVersion.collectAsState()
    val isRootAvailable by viewModel.isRootAvailable.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val lastCustomFlags by viewModel.lastCustomFlags.collectAsState()

    // Context is needed for operations that require it (e.g., file download)
    val context = LocalContext.current

    // State for showing/hiding the custom flags dialog
    var showCustomFlagsDialog by remember { mutableStateOf(false) }

    // Outer Box allows us to overlay the loading indicator on top of everything
    Box(modifier = Modifier.fillMaxSize()) {

        // Main scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Section: Device Info ──────────────────────────────────────────
            // Shows the device model and CPU architecture
            DeviceInfoCard(isRootAvailable = isRootAvailable)

            // ── Section: Server Status ────────────────────────────────────────
            // Shows whether frida-server is installed and running
            StatusCard(
                isInstalled = isInstalled,
                isRunning = isRunning,
                installedVersion = installedVersion,
                activeFlags = lastCustomFlags
            )

            // ── Section: Version Selector ─────────────────────────────────────
            // Dropdown to pick which Frida version to install
            VersionSelector(
                releases = availableReleases,
                selectedVersion = selectedVersion,
                onVersionSelected = { viewModel.setSelectedVersion(it) },
                onCustomVersion = { viewModel.setCustomVersion(it) },
                enabled = !isLoading
            )

            // ── Section: Action Buttons ───────────────────────────────────────
            ActionButtons(
                isInstalled = isInstalled,
                isRunning = isRunning,
                isLoading = isLoading,
                isRootAvailable = isRootAvailable,
                onInstall = { viewModel.downloadAndInstall(context) },
                onStart = { viewModel.startServer() },
                onStartCustom = { showCustomFlagsDialog = true },
                onStop = { viewModel.stopServer() },
                onUninstall = { viewModel.uninstallServer() },
                onRefresh = {
                    viewModel.checkRootAndStatus()
                    viewModel.loadAvailableReleases()
                }
            )

            // ── Section: Log Panel ────────────────────────────────────────────
            LogPanel(
                logs = logs,
                onClear = { viewModel.clearLogs() }
            )
        }

        // ── Loading Overlay ───────────────────────────────────────────────────
        // Shown on top of everything when an operation is in progress
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Semi-transparent black background blocks interaction with UI below
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    // ── Custom Flags Dialog ───────────────────────────────────────────────────
    if (showCustomFlagsDialog) {
        CustomFlagsDialog(
            initialFlags = lastCustomFlags,
            onConfirm = { flags ->
                viewModel.startServerWithCustomFlags(flags)
                showCustomFlagsDialog = false
            },
            onDismiss = { showCustomFlagsDialog = false }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-composables — each one is responsible for one section of the screen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Shows device model and CPU architecture, plus a root access warning if needed.
 */
@Composable
private fun DeviceInfoCard(isRootAvailable: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Device",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Build.MODEL is the device model name (e.g., "Pixel 6")
            // FridaUtils.getDeviceArchitecture() returns "arm64", "arm", etc.
            Text(
                text = "${Build.MODEL} · ${FridaUtils.getDeviceArchitecture()}",
                style = MaterialTheme.typography.bodyMedium
            )

            // Show a warning if root is not available
            if (!isRootAvailable) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "⚠ Root access not available — frida-server requires root",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * Shows the current frida-server status: installed, running, and version.
 * Uses green for active/yes states and red for inactive/no states.
 */
@Composable
private fun StatusCard(
    isInstalled: Boolean,
    isRunning: Boolean,
    installedVersion: String,
    activeFlags: String
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Server Status",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            StatusRow(label = "Installed", value = if (isInstalled) "Yes" else "No", isActive = isInstalled)
            StatusRow(label = "Running",   value = if (isRunning) "Yes" else "No",   isActive = isRunning)
            StatusRow(label = "Version",   value = installedVersion, isActive = installedVersion != "Not installed")

            // Show active flags only when the server is running
            if (isRunning) {
                StatusRow(
                    label = "Flags",
                    value = if (activeFlags.isBlank()) "default" else activeFlags,
                    isActive = true
                )
            }
        }
    }
}

/**
 * A single row showing a label and a colored status value.
 *
 * @param label   The left-side label (e.g., "Installed")
 * @param value   The right-side value (e.g., "Yes")
 * @param isActive Whether to use green (true) or red (false) for the value
 */
@Composable
private fun StatusRow(label: String, value: String, isActive: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            fontWeight = FontWeight.Bold,
            // Green when active, red when inactive
            color = if (isActive) Color(0xFF4CAF50) else Color(0xFFF44336)
        )
    }
}

/**
 * Dropdown menu for selecting a Frida version to install.
 *
 * ExposedDropdownMenuBox is the Material3 Compose equivalent of a Spinner.
 * It shows the selected item and expands to show all options when tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VersionSelector(
    releases: List<com.hackpuntes.fridagate.utils.FridaUtils.FridaRelease>,
    selectedVersion: String,
    onVersionSelected: (String) -> Unit,
    onCustomVersion: (String) -> Unit,
    enabled: Boolean
) {
    // Controls whether the dropdown is expanded or collapsed
    var expanded by remember { mutableStateOf(false) }
    // Controls whether the custom version input dialog is shown
    var showCustomDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Version to Install",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { if (enabled) expanded = !expanded }
            ) {
                // The text field that shows the currently selected version
                OutlinedTextField(
                    value = if (selectedVersion.isEmpty()) "Select version..." else selectedVersion,
                    onValueChange = {},
                    readOnly = true, // User can't type here — must use the dropdown
                    trailingIcon = {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Expand")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        // menuAnchor links this text field to the dropdown menu
                        // ExposedDropdownMenuAnchorType.PrimaryNotEditable = field is read-only (no typing)
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
                    enabled = enabled
                )

                // The dropdown list
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    // List all available releases
                    releases.forEach { release ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(text = release.version, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = release.releaseDate,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                onVersionSelected(release.version)
                                expanded = false
                            }
                        )
                    }

                    // Special option at the bottom to enter a custom version
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("⚙ Custom version...") },
                        onClick = {
                            expanded = false
                            showCustomDialog = true
                        }
                    )
                }
            }
        }
    }

    // Custom version input dialog
    if (showCustomDialog) {
        var customInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("Enter Custom Version") },
            text = {
                OutlinedTextField(
                    value = customInput,
                    onValueChange = { customInput = it },
                    label = { Text("Version (e.g. 16.5.9)") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (customInput.isNotEmpty()) {
                        onCustomVersion(customInput)
                        showCustomDialog = false
                    }
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * All action buttons for frida-server management.
 * Buttons are enabled/disabled based on the current state.
 */
@Composable
private fun ActionButtons(
    isInstalled: Boolean,
    isRunning: Boolean,
    isLoading: Boolean,
    isRootAvailable: Boolean,
    onInstall: () -> Unit,
    onStart: () -> Unit,
    onStartCustom: () -> Unit,
    onStop: () -> Unit,
    onUninstall: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Actions",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            // Install button — always visible, disabled when root is unavailable or loading
            Button(
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && isRootAvailable
            ) {
                Text("Install / Update Frida Server")
            }

            // Start/Stop/Custom buttons — only shown when frida-server is installed
            if (isInstalled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Start button — disabled when already running
                    Button(
                        onClick = onStart,
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading && !isRunning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50) // Green
                        )
                    ) { Text("Start") }

                    // Stop button — disabled when not running
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading && isRunning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF44336) // Red
                        )
                    ) { Text("Stop") }
                }

                // Custom flags button — disabled when already running
                OutlinedButton(
                    onClick = onStartCustom,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading && !isRunning
                ) { Text("Start with Custom Flags") }

                // Uninstall button
                OutlinedButton(
                    onClick = onUninstall,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Uninstall") }
            }

            // Refresh button — always available
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Refresh Status")
            }
        }
    }
}

/**
 * Terminal-style scrollable log panel.
 *
 * LazyColumn is Compose's equivalent of RecyclerView — it only renders
 * the items that are currently visible on screen, making it memory-efficient
 * for long lists.
 */
@Composable
private fun LogPanel(
    logs: List<String>,
    onClear: () -> Unit
) {
    // Used to programmatically scroll to the bottom when new logs arrive
    val listState = rememberLazyListState()

    // Auto-scroll to bottom whenever the logs list changes
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            // animateScrollToItem scrolls smoothly to the last item
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Log",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = onClear) { Text("Clear") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Dark background to simulate a terminal
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(
                        color = Color(0xFF1A1A1A),
                        shape = MaterialTheme.shapes.small
                    )
                    .padding(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Text(
                        text = "No logs yet...",
                        color = Color(0xFF666666),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                } else {
                    LazyColumn(state = listState) {
                        items(logs) { logLine ->
                            Text(
                                text = logLine,
                                color = Color(0xFF00FF00), // Green text like a terminal
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

/**
 * Dialog for entering custom frida-server flags.
 *
 * @param initialFlags The last used flags (pre-filled for convenience)
 * @param onConfirm Called with the entered flags when the user taps Start
 * @param onDismiss Called when the user cancels
 */
@Composable
private fun CustomFlagsDialog(
    initialFlags: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // remember keeps this value across recompositions within this dialog's lifetime
    var flags by remember { mutableStateOf(initialFlags) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start with Custom Flags") },
        text = {
            Column {
                OutlinedTextField(
                    value = flags,
                    onValueChange = { flags = it },
                    label = { Text("Insert Flags Here") },
                    placeholder = { Text("-l 0.0.0.0:27042") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Help text showing common flags
                Text(
                    text = "Common flags:\n" +
                            "-l ADDRESS  Listen on address\n" +
                            "--token=TOKEN  Require auth token\n" +
                            "-D  Daemonize",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(flags) }) { Text("Start") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
