package com.hackpuntes.fridagate.utils

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun formatPrintsOneLinePerItemUnderItsSection() {
        val sections = listOf(
            Diagnostics.Section("App", listOf(Diagnostics.Item("Version", "1.0.4 (10004, release)"))),
            Diagnostics.Section(
                "Root",
                listOf(Diagnostics.Item("Manager", "Magisk 27.0 (27000)"), Diagnostics.Item("SELinux", "Enforcing"))
            )
        )
        assertEquals(
            "Fridagate diagnostics\n\n[App]\nVersion: 1.0.4 (10004, release)\n\n" +
                "[Root]\nManager: Magisk 27.0 (27000)\nSELinux: Enforcing",
            Diagnostics.format(sections)
        )
    }

    @Test
    fun parseProbeKeepsKnownKeysAndIgnoresTheRest() {
        val output = """
            manager=Magisk 27.0 (27000)
            selinux=Enforcing
            nsenter=
            iptables=iptables v1.8.7 (legacy)
            unknown=value
            a warning some tool printed
        """.trimIndent()
        assertEquals(
            mapOf(
                "manager" to "Magisk 27.0 (27000)",
                "selinux" to "Enforcing",
                "nsenter" to "",
                "iptables" to "iptables v1.8.7 (legacy)"
            ),
            Diagnostics.parseProbe(output)
        )
    }

    @Test
    fun parseProbeKeepsEqualsSignsInsideValues() {
        assertEquals(mapOf("manager" to "a=b"), Diagnostics.parseProbe("manager=a=b"))
    }

    @Test
    fun probeScriptPrintsEveryKeyAndDetectsMagisk() {
        assumeTrue(File("/bin/sh").canExecute())
        // A fake magisk first in PATH, so the real su is never run
        val bin = tmp.newFolder("bin")
        File(bin, "magisk").apply {
            writeText("#!/bin/sh\nif [ \"\$1\" = -V ]; then echo 27000; else echo 27.0:MAGISK:R; fi\n")
            setExecutable(true)
        }
        val process = ProcessBuilder("/bin/sh", "-c", Diagnostics.buildProbeScript())
            .redirectInput(File("/dev/null"))
            .apply { environment()["PATH"] = "${bin.path}:${System.getenv("PATH")}" }
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(output, 0, process.waitFor())

        val probe = Diagnostics.parseProbe(output)
        assertEquals(setOf("manager", "selinux", "nsenter", "iptables", "ip6tables"), probe.keys)
        assertEquals("Magisk 27.0 (27000)", probe["manager"])
    }
}
