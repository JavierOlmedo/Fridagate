package com.hackpuntes.fridagate.utils

import android.os.Build
import com.hackpuntes.fridagate.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Diagnostics - Everything that decides whether Fridagate works on a device, in one
 * report: root, SELinux, iptables, the CA store, frida-server and the proxy.
 *
 * The report is meant to be pasted into a GitHub issue, so it never includes what
 * identifies an engagement: no proxy address and no target app.
 */
object Diagnostics {

    data class Item(val label: String, val value: String)

    data class Section(val title: String, val items: List<Item>)

    /** What the user saved in the app; the caller reads it from AppPreferences */
    data class Saved(
        val tool: ProxyTool,
        val proxyIp: String,
        val proxyPort: Int,
        val caHash: String,
        val caReinstallOnBoot: Boolean,
        val server: FridaServerConfig
    )

    // Keys printed by the probe script, see buildProbeScript()
    private val PROBE_KEYS = setOf("manager", "selinux", "nsenter", "iptables", "ip6tables")

    /** Runs every check. Takes a few seconds: the proxy check alone may wait 2 s. */
    suspend fun collect(saved: Saved): List<Section> = withContext(Dispatchers.IO) {
        val sections = mutableListOf(appSection(), deviceSection())
        if (!RootUtils.isRootAvailable()) {
            sections += Section("Root", listOf(Item("Root access", "not granted, the other checks need it")))
            return@withContext sections
        }
        val probe = parseProbe(RootUtils.exec(buildProbeScript()).stdout)
        sections += rootSection(probe)
        sections += fridaSection(saved.server)
        sections += proxySection(saved, probe)
        sections += caSection(saved)
        sections
    }

    /** Plain text report, one "Label: value" line per item */
    fun format(sections: List<Section>): String = buildString {
        append("Fridagate diagnostics")
        sections.forEach { section ->
            append("\n\n[").append(section.title).append("]")
            section.items.forEach { append("\n").append(it.label).append(": ").append(it.value) }
        }
    }

    private fun appSection() = Section(
        "App",
        listOf(Item("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})"))
    )

    private fun deviceSection() = Section(
        "Device",
        listOf(
            Item("Model", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"),
            Item("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}"),
            Item("ABIs", Build.SUPPORTED_ABIS.joinToString(", ")),
            Item("Frida arch", FridaUtils.getDeviceArchitecture()),
            Item("Kernel", System.getProperty("os.version") ?: "unknown")
        )
    )

    private fun rootSection(probe: Map<String, String>): Section {
        val nsenter = probe["nsenter"].orEmpty()
        // Mounts made outside the global namespace are only seen by Fridagate itself
        val namespace = when {
            RootUtils.isGlobalMountNamespace -> "global (su --mount-master)"
            nsenter.isNotEmpty() -> "isolated, nsenter reaches the global one"
            else -> "isolated and no nsenter, other apps may not see the CA"
        }
        return Section(
            "Root",
            listOf(
                Item("Manager", probe["manager"].orFallback("unknown")),
                Item("Mount namespace", namespace),
                Item("nsenter", nsenter.ifEmpty { "missing" }),
                Item("SELinux", probe["selinux"].orFallback("unknown"))
            )
        )
    }

    private suspend fun fridaSection(server: FridaServerConfig): Section {
        val installed = FridaUtils.isFridaServerInstalled(server)
        val inject = if (FridaInjectUtils.isFridaInjectInstalled()) {
            FridaInjectUtils.getInstalledVersion() ?: "installed, unknown version"
        } else {
            "not installed"
        }
        return Section(
            "Frida",
            listOf(
                Item("frida-server", if (installed) FridaUtils.getInstalledFridaVersion() ?: "unknown version" else "not installed"),
                Item("Binary", server.binaryPath),
                Item("Port", server.port.toString()),
                Item("Running", yesNo(FridaUtils.isFridaServerRunning(server))),
                Item("frida-inject", inject)
            )
        )
    }

    private suspend fun proxySection(saved: Saved, probe: Map<String, String>): Section {
        val redirect = ProxyUtils.redirectState()
        val systemProxy = ProxyUtils.getSystemProxy()
        return Section(
            "Proxy",
            listOf(
                Item("Tool", saved.tool.label),
                Item("Reachable", yesNo(ProxyUtils.isBurpReachable(saved.proxyIp, saved.proxyPort))),
                Item(
                    "iptables redirect",
                    when {
                        !redirect.active -> "off"
                        redirect.targetUid == null -> "on, every app"
                        else -> "on, one app (uid ${redirect.targetUid})"
                    }
                ),
                Item(
                    "System proxy",
                    when (systemProxy) {
                        null -> "not set"
                        "${saved.proxyIp}:${saved.proxyPort}" -> "set to the saved proxy"
                        else -> "set to another address"
                    }
                ),
                Item("iptables", probe["iptables"].orFallback("missing")),
                Item("ip6tables", probe["ip6tables"].orFallback("missing"))
            )
        )
    }

    private suspend fun caSection(saved: Saved): Section {
        val store = if (ProxyUtils.isApexStore()) "Conscrypt APEX (Android 14+)" else "/system/etc/security/cacerts"
        val items = mutableListOf(Item("Trust store", store))
        if (saved.caHash.isEmpty()) {
            items += Item("Installed CA", "none yet")
        } else {
            items += Item("Installed CA", "${saved.caHash}.0")
            items += Item("Trusted now", yesNo(ProxyUtils.isStagedCertificateActive(saved.caHash)))
        }
        items += Item("Install at boot", if (saved.caReinstallOnBoot) "on" else "off")
        return Section("CA certificate", items)
    }

    /**
     * Gets the system facts in one root shell round trip. Prints one key=value line
     * per key in PROBE_KEYS, with an empty value when a tool is missing.
     */
    internal fun buildProbeScript(): String = listOf(
        "if command -v magisk >/dev/null 2>&1; then",
        "  manager=\"Magisk \$(magisk -v 2>/dev/null | cut -d: -f1) (\$(magisk -V 2>/dev/null))\"",
        "elif [ -e /data/adb/ksud ]; then",
        "  manager=\"KernelSU \$(/data/adb/ksud -V 2>/dev/null)\"",
        "elif [ -e /data/adb/apd ]; then",
        "  manager=\"APatch \$(/data/adb/apd -V 2>/dev/null)\"",
        "else",
        "  manager=\"\$(su -v 2>/dev/null)\"",
        "fi",
        "echo \"manager=\$manager\"",
        "echo \"selinux=\$(getenforce 2>/dev/null)\"",
        "echo \"nsenter=\$(command -v nsenter 2>/dev/null)\"",
        "echo \"iptables=\$(iptables --version 2>/dev/null | head -n 1)\"",
        "echo \"ip6tables=\$(ip6tables --version 2>/dev/null | head -n 1)\""
    ).joinToString("\n")

    /** Parses the probe script output; unknown keys and stray lines are ignored */
    internal fun parseProbe(output: String): Map<String, String> =
        output.lines()
            .mapNotNull { line ->
                val key = line.substringBefore('=', missingDelimiterValue = "")
                if (key in PROBE_KEYS) key to line.substringAfter('=').trim() else null
            }
            .toMap()

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    private fun String?.orFallback(fallback: String) = if (isNullOrBlank()) fallback else this
}
