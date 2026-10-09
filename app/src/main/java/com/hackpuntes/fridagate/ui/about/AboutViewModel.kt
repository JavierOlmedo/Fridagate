package com.hackpuntes.fridagate.ui.about

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hackpuntes.fridagate.data.AppPreferences
import com.hackpuntes.fridagate.utils.Diagnostics
import com.hackpuntes.fridagate.utils.ProxyTool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * AboutViewModel - Runs the diagnostics shown on the About screen.
 *
 * Only on demand: the checks need root, so opening the screen never runs them.
 */
class AboutViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)

    /** Report sections, empty until the first run */
    private val _diagnostics = MutableStateFlow<List<Diagnostics.Section>>(emptyList())
    val diagnostics: StateFlow<List<Diagnostics.Section>> = _diagnostics.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun runDiagnostics() {
        if (_isRunning.value) return
        _isRunning.value = true
        viewModelScope.launch {
            try {
                _diagnostics.value = Diagnostics.collect(savedState())
            } finally {
                _isRunning.value = false
            }
        }
    }

    /** The saved settings the checks compare the device against */
    private suspend fun savedState() = Diagnostics.Saved(
        tool = ProxyTool.fromName(prefs.proxyTool.first()),
        proxyIp = prefs.burpIp.first(),
        proxyPort = prefs.burpHttpPort.first(),
        caHash = prefs.caHash.first(),
        caReinstallOnBoot = prefs.caReinstallOnBoot.first(),
        server = prefs.fridaServerConfig()
    )
}
