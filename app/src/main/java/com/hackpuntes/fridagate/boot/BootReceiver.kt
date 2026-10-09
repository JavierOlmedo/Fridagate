package com.hackpuntes.fridagate.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hackpuntes.fridagate.data.AppPreferences
import com.hackpuntes.fridagate.utils.ProxyUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * BootReceiver - Installs the proxy CA again after a reboot.
 *
 * The CA lives in an in-memory overlay that a reboot wipes. When "Install again at
 * boot" is on, this receiver re-applies the copy staged in /data/local/tmp/fridagate
 * (no network needed), so interception keeps working without opening the app.
 * The root manager must already have granted root to Fridagate.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // goAsync keeps the process alive while the root commands run in the background
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(BOOT_TIMEOUT_MS) {
                    val prefs = AppPreferences(appContext)
                    val hash = prefs.caHash.first()
                    if (prefs.caReinstallOnBoot.first() && hash.isNotEmpty()) {
                        val result = ProxyUtils.applyStagedCertificate(hash)
                        Log.i(TAG, "CA re-install at boot: ${if (result.success) "ok" else "failed"} — ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "CA re-install at boot failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "Fridagate"

        // Background broadcasts may run for about a minute
        const val BOOT_TIMEOUT_MS = 50_000L
    }
}
