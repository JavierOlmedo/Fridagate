package com.hackpuntes.fridagate.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Runs the generated iptables scripts against the machine's real iptables, inside a
 * throwaway network namespace (unshare -n): the host's own rules are never touched.
 * Needs Linux, root (or passwordless sudo on CI), unshare and iptables with the
 * nat/owner/REJECT extensions; skipped otherwise (e.g. on a normal developer machine).
 */
class IptablesNetnsTest {

    /** How to become root: nothing when already root, sudo -n on CI, null when neither works */
    private val rootPrefix: List<String>? by lazy {
        when {
            runQuietly(listOf("id", "-u")).trim() == "0" -> emptyList()
            System.getenv("CI") == "true" && runQuietly(listOf("sudo", "-n", "true"), exitCode = true) == "0" ->
                listOf("sudo", "-n")
            else -> null
        }
    }

    @Before
    fun requireIsolatedIptables() {
        assumeTrue(File("/bin/sh").canExecute())
        assumeTrue("needs root or passwordless sudo on CI", rootPrefix != null)
        val probe = """
            set -e
            iptables -w -t nat -N PROBE
            iptables -w -t nat -A PROBE -m owner --uid-owner 1000 -j RETURN
            iptables -w -t nat -A PROBE -p tcp --dport 80 -j DNAT --to-destination 10.0.0.1:8080
            iptables -w -t nat -A PROBE -d 10.0.0.1 -p tcp --dport 8080 -j MASQUERADE
            iptables -w -N PROBE
            iptables -w -A PROBE -p udp --dport 443 -j REJECT
            ip6tables -w -N PROBE
            ip6tables -w -A PROBE -p tcp --dport 443 -j REJECT --reject-with tcp-reset
        """.trimIndent()
        assumeTrue("needs root, unshare and iptables", runIsolated(probe).first == 0)
    }

    @Test
    fun enableThenDisableLeavesOtherRulesUntouched() {
        val output = runIsolatedOrFail(
            """
            # Rules that belong to someone else (tethering, a VPN or firewall app)
            iptables -w -t nat -A POSTROUTING -o wlan0 -j MASQUERADE
            iptables -w -N OTHER_APP
            iptables -w -A OUTPUT -j OTHER_APP
            ${sh(ProxyUtils.buildRedirectScript("192.168.1.10", 8080, 8080))} || echo REDIRECT_FAILED
            ${sh(ProxyUtils.buildLeakBlockingScript(10123))} || echo LEAK_BLOCKING_FAILED
            echo '### ENABLED'
            iptables -w -t nat -S
            iptables -w -S
            ip6tables -w -S
            ${sh(ProxyUtils.buildDisableScript())}
            echo '### DISABLED'
            iptables -w -t nat -S
            iptables -w -S
            ip6tables -w -S
            """
        )
        val enabled = output.substringAfter("### ENABLED").substringBefore("### DISABLED")
        val disabled = output.substringAfter("### DISABLED")

        assertFalse(output, output.contains("REDIRECT_FAILED"))
        assertFalse(output, output.contains("LEAK_BLOCKING_FAILED"))
        assertTrue(enabled, ProxyUtils.isRedirectActive(enabled))
        assertTrue(enabled, enabled.contains("-A OUTPUT -j FRIDAGATE_FILTER"))
        assertTrue(enabled, enabled.contains("--uid-owner 10123"))

        assertFalse(disabled, disabled.contains("FRIDAGATE"))
        assertFalse(disabled, ProxyUtils.isRedirectActive(disabled))
        assertTrue(disabled, disabled.contains("-A POSTROUTING -o wlan0 -j MASQUERADE"))
        assertTrue(disabled, disabled.contains("-A OUTPUT -j OTHER_APP"))
    }

    @Test
    fun enablingTwiceDoesNotDuplicateRules() {
        val output = runIsolatedOrFail(
            """
            ${sh(ProxyUtils.buildRedirectScript("192.168.1.10", 8080, 8081))}
            ${sh(ProxyUtils.buildLeakBlockingScript(10123))}
            ${sh(ProxyUtils.buildRedirectScript("192.168.1.20", 8080, 8081))}
            ${sh(ProxyUtils.buildLeakBlockingScript(10123))}
            iptables -w -t nat -S
            iptables -w -S
            ip6tables -w -S
            """
        )
        assertEquals(output, 1, output.lines().count { it == "-A OUTPUT -j FRIDAGATE_OUT" })
        assertEquals(output, 1, output.lines().count { it == "-A POSTROUTING -j FRIDAGATE_POST" })
        assertEquals(output, 2, output.lines().count { it == "-A OUTPUT -j FRIDAGATE_FILTER" }) // IPv4 + IPv6
        assertFalse(output, output.contains("192.168.1.10"))
        assertTrue(output, output.contains("--to-destination 192.168.1.20:8081"))
    }

    @Test
    fun perAppModeIsAcceptedAndReadBack() {
        val output = runIsolatedOrFail(
            """
            ${sh(ProxyUtils.buildRedirectScript("192.168.1.10", 8080, 8080, targetUid = 10200))} || echo REDIRECT_FAILED
            ${sh(ProxyUtils.buildLeakBlockingScript(10123, targetUid = 10200))} || echo LEAK_BLOCKING_FAILED
            iptables -w -t nat -S
            iptables -w -S
            ip6tables -w -S
            """
        )
        assertFalse(output, output.contains("FAILED"))
        assertTrue(output, ProxyUtils.isRedirectActive(output))
        assertEquals(output, 10200, ProxyUtils.redirectTargetUid(output))
        assertTrue(output, output.lines().any { it.contains("--dport 443") && it.contains("--uid-owner 10200") && it.contains("REJECT") })
    }

    @Test
    fun disableRemovesRulesLeftByOlderVersions() {
        val output = runIsolatedOrFail(
            """
            iptables -w -t nat -A OUTPUT -p tcp --dport 80 -j DNAT --to-destination 192.168.1.10:8080
            iptables -w -t nat -A OUTPUT -p tcp --dport 443 -j DNAT --to-destination 192.168.1.10:8080
            iptables -w -t nat -A POSTROUTING -j MASQUERADE
            echo '### LEGACY'
            iptables -w -t nat -S
            ${sh(ProxyUtils.buildDisableScript())}
            echo '### CLEANED'
            iptables -w -t nat -S
            """
        )
        val legacy = output.substringAfter("### LEGACY").substringBefore("### CLEANED")
        val cleaned = output.substringAfter("### CLEANED")
        assertTrue(legacy, ProxyUtils.isRedirectActive(legacy))
        assertFalse(cleaned, ProxyUtils.isRedirectActive(cleaned))
        assertFalse(cleaned, cleaned.contains("DNAT"))
        assertFalse(cleaned, cleaned.contains("MASQUERADE"))
    }

    /** "sh -c '<script>'", so a script's own exit or set -e stays inside it */
    private fun sh(script: String) = "sh -c ${ShellUtils.quote(script)}"

    private fun runIsolated(script: String): Pair<Int, String> = try {
        val command = rootPrefix.orEmpty() + listOf("unshare", "-n", "/bin/sh", "-c", script)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor() to output
    } catch (e: IOException) {
        -1 to e.toString()
    }

    /** Output of [command], or its exit code when [exitCode] is true. "" if it can't run. */
    private fun runQuietly(command: List<String>, exitCode: Boolean = false): String = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val code = process.waitFor()
        if (exitCode) code.toString() else output
    } catch (e: IOException) {
        ""
    }

    private fun runIsolatedOrFail(script: String): String {
        val (code, output) = runIsolated(script.trimIndent())
        assertEquals(output, 0, code)
        return output
    }
}
