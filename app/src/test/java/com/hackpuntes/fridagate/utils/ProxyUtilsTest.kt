package com.hackpuntes.fridagate.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ProxyUtilsTest {

    @Test
    fun redirectIsActiveWhenOurChainIsHookedAndHasDnatRules() {
        val rules = """
            -P OUTPUT ACCEPT
            -N FRIDAGATE_OUT
            -A OUTPUT -j FRIDAGATE_OUT
            -A FRIDAGATE_OUT -m owner --uid-owner 10123 -j RETURN
            -A FRIDAGATE_OUT -p tcp -m tcp --dport 80 -j DNAT --to-destination 192.168.1.10:8080
        """.trimIndent()
        assertTrue(ProxyUtils.isRedirectActive(rules))
    }

    @Test
    fun redirectIsInactiveWhenTheChainIsNotHookedOrEmpty() {
        assertFalse(ProxyUtils.isRedirectActive("-P OUTPUT ACCEPT\n-N FRIDAGATE_OUT\n-A FRIDAGATE_OUT -p tcp --dport 80 -j DNAT --to-destination 1.2.3.4:8080"))
        assertFalse(ProxyUtils.isRedirectActive("-P OUTPUT ACCEPT\n-N FRIDAGATE_OUT\n-A OUTPUT -j FRIDAGATE_OUT"))
        assertFalse(ProxyUtils.isRedirectActive(""))
    }

    @Test
    fun legacyRulesFromOlderVersionsCountAsActive() {
        assertTrue(ProxyUtils.isRedirectActive("-A OUTPUT -p tcp -m tcp --dport 443 -j DNAT --to-destination 192.168.1.10:8080"))
    }

    @Test
    fun redirectScriptContainsTheExpectedRules() {
        val script = ProxyUtils.buildRedirectScript("192.168.1.10", 8080, 8081)
        assertTrue(script.contains("iptables -w -t nat -A FRIDAGATE_OUT -p tcp --dport 80 -j DNAT --to-destination 192.168.1.10:8080"))
        assertTrue(script.contains("iptables -w -t nat -A FRIDAGATE_OUT -p tcp --dport 443 -j DNAT --to-destination 192.168.1.10:8081"))
        assertTrue(script.contains("iptables -w -t nat -I OUTPUT 1 -j FRIDAGATE_OUT"))
        assertTrue(script.contains("-d 192.168.1.10 -p tcp --dport 8081 -j MASQUERADE"))
        // Never flushes the built-in chains like 1.0.x did
        assertFalse(script.contains("-F OUTPUT"))
        assertFalse(script.contains("-F POSTROUTING"))
    }

    @Test
    fun leakBlockingScriptExcludesTheAppAndBlocksQuicAndIpv6() {
        val script = ProxyUtils.buildLeakBlockingScript(10123)
        assertTrue(script.contains("-m owner --uid-owner 10123 -j RETURN"))
        assertTrue(script.contains("iptables -w -A FRIDAGATE_FILTER -p udp --dport 443 -j REJECT"))
        assertTrue(script.contains("ip6tables -w -A FRIDAGATE_FILTER -p tcp --dport 443 -j REJECT --reject-with tcp-reset"))
    }

    @Test
    fun generatedScriptsAreValidShellSyntax() {
        assumeTrue(File("/bin/sh").canExecute())
        val scripts = listOf(
            ProxyUtils.buildRedirectScript("192.168.1.10", 8080, 8080),
            ProxyUtils.buildLeakBlockingScript(10123),
            ProxyUtils.buildDisableScript(),
            ProxyUtils.buildCaOverlayScript("/apex/com.android.conscrypt/cacerts", "/data/local/tmp/fridagate/9a5ba575.0")
        )
        for (script in scripts) {
            val process = ProcessBuilder("/bin/sh", "-n", "-c", script).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals(output, 0, process.waitFor())
        }
    }

    @Test
    fun parseChildPidsKeepsOnlyChildrenOfTheGivenParents() {
        val ps = """
              PID  PPID
                1     0
              640     1
              641     1
             2001   640
             2002   641
             2003  1500
        """.trimIndent()
        assertEquals(listOf(2001, 2002), ProxyUtils.parseChildPids(ps, setOf(640, 641)))
    }
}
