package com.hackpuntes.fridagate.utils

/**
 * RootUtils - Utility object for executing root (superuser) commands.
 *
 * On Android, regular apps run in a sandboxed environment and cannot
 * execute privileged operations. A rooted device has the "su" (superuser)
 * binary installed, which allows running commands as root (uid=0).
 *
 * This object is used by both FridaUtils and ProxyUtils, so it lives
 * here as a shared utility instead of being duplicated in each module.
 *
 * How it works:
 *   1. We start a persistent "su" process once (getSuProcess)
 *   2. We send commands to it by writing to its stdin (input stream)
 *   3. We read the output from its stdout (output stream)
 *   4. When done, we close the process (closeSuProcess)
 *
 * Why a persistent process instead of a new one per command?
 *   Starting a new "su" process for every command is slow because
 *   it requires user approval each time on some root managers (e.g. Magisk).
 *   A persistent process only asks once.
 */
object RootUtils {

    // The persistent "su" process — null if not yet started or already closed
    private var suProcess: Process? = null

    // The output stream of the su process (we write commands here)
    private var suOutputStream: java.io.OutputStream? = null

    /**
     * Checks whether the device has root access available.
     *
     * Runs "su -c id" and looks for "uid=0" in the output.
     * "id" is a standard Unix command that prints the current user's identity.
     * If the user is root, it returns something like "uid=0(root) gid=0(root)".
     *
     * @return true if root is available, false otherwise
     */
    fun isRootAvailable(): Boolean {
        return try {
            // Start a new process running "su -c id"
            // "-c id" means: run the "id" command as superuser
            val process = Runtime.getRuntime().exec("su -c id")

            // Wait for the process to finish and get its exit code
            // Exit code 0 = success, anything else = failure
            val exitCode = process.waitFor()

            // Read all output from the process
            val output = process.inputStream.bufferedReader().readText()

            // Root is available if the command succeeded AND output contains uid=0
            exitCode == 0 && output.contains("uid=0")
        } catch (e: Exception) {
            // If "su" doesn't exist or any other error occurs, root is not available
            false
        }
    }

    /**
     * Returns the active su process, creating it if it doesn't exist yet.
     *
     * This implements a simple singleton pattern for the process:
     * - First call: creates the process and stores it
     * - Subsequent calls: returns the existing process
     *
     * @return Pair of (Process, OutputStream) or null if su is not available
     */
    private fun getSuProcess(): Pair<Process, java.io.OutputStream>? {
        // If we already have a running process, return it directly
        if (suProcess != null && suOutputStream != null) {
            return Pair(suProcess!!, suOutputStream!!)
        }

        return try {
            // Start the "su" shell — this opens an interactive root shell
            val process = Runtime.getRuntime().exec("su")

            // Get the stream where we write commands (process's stdin)
            val outputStream = process.outputStream

            // Store references so we can reuse them in future calls
            suProcess = process
            suOutputStream = outputStream

            Pair(process, outputStream)
        } catch (e: Exception) {
            // If "su" binary is not found or fails to start
            null
        }
    }

    /**
     * Executes a shell command as root and returns its output.
     *
     * Example usage:
     *   val result = RootUtils.executeSuCommand("ls /data/local/tmp")
     *   // result might be "frida-server\n"
     *
     * @param command The shell command to execute as root
     * @return The command's stdout output as a String, or empty string on failure
     */
    fun executeSuCommand(command: String): String {
        // Safety check: don't even try if root is not available
        if (!isRootAvailable()) return ""

        // Get (or create) the persistent su process
        val suPair = getSuProcess() ?: return ""
        val (process, outputStream) = suPair

        return try {
            // Write the command followed by a newline (like pressing Enter in a terminal)
            outputStream.write("$command\n".toByteArray())
            outputStream.flush() // Make sure the data is actually sent

            // Wait a bit for the command to execute
            // This is a simple approach — more advanced apps use markers instead
            Thread.sleep(500)

            // Read whatever output is available from the process
            val inputStream = process.inputStream
            val available = inputStream.available()

            // Allocate a buffer: use the actual available bytes, or 1024 as minimum
            val buffer = ByteArray(if (available > 0) available else 1024)
            val output = StringBuilder()

            // Keep reading while there's data available
            while (inputStream.available() > 0 && inputStream.read(buffer) != -1) {
                output.append(String(buffer))
            }

            output.toString()
        } catch (e: Exception) {
            // Return empty string if anything goes wrong
            ""
        }
    }

    /**
     * Closes the persistent su process and releases resources.
     *
     * Should be called when the app is closing (e.g., in ViewModel.onCleared())
     * to avoid leaving orphaned root processes running in the background.
     */
    fun closeSuProcess() {
        try {
            suOutputStream?.let {
                // Send "exit" command to cleanly close the shell
                it.write("exit\n".toByteArray())
                it.flush()
                it.close()
            }
            // Force-terminate the process as a fallback
            suProcess?.destroy()
        } catch (e: Exception) {
            // Ignore errors during cleanup
        } finally {
            // Always clear the references so the next call creates a fresh process
            suProcess = null
            suOutputStream = null
        }
    }
}
