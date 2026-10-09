package com.hackpuntes.fridagate.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ScriptUtils - Manages the Frida bypass scripts used by the Extras tab.
 *
 * Scripts ship as raw JS files in assets/scripts/. Before an injection they are
 * copied to /data/local/tmp/, where frida-inject (running as root) can read them.
 */
object ScriptUtils {

    // Directory where scripts are staged (readable by root tools)
    private const val SCRIPT_DIR = "/data/local/tmp"

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
     * Copies a script to /data/local/tmp/ so frida-inject can load it.
     * The copy is made as root, so the app needs no storage permission.
     *
     * @param context Android context (to read the script)
     * @param script  The script to deploy
     * @return The script's path on the device, or null if the copy failed
     */
    suspend fun saveScriptToDevice(context: Context, script: BypassScript): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Write to the app's private dir first (no root needed), then copy as root
                val tempFile = File(context.filesDir, script.fileName)
                tempFile.writeText(readScriptContent(context, script))

                val target = "$SCRIPT_DIR/${script.fileName}"
                val quotedTarget = ShellUtils.quote(target)
                val copied = RootUtils.exec(
                    "cp ${ShellUtils.quote(tempFile.absolutePath)} $quotedTarget && chmod 644 $quotedTarget"
                ).isSuccess

                tempFile.delete()
                if (copied) target else null
            } catch (e: Exception) {
                null
            }
        }
    }
}
