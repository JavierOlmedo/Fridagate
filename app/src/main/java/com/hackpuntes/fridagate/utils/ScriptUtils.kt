package com.hackpuntes.fridagate.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ScriptUtils - Manages Frida bypass scripts.
 *
 * Scripts are stored as raw JS files in assets/scripts/ and deployed to
 * /data/local/tmp/ at runtime so they can be used with the frida CLI.
 *
 * Typical workflow:
 *  1. User enters the target package name in the UI
 *  2. User taps "Inject" on a script
 *  3. We save the .js file to /data/local/tmp/ via root
 *  4a. If frida CLI is present on device: run it directly
 *  4b. If not: show the frida command to run from the host machine
 *
 * Note: frida-server (managed by FridaUtils) is the daemon that must be
 * running on the device. The frida CLI is a separate tool that connects
 * to that daemon and is typically run from the host machine.
 */
object ScriptUtils {

    // Directory where scripts are staged (readable by root tools)
    private const val SCRIPT_DIR = "/data/local/tmp"

    // Public directory — accessible via `adb pull` without root on the host
    private const val DOWNLOADS_DIR = "/sdcard/Download"

    /**
     * Represents one bypass script available in the Extras tab.
     *
     * @param id          Unique identifier used for logging and filenames
     * @param name        Human-readable display name
     * @param description What the script does (shown in the UI)
     * @param category    Logical grouping: "root" or "ssl"
     * @param assetPath   Path inside assets/ where the .js file lives
     * @param fileName    Filename used when deploying to the device
     */
    data class BypassScript(
        val id: String,
        val name: String,
        val description: String,
        val category: String,
        val assetPath: String,
        val fileName: String
    )

    /**
     * All available bypass scripts, ordered by category.
     * Adding new scripts: create the .js in assets/scripts/ and add an entry here.
     */
    val SCRIPTS = listOf(
        BypassScript(
            id          = "root_bypass",
            name        = "Root Detection Bypass",
            description = "Hooks File.exists(), Runtime.exec(), SystemProperties, " +
                          "and PackageManager to hide all signs of root access: " +
                          "su binaries, Magisk, SuperSU, and build flags.",
            category    = "root",
            assetPath   = "scripts/fridantiroot.js",
            fileName    = "fridantiroot.js"
        ),
        BypassScript(
            id          = "ssl_bypass",
            name        = "Universal SSL Pinning Bypass",
            description = "Bypasses certificate pinning for TrustManager, OkHttp3/2, " +
                          "Conscrypt, HostnameVerifier, Android Network Security Config, " +
                          "and TrustKit. Works on most apps without modifications.",
            category    = "ssl",
            assetPath   = "scripts/universal-android-ssl-pinning-bypass-with-frida.js",
            fileName    = "universal-android-ssl-pinning-bypass-with-frida.js"
        )
    )

    // -------------------------------------------------------------------------
    // Script content
    // -------------------------------------------------------------------------

    /**
     * Reads the JavaScript source of a script from the app's assets.
     *
     * @param context Android context (needed to open assets)
     * @param script  The script descriptor
     * @return The full JS source code as a String
     */
    fun readScriptContent(context: Context, script: BypassScript): String {
        return context.assets.open(script.assetPath).bufferedReader().readText()
    }

    // -------------------------------------------------------------------------
    // Device deployment
    // -------------------------------------------------------------------------

    /**
     * Result of deploying a script to the device.
     *
     * @param tmpPath       Path in /data/local/tmp/ (for on-device frida CLI)
     * @param downloadsPath Path in /sdcard/Download/ (for `adb pull` to host)
     */
    data class DeployResult(
        val tmpPath: String,
        val downloadsPath: String
    )

    /**
     * Saves a script to both /data/local/tmp/ and /sdcard/Download/ on the device.
     *
     * Why two locations?
     *  - /data/local/tmp/  → used if frida CLI is available directly on the device
     *  - /sdcard/Download/ → accessible via `adb pull` so the user can grab it on
     *                        their host machine and pass it to frida with `-l`
     *
     * Both copies are made via root (su) so no WRITE_EXTERNAL_STORAGE permission
     * is needed in the manifest.
     *
     * @param context Android context
     * @param script  The script to deploy
     * @return DeployResult with both paths, or null if saving failed
     */
    suspend fun saveScriptToDevice(context: Context, script: BypassScript): DeployResult? {
        return withContext(Dispatchers.IO) {
            try {
                val content = readScriptContent(context, script)

                // Write to app's private dir first (no root needed here)
                val tempFile = File(context.filesDir, script.fileName)
                tempFile.writeText(content)

                val tmpPath       = "$SCRIPT_DIR/${script.fileName}"
                val downloadsPath = "$DOWNLOADS_DIR/${script.fileName}"

                // Copy to /data/local/tmp/
                RootUtils.executeSuCommand("cp ${tempFile.absolutePath} $tmpPath")
                RootUtils.executeSuCommand("chmod 644 $tmpPath")

                // Copy to /sdcard/Download/ so the host can pull it via adb
                RootUtils.executeSuCommand("cp ${tempFile.absolutePath} $downloadsPath")
                RootUtils.executeSuCommand("chmod 644 $downloadsPath")

                tempFile.delete()

                // Verify at least the tmp path exists
                val check = RootUtils.executeSuCommand("ls $tmpPath")
                val ok = check.contains(script.fileName) && !check.contains("No such file")

                if (ok) DeployResult(tmpPath, downloadsPath) else null
            } catch (e: Exception) {
                null
            }
        }
    }

    // -------------------------------------------------------------------------
    // Command building
    // -------------------------------------------------------------------------

    /**
     * Builds the complete host-side workflow for one or more scripts:
     *  1. One `adb pull` per script — copies each .js from /sdcard/Download/ to ./
     *  2. `frida -U -f` spawn command with one `-l` flag per script
     *  3. `frida -U` attach variant
     *
     * frida supports multiple -l flags in a single invocation:
     *   frida -U -f com.app -l script1.js -l script2.js --no-pause
     *
     * The `-l` flag always refers to a LOCAL path on the host machine,
     * NOT a path on the Android device — hence the adb pull step.
     *
     * @param deploys     List of (script, DeployResult) pairs for all enabled scripts
     * @param packageName Target app package (e.g. "com.target.app")
     * @return Multi-line string with adb pull commands + frida spawn + frida attach
     */
    fun buildCombinedHostCommands(
        deploys: List<Pair<BypassScript, DeployResult>>,
        packageName: String
    ): String = buildString {
        appendLine("# 1. Pull scripts from device to your current directory")
        deploys.forEach { (script, deploy) ->
            appendLine("adb pull ${deploy.downloadsPath} ./")
        }
        appendLine()
        appendLine("# 2a. Spawn — frida launches the app with all scripts loaded")
        append("frida -U -f $packageName")
        deploys.forEach { (script, _) -> append(" -l ./${script.fileName}") }
        appendLine(" --no-pause")
        appendLine()
        append("# 2b. Attach — if the app is already running")
        append("frida -U $packageName")
        deploys.forEach { (script, _) -> append(" -l ./${script.fileName}") }
    }

    /**
     * Returns just the frida spawn command (for copying to clipboard).
     */
    fun buildFridaSpawnCommand(
        deploys: List<Pair<BypassScript, DeployResult>>,
        packageName: String
    ): String = buildString {
        append("frida -U -f $packageName")
        deploys.forEach { (script, _) -> append(" -l ./${script.fileName}") }
        append(" --no-pause")
    }

    // -------------------------------------------------------------------------
    // On-device execution (optional — frida CLI must be present on device)
    // -------------------------------------------------------------------------

    /**
     * Looks for a frida CLI binary on the device (not common, but possible).
     * Checks several standard locations where someone might have pushed it.
     *
     * @return The full path to the binary if found, null otherwise
     */
    suspend fun findFridaCli(): String? {
        return withContext(Dispatchers.IO) {
            val candidates = listOf(
                "/data/local/tmp/frida",
                "/usr/bin/frida",
                "/usr/local/bin/frida",
                "/system/xbin/frida",
                "/system/bin/frida"
            )
            candidates.firstOrNull { path ->
                val result = RootUtils.executeSuCommand("ls $path")
                result.contains(path.substringAfterLast("/")) &&
                        !result.contains("No such file")
            }
        }
    }

    /**
     * Runs the frida CLI directly on the device (requires frida binary on device).
     * This is an optional path; most users will run frida from their host machine.
     *
     * @param fridaCliPath Path to the frida binary on device
     * @param scriptPath   Path to the .js script on device
     * @param packageName  Target package to instrument
     * @return Command output, or an error message
     */
    suspend fun injectOnDevice(
        fridaCliPath: String,
        scriptPath: String,
        packageName: String
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                val cmd = "$fridaCliPath -U -f $packageName -l $scriptPath --no-pause"
                val output = RootUtils.executeSuCommand(cmd)
                output.ifEmpty { "Command executed (no output captured)" }
            } catch (e: Exception) {
                "ERROR: ${e.message}"
            }
        }
    }
}
