package com.hackpuntes.fridagate.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * ScriptUtils - Manages the Frida bypass scripts used by the Extras tab.
 *
 * Built-in scripts ship as raw JS files in assets/scripts/. Users can also import
 * their own .js files, which are kept in the app's private storage (files/scripts/).
 * Before an injection every script is copied to /data/local/tmp/, where frida-inject
 * (running as root) can read it.
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
     * @param assetPath   Path inside assets/ for built-in scripts
     * @param localPath   Absolute path of an imported script in the app's storage
     * @param fileName    Filename used when deploying to the device
     */
    data class BypassScript(
        val id: String,
        val name: String,
        val description: String,
        val category: String,
        val assetPath: String? = null,
        val localPath: String? = null,
        val fileName: String
    ) {
        /** Imported by the user, so it can be deleted */
        val isCustom: Boolean get() = localPath != null
    }

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
     * Reads the JavaScript source of a script, built-in or imported.
     *
     * @param context Android context (needed to open assets)
     * @param script  The script descriptor
     * @return The full JS source code as a String
     */
    fun readScriptContent(context: Context, script: BypassScript): String = when {
        script.localPath != null -> File(script.localPath).readText()
        script.assetPath != null -> context.assets.open(script.assetPath).bufferedReader().use { it.readText() }
        else -> ""
    }

    // -------------------------------------------------------------------------
    // Imported scripts
    // -------------------------------------------------------------------------

    /** Largest script accepted on import */
    private const val MAX_SCRIPT_BYTES = 5 * 1024 * 1024

    private fun customDir(context: Context) = File(context.filesDir, "scripts")

    /** Imported scripts sorted by name. Blocking: call it from Dispatchers.IO. */
    fun listCustomScripts(context: Context): List<BypassScript> =
        customDir(context)
            .listFiles { file -> file.isFile && file.name.endsWith(".js") }
            ?.sortedBy { it.name.lowercase() }
            ?.map { customScript(it) }
            ?: emptyList()

    /**
     * Copies a .js file picked by the user into the app's private storage.
     * Blocking: call it from Dispatchers.IO.
     *
     * @throws IllegalArgumentException if the file can't be read, is too big or isn't text
     */
    fun importScript(context: Context, uri: Uri): BypassScript {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val bytes = resolver.openInputStream(uri)?.use { readLimited(it, MAX_SCRIPT_BYTES) }
            ?: throw IllegalArgumentException("could not open the file")
        require(bytes.none { it == 0.toByte() }) { "it is not a text file" }

        val dir = customDir(context).apply { mkdirs() }
        val target = uniqueFile(dir, sanitizeScriptName(displayName))
        target.writeBytes(bytes)
        return customScript(target)
    }

    /** Deletes an imported script. Built-in scripts can't be deleted. */
    fun deleteCustomScript(script: BypassScript): Boolean =
        script.localPath?.let { File(it).delete() } ?: false

    private fun customScript(file: File) = BypassScript(
        id          = "custom:${file.name}",
        name        = file.name.removeSuffix(".js"),
        description = "Imported script",
        category    = "custom",
        localPath   = file.absolutePath,
        // Prefixed so it can't overwrite a built-in script in /data/local/tmp
        fileName    = "fridagate_custom_${file.name}"
    )

    /** Safe file name for an imported script: letters, digits, '.', '-' and '_', ending in .js */
    internal fun sanitizeScriptName(displayName: String?): String {
        var base = (displayName ?: "").substringAfterLast('/')
        if (base.lowercase().endsWith(".js")) base = base.dropLast(3)
        base = base.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').take(60)
        return base.ifEmpty { "script" } + ".js"
    }

    /** [name] inside [dir], or name-2.js, name-3.js... when it is already taken */
    internal fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        var counter = 2
        while (candidate.exists()) {
            candidate = File(dir, "${name.removeSuffix(".js")}-$counter.js")
            counter++
        }
        return candidate
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            require(out.size() <= max) { "it is bigger than ${max / 1024 / 1024} MB" }
        }
        return out.toByteArray()
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
