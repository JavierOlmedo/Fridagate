package com.hackpuntes.fridagate.utils

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * FridaUtils - All logic related to the Frida server binary.
 *
 * Frida is a dynamic instrumentation toolkit used in security research.
 * It requires a "frida-server" binary running on the Android device as root.
 * This binary is downloaded from GitHub Releases and installed to /data/local/tmp/.
 *
 * Responsibilities:
 *  - Fetch available Frida releases from the GitHub API
 *  - Download the correct binary for the device architecture (.xz or .zip)
 *  - Decompress and install the binary to /data/local/tmp/frida-server
 *  - Start / stop the frida-server process via root commands
 *  - Check if frida-server is installed or running
 *
 * All network and file operations run on Dispatchers.IO (background thread)
 * using suspend functions so they never block the UI thread.
 */
object FridaUtils {

    // Path where the frida-server binary will be installed on the device
    // /data/local/tmp/ is writable by root on all Android versions
    const val FRIDA_BINARY_PATH = "/data/local/tmp/frida-server"

    // Path where we store the installed version number as a plain text file
    // This lets us display which version is installed without running frida-server
    private const val FRIDA_VERSION_FILE = "/data/local/tmp/frida-version.txt"

    // GitHub API endpoint that returns all Frida releases as JSON
    private const val FRIDA_GITHUB_API = "https://api.github.com/repos/frida/frida/releases"

    // -------------------------------------------------------------------------
    // Data classes — these represent the structure of the JSON we receive
    // from the GitHub API. Gson maps JSON fields to these Kotlin properties.
    // -------------------------------------------------------------------------

    /**
     * Represents one Frida release (e.g., version 16.7.0 released on 2024-01-15).
     * This is our internal model — cleaned up from the raw GitHub response.
     */
    data class FridaRelease(
        val version: String,       // e.g. "16.7.0"
        val releaseDate: String,   // e.g. "2024-01-15"
        val assets: List<FridaAsset>
    )

    /**
     * Represents one downloadable file within a release.
     * Each release has multiple assets for different architectures (arm, arm64, x86, x86_64).
     */
    data class FridaAsset(
        val name: String,          // e.g. "frida-server-16.7.0-android-arm64.xz"
        val downloadUrl: String,   // Direct download URL
        val architecture: String,  // e.g. "arm64"
        val size: Long             // File size in bytes
    )

    /**
     * Raw GitHub API response for one release.
     * @SerializedName maps the JSON field name (snake_case) to our Kotlin property (camelCase).
     */
    data class GithubRelease(
        @SerializedName("tag_name") val tagName: String,         // e.g. "16.7.0"
        @SerializedName("published_at") val publishedAt: String, // e.g. "2024-01-15T10:00:00Z"
        @SerializedName("assets") val assets: List<GithubAsset>
    )

    /**
     * Raw GitHub API response for one asset (downloadable file).
     */
    data class GithubAsset(
        @SerializedName("name") val name: String,
        @SerializedName("browser_download_url") val browserDownloadUrl: String,
        @SerializedName("size") val size: Long
    )

    // -------------------------------------------------------------------------
    // Network functions
    // -------------------------------------------------------------------------

    /**
     * Fetches all available Frida releases from the GitHub API.
     *
     * Uses JsonReader for streaming JSON parsing — this is memory-efficient
     * because it processes one release at a time instead of loading the entire
     * JSON response into memory (releases list can be very large).
     *
     * @return List of FridaRelease objects, or empty list if the request fails
     */
    suspend fun getAvailableFridaReleases(): List<FridaRelease> {
        // withContext(Dispatchers.IO) moves execution to a background thread
        // Any network or file I/O MUST run here, never on the main/UI thread
        return withContext(Dispatchers.IO) {
            try {
                // Build the HTTP client with timeouts to avoid hanging forever
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS) // Time to establish connection
                    .readTimeout(30, TimeUnit.SECONDS)    // Time to receive data
                    .build()

                // Build the HTTP GET request
                val request = Request.Builder()
                    .url(FRIDA_GITHUB_API)
                    // GitHub recommends this header to get a stable API response format
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                val releases = mutableListOf<FridaRelease>()
                val gson = Gson()

                // Execute the request and use the response inside the block
                // .use {} ensures the response body is closed after we're done
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext emptyList()
                    }

                    // JsonReader streams the JSON array one element at a time
                    JsonReader(response.body?.charStream() ?: return@withContext emptyList()).use { reader ->
                        reader.beginArray() // The response is a JSON array [...]

                        while (reader.hasNext()) {
                            // Parse one release object from the stream
                            val githubRelease = gson.fromJson<GithubRelease>(reader, GithubRelease::class.java)
                            val fridaAssets = mutableListOf<FridaAsset>()

                            // Filter assets: we only want frida-server binaries for Android
                            for (asset in githubRelease.assets) {
                                val name = asset.name
                                // Keep only files like: frida-server-16.7.0-android-arm64.xz
                                if (name.startsWith("frida-server-") &&
                                    (name.endsWith(".xz") || name.endsWith(".zip"))) {

                                    // Extract the architecture from the filename using regex
                                    // Pattern: "android-(arm|arm64|x86|x86_64)"
                                    val archPattern = "android-(arm|arm64|x86|x86_64)".toRegex()
                                    val matchResult = archPattern.find(name)
                                    val architecture = matchResult?.groupValues?.get(1) ?: "unknown"

                                    fridaAssets.add(
                                        FridaAsset(
                                            name = name,
                                            downloadUrl = asset.browserDownloadUrl,
                                            architecture = architecture,
                                            size = asset.size
                                        )
                                    )
                                }
                            }

                            // Only include releases that have Android server assets
                            if (fridaAssets.isNotEmpty()) {
                                releases.add(
                                    FridaRelease(
                                        // Tag name is the version number (e.g., "16.7.0")
                                        version = githubRelease.tagName,
                                        // publishedAt is "2024-01-15T10:00:00Z" — we only want the date part
                                        releaseDate = githubRelease.publishedAt.split("T")[0],
                                        assets = fridaAssets
                                    )
                                )
                            }
                        }

                        reader.endArray()
                    }
                }

                return@withContext releases
            } catch (e: Exception) {
                return@withContext emptyList()
            }
        }
    }

    /**
     * Finds the download URL for a specific Frida version and device architecture.
     *
     * First looks in the cached releases list, then falls back to constructing
     * the URL manually if the version isn't in the list (e.g., older releases).
     *
     * @param version The Frida version string, e.g. "16.7.0"
     * @param architecture The device architecture, e.g. "arm64"
     * @return Download URL string, or null if not found
     */
    suspend fun getFridaServerUrl(version: String, architecture: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val releases = getAvailableFridaReleases()
                // Find the release that matches the requested version
                val release = releases.find { it.version == version }

                if (release != null) {
                    // Look for an asset that matches the device architecture
                    val asset = release.assets.find { it.architecture == architecture }
                    if (asset != null) return@withContext asset.downloadUrl

                    // Fallback: search by partial filename match
                    val fallback = release.assets.find { it.name.contains("-android-$architecture") }
                    if (fallback != null) return@withContext fallback.downloadUrl
                }

                // Last resort: build the URL manually and verify it exists
                return@withContext buildCustomVersionUrl(version, architecture)
            } catch (e: Exception) {
                return@withContext null
            }
        }
    }

    /**
     * Constructs a download URL for a specific version and verifies it exists.
     *
     * Used when the version is not in the GitHub API response (e.g., very old releases).
     * Sends a HEAD request (no body, just checks if the URL is valid).
     *
     * @param version Frida version, e.g. "16.5.9"
     * @param architecture Device architecture, e.g. "arm64"
     * @return The URL if it exists on GitHub, null otherwise
     */
    private suspend fun buildCustomVersionUrl(version: String, architecture: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val fileName = "frida-server-$version-android-$architecture.xz"
                val url = "https://github.com/frida/frida/releases/download/$version/$fileName"

                val client = OkHttpClient.Builder()
                    .followRedirects(true)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .build()

                // HEAD request: like GET but without downloading the body
                // Used to check if a URL exists without wasting bandwidth
                val request = Request.Builder().url(url).head().build()

                client.newCall(request).execute().use { response ->
                    // 200 OK or 302 redirect both mean the file exists
                    if (response.isSuccessful || response.code == 302) url else null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Validates that a version string has the correct format (e.g., "16.7.0").
     *
     * Regex explanation:
     *   ^\d+    — starts with one or more digits
     *   \.\d+   — followed by a dot and digits (repeated twice)
     *   (-[a-zA-Z0-9]+)?$ — optionally followed by a dash and alphanumeric chars (e.g., "-rc1")
     *
     * @param version The version string to validate
     * @return true if the format is valid
     */
    fun isValidVersionFormat(version: String): Boolean {
        val pattern = Regex("^\\d+\\.\\d+\\.\\d+(-[a-zA-Z0-9]+)?$")
        return pattern.matches(version)
    }

    // -------------------------------------------------------------------------
    // Download and installation functions
    // -------------------------------------------------------------------------

    /**
     * Downloads the Frida server binary from the given URL.
     *
     * Handles three file formats:
     *  - .xz  → decompress with XZInputStream
     *  - .zip → extract the binary entry with ZipInputStream
     *  - raw  → save directly (no decompression needed)
     *
     * The downloaded file is saved to the app's private files directory
     * (context.filesDir), which is /data/data/com.hackpuntes.fridagate/files/
     * This directory is readable only by our app, not by other apps.
     *
     * @param context Android context (needed to get the files directory)
     * @param url The direct download URL for the binary
     * @return The decompressed File object, or null if download/decompression failed
     */
    suspend fun downloadFridaServerFromUrl(context: Context, url: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .followRedirects(true)
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS) // Large file — needs more time
                    .build()

                val downloadRequest = Request.Builder().url(url).build()

                // Destination file: app's private directory + "frida-server"
                val fridaFile = File(context.filesDir, "frida-server")

                client.newCall(downloadRequest).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null

                    response.body?.let { body ->
                        when {
                            // --- Handle .xz compressed files ---
                            url.endsWith(".xz") -> {
                                // First save the compressed file to disk
                                val compressedFile = File(context.filesDir, "frida-server.xz")

                                // Write the downloaded bytes to the .xz file
                                FileOutputStream(compressedFile).use { output ->
                                    body.byteStream().use { input ->
                                        val buffer = ByteArray(8192) // 8KB chunks
                                        var bytesRead: Int
                                        while (input.read(buffer).also { bytesRead = it } != -1) {
                                            output.write(buffer, 0, bytesRead)
                                        }
                                    }
                                }

                                // Decompress the .xz file into the final binary
                                try {
                                    XZInputStream(FileInputStream(compressedFile)).use { xzInput ->
                                        FileOutputStream(fridaFile).use { output ->
                                            val buffer = ByteArray(8192)
                                            var bytesRead: Int
                                            while (xzInput.read(buffer).also { bytesRead = it } != -1) {
                                                output.write(buffer, 0, bytesRead)
                                            }
                                        }
                                    }
                                    compressedFile.delete() // Clean up the .xz file
                                } catch (e: Exception) {
                                    // Clean up both files if decompression fails
                                    compressedFile.delete()
                                    if (fridaFile.exists()) fridaFile.delete()
                                    throw IOException("Failed to decompress .xz file", e)
                                }
                            }

                            // --- Handle .zip compressed files ---
                            url.endsWith(".zip") -> {
                                // ZipInputStream lets us read entries inside the zip
                                ZipInputStream(body.byteStream()).use { zipStream ->
                                    var entry = zipStream.nextEntry
                                    while (entry != null) {
                                        // Find the entry that contains "frida-server"
                                        if (entry.name.contains("frida-server")) {
                                            FileOutputStream(fridaFile).use { output ->
                                                val buffer = ByteArray(8192)
                                                var bytesRead: Int
                                                while (zipStream.read(buffer).also { bytesRead = it } != -1) {
                                                    output.write(buffer, 0, bytesRead)
                                                }
                                            }
                                            break // Found it, stop looking
                                        }
                                        zipStream.closeEntry()
                                        entry = zipStream.nextEntry
                                    }
                                }
                            }

                            // --- Handle raw (uncompressed) files ---
                            else -> {
                                FileOutputStream(fridaFile).use { output ->
                                    body.byteStream().use { input ->
                                        val buffer = ByteArray(8192)
                                        var bytesRead: Int
                                        while (input.read(buffer).also { bytesRead = it } != -1) {
                                            output.write(buffer, 0, bytesRead)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Mark the file as executable so Android can run it as a process
                fridaFile.setExecutable(true)
                return@withContext fridaFile
            } catch (e: Exception) {
                return@withContext null
            }
        }
    }

    /**
     * Installs the Frida server binary to /data/local/tmp/ using root commands.
     *
     * Steps:
     *  1. Copy the file from our app's private directory to /data/local/tmp/
     *     (needs root: the app can't write there directly)
     *  2. chmod 755 so Android can execute it
     *  3. Save the version number to a text file for later display
     *
     * @param fridaFile The downloaded binary file (in the app's private directory)
     * @param version The version string to save (e.g., "16.7.0")
     * @return true if installation was successful
     */
    suspend fun installFridaServer(fridaFile: File, version: String): Boolean {
        return withContext(Dispatchers.IO) {
            val copy = RootUtils.exec(
                "cp ${ShellUtils.quote(fridaFile.absolutePath)} $FRIDA_BINARY_PATH && chmod 755 $FRIDA_BINARY_PATH"
            )
            if (!copy.isSuccess) return@withContext false

            saveInstalledVersion(version)
            isFridaServerInstalled()
        }
    }

    /**
     * Saves the installed Frida version to a text file on the device.
     * This file is read by getInstalledFridaVersion() to display the current version.
     *
     * @param version The version string to save
     * @return true if the file was written successfully
     */
    suspend fun saveInstalledVersion(version: String): Boolean {
        return withContext(Dispatchers.IO) {
            RootUtils.exec("printf '%s\\n' ${ShellUtils.quote(version)} > $FRIDA_VERSION_FILE").isSuccess
        }
    }

    /**
     * Reads the installed Frida version from the version text file.
     *
     * @return Version string (e.g., "16.7.0"), or null if not installed or file missing
     */
    suspend fun getInstalledFridaVersion(): String? {
        return withContext(Dispatchers.IO) {
            val result = RootUtils.exec("cat $FRIDA_VERSION_FILE")
            if (result.isSuccess) result.stdout.trim().ifEmpty { null } else null
        }
    }

    // -------------------------------------------------------------------------
    // Server control functions
    // -------------------------------------------------------------------------

    /**
     * Starts frida-server with no extra flags.
     * Delegates to startFridaServerWithFlags with an empty string.
     *
     * @return true if the server started successfully
     */
    suspend fun startFridaServer(): Boolean = startFridaServerWithFlags("")

    /**
     * Starts frida-server with optional command-line flags.
     *
     * The flags are split into arguments (ShellUtils.splitArgs) and every argument is
     * quoted, so whatever the user types is passed to frida-server as data and can't
     * run extra commands.
     *
     *  - nohup: keeps the process running after the shell that started it exits
     *  - </dev/null >/dev/null 2>&1: detaches it from the root shell's input and output
     *  - &: runs it in the background (non-blocking)
     *
     * @param flags Extra flags for frida-server (e.g., "-l 0.0.0.0:27042 --token=secret")
     * @return true if the server is running after the start attempt,
     *         false if it isn't or [flags] has an unclosed quote
     */
    suspend fun startFridaServerWithFlags(flags: String): Boolean {
        val args = try {
            ShellUtils.splitArgs(flags).joinToString("") { " " + ShellUtils.quote(it) }
        } catch (e: IllegalArgumentException) {
            return false
        }
        return withContext(Dispatchers.IO) {
            // Don't start a second instance if it's already running
            if (isFridaServerRunning()) return@withContext true

            RootUtils.exec("nohup $FRIDA_BINARY_PATH$args </dev/null >/dev/null 2>&1 &")

            // Wait 1.5 seconds for the server to fully initialize, then verify it is up
            Thread.sleep(1500)
            isFridaServerRunning()
        }
    }

    /**
     * Stops every frida-server process.
     *
     * pidof finds the processes by name and kill -9 (SIGKILL, can't be ignored)
     * ends them. killall is a fallback for unusual toolsets.
     *
     * @return true if no frida-server process is left
     */
    suspend fun stopFridaServer(): Boolean {
        return withContext(Dispatchers.IO) {
            RootUtils.exec("pids=\$(pidof frida-server) && kill -9 \$pids")
            Thread.sleep(300)
            if (!isFridaServerRunning()) return@withContext true

            RootUtils.exec("killall -9 frida-server")
            Thread.sleep(500)
            !isFridaServerRunning()
        }
    }

    // -------------------------------------------------------------------------
    // Status check functions
    // -------------------------------------------------------------------------

    /**
     * Checks whether the frida-server binary is installed on the device.
     *
     * @return true if the binary exists at FRIDA_BINARY_PATH
     */
    suspend fun isFridaServerInstalled(): Boolean {
        return withContext(Dispatchers.IO) {
            RootUtils.exec("[ -f $FRIDA_BINARY_PATH ]").isSuccess
        }
    }

    /**
     * Checks whether the frida-server process is currently running.
     * pidof exits with 0 only when at least one process matches.
     *
     * @return true if a frida-server process is found
     */
    suspend fun isFridaServerRunning(): Boolean {
        return withContext(Dispatchers.IO) {
            RootUtils.exec("pidof frida-server").isSuccess
        }
    }

    /**
     * Uninstalls frida-server by removing the binary and version files.
     *
     * Stops the server first if it's running, then deletes both files.
     *
     * @return true if the binary no longer exists after uninstallation
     */
    suspend fun uninstallFridaServer(): Boolean {
        return withContext(Dispatchers.IO) {
            if (isFridaServerRunning()) stopFridaServer()
            RootUtils.exec("rm -f $FRIDA_BINARY_PATH $FRIDA_VERSION_FILE")
            !isFridaServerInstalled()
        }
    }

    // -------------------------------------------------------------------------
    // Device utility functions
    // -------------------------------------------------------------------------

    /**
     * Returns the CPU architecture name used in Frida's release files.
     *
     * Build.SUPPORTED_ABIS lists the ABIs the device can run, preferred first.
     * We take the first one Frida ships a build for:
     *  - arm64-v8a → arm64 (most phones since 2015)
     *  - armeabi-v7a / armeabi → arm (older 32-bit ARM)
     *  - x86_64 / x86 → emulators and some Intel devices
     *
     * @return "arm64", "arm", "x86_64" or "x86" (arm64 if nothing matches)
     */
    fun getDeviceArchitecture(): String =
        Build.SUPPORTED_ABIS.firstNotNullOfOrNull { fridaArchForAbi(it) } ?: "arm64"

    /** Maps an Android ABI name to Frida's architecture name, or null if Frida has no build for it */
    internal fun fridaArchForAbi(abi: String): String? = when (abi) {
        "arm64-v8a" -> "arm64"
        "armeabi-v7a", "armeabi" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> null
    }
}
