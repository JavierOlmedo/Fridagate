package com.hackpuntes.fridagate.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FridaInjectUtils - Manages the frida-inject binary and on-device script injection.
 *
 * frida-inject is a compiled C binary from the Frida project that runs directly
 * on Android — no Python, no connected PC required.
 *
 * It connects to the local frida-server (must be running at 127.0.0.1:27042)
 * and asks it to spawn or attach to the target app.
 *
 * Download URL pattern:
 *   https://github.com/frida/frida/releases/download/<ver>/frida-inject-<ver>-android-<arch>.xz
 */
object FridaInjectUtils {

    const val INJECT_BINARY_PATH = "/data/local/tmp/frida-inject"
    private const val INJECT_VERSION_FILE = "/data/local/tmp/frida-inject-version.txt"

    // frida-inject writes its output here so we can read it back for logging
    private const val INJECT_LOG = "/data/local/tmp/fridagate_inject.log"

    // -------------------------------------------------------------------------
    // Status checks
    // -------------------------------------------------------------------------

    suspend fun isFridaInjectInstalled(): Boolean = withContext(Dispatchers.IO) {
        RootUtils.exec("[ -f $INJECT_BINARY_PATH ]").isSuccess
    }

    suspend fun getInstalledVersion(): String? = withContext(Dispatchers.IO) {
        val result = RootUtils.exec("cat $INJECT_VERSION_FILE")
        if (result.isSuccess) result.stdout.trim().ifEmpty { null } else null
    }

    // -------------------------------------------------------------------------
    // Download and installation
    // -------------------------------------------------------------------------

    suspend fun downloadAndInstall(context: Context, version: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                // The version ends up in a URL and in a shell command
                if (!FridaUtils.isValidVersionFormat(version)) return@withContext false
                val arch = FridaUtils.getDeviceArchitecture()
                val url  = "https://github.com/frida/frida/releases/download/$version/" +
                           "frida-inject-$version-android-$arch.xz"

                // Reuse FridaUtils download — it decompresses .xz and saves as filesDir/frida-server
                val downloaded = FridaUtils.downloadFridaServerFromUrl(context, url)
                    ?: return@withContext false

                val injectFile = File(context.filesDir, "frida-inject")
                downloaded.renameTo(injectFile)
                injectFile.setExecutable(true)

                val install = RootUtils.exec(
                    "cp ${ShellUtils.quote(injectFile.absolutePath)} $INJECT_BINARY_PATH" +
                        " && chmod 755 $INJECT_BINARY_PATH" +
                        " && printf '%s\\n' ${ShellUtils.quote(version)} > $INJECT_VERSION_FILE"
                )

                try { injectFile.delete() } catch (_: Exception) {}
                install.isSuccess && isFridaInjectInstalled()
            } catch (e: Exception) { false }
        }

    // -------------------------------------------------------------------------
    // Launch & inject
    // -------------------------------------------------------------------------

    /**
     * Spawns the target app with one or more scripts injected from the first instruction.
     *
     * Flow:
     *  1. Save all scripts to /data/local/tmp/
     *  2. If more than one script: concatenate them into a single temp file
     *     (frida-inject takes exactly one script argument)
     *  3. Kill any existing instance of the target app
     *  4. Run: frida-inject -f <package> <script> and capture real output
     *  5. Read the log to report success/error
     *
     * @param context     Android context (to read scripts from assets)
     * @param scripts     Enabled bypass scripts (1 or more)
     * @param packageName Target app package
     * @return List of log lines to show in the UI
     */
    suspend fun launchWithScripts(
        context: Context,
        scripts: List<ScriptUtils.BypassScript>,
        packageName: String
    ): List<String> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        if (!InputValidator.isValidPackageName(packageName)) {
            return@withContext listOf("ERROR: '$packageName' is not a valid package name")
        }
        val pkg = ShellUtils.quote(packageName)

        try {
            // Step 1: save scripts and build the path to pass to frida-inject
            val scriptPath = if (scripts.size == 1) {
                val deploy = ScriptUtils.saveScriptToDevice(context, scripts.first())
                    ?: return@withContext listOf("ERROR: Could not save script — check root access")
                lines += "Saved ${scripts.first().fileName} → ${deploy.tmpPath}"
                deploy.tmpPath
            } else {
                // Multiple scripts: concatenate into one temp file
                val combined = buildString {
                    scripts.forEach { s ->
                        appendLine("// ── ${s.fileName} ──")
                        appendLine(ScriptUtils.readScriptContent(context, s))
                        appendLine()
                    }
                }
                val tmpFile = File(context.filesDir, "fridagate_combined.js")
                tmpFile.writeText(combined)
                val dest = "/data/local/tmp/fridagate_combined.js"
                val copy = RootUtils.exec("cp ${ShellUtils.quote(tmpFile.absolutePath)} $dest && chmod 644 $dest")
                tmpFile.delete()
                if (!copy.isSuccess) {
                    return@withContext listOf("ERROR: Could not save combined script: ${copy.errorMessage}")
                }
                scripts.forEach { lines += "Saved ${it.fileName}" }
                lines += "Combined into fridagate_combined.js"
                dest
            }

            // Step 2: kill existing instance
            RootUtils.exec("am force-stop $pkg")
            Thread.sleep(600)
            lines += "Stopped $packageName"

            // Step 3: clear previous log
            RootUtils.exec("rm -f $INJECT_LOG")

            // Step 4: spawn with frida-inject in its own independent su process.
            //
            // WHY NOT RootUtils.exec():
            //   RootUtils keeps a persistent su shell. Backgrounding frida-inject (&) inside
            //   that shell makes it a child job — it can receive SIGHUP when the shell resets
            //   state, and job-control semantics vary by Android shell implementation.
            //
            // WHY Runtime.exec() directly:
            //   Creates a brand-new su process whose sole child is frida-inject.
            //   No persistent shell in between, no job-control issues.
            //   We don't call waitFor() so it runs for the lifetime of the target app.
            //
            // frida-inject --eternalize: keep the script alive even after frida-inject exits
            val injectCmd = "$INJECT_BINARY_PATH -f $pkg -s ${ShellUtils.quote(scriptPath)} -e > $INJECT_LOG 2>&1 &"
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", injectCmd))
                // Intentionally no waitFor() — frida-inject stays attached to the target process
            } catch (e: Exception) {
                lines += "ERROR launching frida-inject: ${e.message}"
                return@withContext lines
            }

            // Step 5: wait for frida-inject to spawn and inject, then read output
            Thread.sleep(4000)
            val injectLog = RootUtils.exec("cat $INJECT_LOG").stdout.trim()

            if (injectLog.isNotEmpty()) {
                lines += "frida-inject output:"
                injectLog.lines().filter { it.isNotBlank() }.forEach { lines += "  $it" }
            }

            // Step 6: verify the app is running
            val pid = findProcessId(packageName)
            if (pid != null) {
                lines += "✓ $packageName is running (PID $pid)"
            } else {
                lines += "Process not found — trying attach fallback..."
                lines += attachByName(packageName, scriptPath)
            }

        } catch (e: Exception) {
            lines += "ERROR: ${e.message}"
        }

        lines
    }

    /**
     * Fallback: launch via monkey then attach by process name.
     * Used when frida-inject -f does not spawn the app.
     */
    private suspend fun attachByName(packageName: String, scriptPath: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val pkg = ShellUtils.quote(packageName)
                RootUtils.exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                Thread.sleep(1000)

                RootUtils.exec("rm -f $INJECT_LOG")
                val attachCmd = "$INJECT_BINARY_PATH -n $pkg -s ${ShellUtils.quote(scriptPath)} -e > $INJECT_LOG 2>&1 &"
                Runtime.getRuntime().exec(arrayOf("su", "-c", attachCmd))
                Thread.sleep(2500)

                val log = RootUtils.exec("cat $INJECT_LOG").stdout.trim()
                val pid = findProcessId(packageName)

                buildString {
                    if (pid != null) append("✓ Attached to $packageName (PID $pid)")
                    else append("Could not find process — is frida-server running?")
                    if (log.isNotEmpty()) append(" | frida-inject: $log")
                }
            } catch (e: Exception) {
                "ERROR (fallback): ${e.message}"
            }
        }
    }

    /**
     * Tries to find the PID of the target app using several methods,
     * since not all Android versions have `pidof`.
     */
    private fun findProcessId(packageName: String): String? {
        val pkg = ShellUtils.quote(packageName)

        // Try pidof first (modern Android)
        val pid = RootUtils.exec("pidof $pkg").stdout.trim()
        if (pid.isNotEmpty() && pid.all { it.isDigit() || it == ' ' }) return pid

        // Fall back to ps
        val ps = RootUtils.exec("ps -A | grep -F -- $pkg").stdout
        if (ps.isNotEmpty() && !ps.contains("grep")) {
            // ps output: USER PID PPID ...
            val parts = ps.trim().split("\\s+".toRegex())
            if (parts.size > 1) return parts[1]
        }
        return null
    }
}
