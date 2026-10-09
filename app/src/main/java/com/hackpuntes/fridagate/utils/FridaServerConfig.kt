package com.hackpuntes.fridagate.utils

import java.security.SecureRandom
import java.util.Random

/**
 * FridaServerConfig - Name and port of the frida-server binary.
 *
 * Many apps look for Frida by scanning processes for "frida-server" or probing the
 * default port 27042. Installing the binary under another name and listening on
 * another port gets past those basic checks.
 *
 * @param name Binary name in /data/local/tmp, which is also the process name
 * @param port Port frida-server listens on (127.0.0.1)
 */
data class FridaServerConfig(
    val name: String = DEFAULT_NAME,
    val port: Int = DEFAULT_PORT
) {
    /** Where the binary is installed */
    val binaryPath: String get() = "$INSTALL_DIR/$name"

    val isDefaultPort: Boolean get() = port == DEFAULT_PORT

    /**
     * Arguments that make frida-server listen on [port], unless the user's own flags
     * already choose a listen address (-l / --listen).
     */
    fun listenArgs(userArgs: List<String>): List<String> {
        val userSetsAddress = userArgs.any { it == "-l" || it.startsWith("--listen") || (it.startsWith("-l") && it.length > 2) }
        return if (isDefaultPort || userSetsAddress) emptyList() else listOf("-l", "127.0.0.1:$port")
    }

    companion object {
        const val DEFAULT_NAME = "frida-server"
        const val DEFAULT_PORT = 27042
        const val INSTALL_DIR = "/data/local/tmp"

        // Up to 15 characters: longer process names are cut, and pidof could miss them
        private val VALID_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,14}$")

        // Files Fridagate already keeps in /data/local/tmp
        private val RESERVED = setOf("frida-inject", "frida-version.txt", "fridagate")

        fun isValidName(name: String): Boolean = VALID_NAME.matches(name) && name !in RESERVED

        /** A random name that doesn't say "frida", e.g. "k3v9qz7a" */
        fun randomName(random: Random = SecureRandom()): String {
            val letters = "abcdefghijklmnopqrstuvwxyz"
            val alphabet = letters + "0123456789"
            return buildString {
                append(letters[random.nextInt(letters.length)])
                repeat(7) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
        }
    }
}
