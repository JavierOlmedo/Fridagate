package com.hackpuntes.fridagate.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

/**
 * ProxyUtils - All logic related to routing Android traffic through Burp Suite.
 *
 * Two ways to redirect traffic:
 *
 * 1. System Proxy:
 *    Sets Android's global HTTP proxy. Only apps that respect it are covered.
 *
 * 2. iptables Transparent Proxy (root, recommended):
 *    NAT rules send every TCP connection to ports 80/443 to Burp, whether the app
 *    respects the proxy setting or not. Because the app doesn't know it is being
 *    proxied, Burp's listener must have "Support invisible proxying" enabled.
 *
 * Certificate installation:
 *    The proxy's CA (Burp, Caido or mitmproxy) is added to the system trust store with an in-memory (tmpfs) overlay,
 *    so /system never has to be remounted read-write. On Android 14+ the store lives
 *    in the Conscrypt APEX and the overlay is bind-mounted into zygote and every running
 *    app. The overlay disappears on reboot: install the CA again after rebooting.
 */
object ProxyUtils {

    /**
     * Outcome of a proxy or certificate operation.
     * [message] explains a failure, or carries extra information on success.
     */
    data class OpResult(val success: Boolean, val message: String = "")

    // -------------------------------------------------------------------------
    // iptables Transparent Proxy (root required)
    // -------------------------------------------------------------------------

    // Our own chains. Fridagate only touches these plus one jump rule per built-in
    // chain, so rules from VPNs, tethering or firewall apps are left alone.
    private const val CHAIN_NAT_OUT = "FRIDAGATE_OUT"
    private const val CHAIN_NAT_POST = "FRIDAGATE_POST"
    private const val CHAIN_FILTER = "FRIDAGATE_FILTER"

    // -w waits for the xtables lock instead of failing while netd is updating rules
    private const val IPT = "iptables -w"
    private const val IP6T = "ip6tables -w"

    /** DNAT rules that Fridagate 1.0.x wrote straight into the built-in nat OUTPUT chain */
    private val LEGACY_DNAT_RULE = Regex("^-A OUTPUT -p tcp (-m tcp )?--dport (80|443) -j DNAT")

    /**
     * Enables the transparent proxy.
     *
     *  - nat FRIDAGATE_OUT: DNAT of TCP 80 -> burpIp:httpPort and TCP 443 -> burpIp:httpsPort
     *  - nat FRIDAGATE_POST: MASQUERADE only for flows going to Burp, so replies come back
     *    even when the original route used another interface
     *  - Fridagate's own traffic (GitHub downloads) is excluded from all of it
     *  - QUIC (UDP 443) is rejected, so apps fall back to TCP, which we do redirect
     *  - Web traffic over IPv6 is rejected, so apps fall back to IPv4: an IPv4 Burp
     *    can't be the DNAT target of an IPv6 connection
     *
     * The last three are best effort: if they fail, the redirect still works and the
     * reason is returned in [OpResult.message].
     *
     * With [targetUid], only that app's traffic is redirected (and only its QUIC/IPv6 is
     * blocked); the rest of the device keeps its normal network. Connections the app
     * delegates to other processes (DownloadManager, Google Play services) are not
     * covered in that mode.
     *
     * @param appUid    Fridagate's own uid, excluded from redirection
     * @param targetUid uid of the only app to redirect, or null for every app
     */
    suspend fun enableIptablesProxy(
        burpIp: String,
        httpPort: Int,
        httpsPort: Int,
        appUid: Int = android.os.Process.myUid(),
        targetUid: Int? = null
    ): OpResult = withContext(Dispatchers.IO) {
        if (!InputValidator.isValidIpv4(burpIp)) {
            return@withContext OpResult(false, "Invalid Burp IP '$burpIp' (iptables needs an IPv4 address such as 192.168.1.10)")
        }
        if (!InputValidator.isValidPort(httpPort) || !InputValidator.isValidPort(httpsPort)) {
            return@withContext OpResult(false, "Invalid Burp port: $httpPort / $httpsPort")
        }

        val redirect = RootUtils.exec(buildRedirectScript(burpIp, httpPort, httpsPort, targetUid))
        if (!redirect.isSuccess) {
            RootUtils.exec(buildDisableScript()) // don't leave half the rules behind
            return@withContext OpResult(false, "iptables error: ${redirect.errorMessage}")
        }

        val leakBlocking = RootUtils.exec(buildLeakBlockingScript(appUid, targetUid))

        if (!isIptablesProxyEnabled()) {
            return@withContext OpResult(false, "Rules were applied but are not active")
        }
        if (!leakBlocking.isSuccess) {
            return@withContext OpResult(
                true,
                "Redirect active, but QUIC/IPv6 blocking or self-exclusion failed: ${leakBlocking.errorMessage}"
            )
        }
        OpResult(true)
    }

    /**
     * Disables the transparent proxy: removes our jump rules and chains, plus the rules
     * left by Fridagate 1.0.x. Nothing else in iptables is touched.
     */
    suspend fun disableIptablesProxy(): OpResult = withContext(Dispatchers.IO) {
        val result = RootUtils.exec(buildDisableScript())
        if (result.exitCode == RootUtils.Result.NO_ROOT || result.exitCode == RootUtils.Result.TIMEOUT) {
            return@withContext OpResult(false, result.errorMessage)
        }
        if (isIptablesProxyEnabled()) {
            OpResult(false, "Redirect rules are still present after cleanup")
        } else {
            OpResult(true)
        }
    }

    /** True if the DNAT redirect to Burp is active (ours, or one left by Fridagate 1.0.x) */
    suspend fun isIptablesProxyEnabled(): Boolean = redirectState().active

    /**
     * What the iptables redirect is doing right now.
     * @param targetUid uid of the only redirected app, or null when every app is redirected
     */
    data class RedirectState(val active: Boolean, val targetUid: Int?)

    /** Reads the live nat rules to tell whether the redirect is on, and for which app */
    suspend fun redirectState(): RedirectState = withContext(Dispatchers.IO) {
        val rules = RootUtils.exec("$IPT -t nat -S")
        if (!rules.isSuccess) {
            RedirectState(active = false, targetUid = null)
        } else {
            RedirectState(isRedirectActive(rules.stdout), redirectTargetUid(rules.stdout))
        }
    }

    /** uid in the owner match of our DNAT rules, or null when they apply to every app */
    internal fun redirectTargetUid(natRules: String): Int? =
        natRules.lines()
            .map { it.trim() }
            .filter { it.startsWith("-A $CHAIN_NAT_OUT ") && it.contains("-j DNAT") }
            .firstNotNullOfOrNull { UID_OWNER.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private val UID_OWNER = Regex("--uid-owner (\\d+)")

    /** Parses "iptables -t nat -S" output */
    internal fun isRedirectActive(natRules: String): Boolean {
        val lines = natRules.lines().map { it.trim() }
        val hooked = lines.any { it == "-A OUTPUT -j $CHAIN_NAT_OUT" }
        val redirects = lines.any { it.startsWith("-A $CHAIN_NAT_OUT ") && it.contains("-j DNAT") }
        val legacy = lines.any { LEGACY_DNAT_RULE.containsMatchIn(it) }
        return (hooked && redirects) || legacy
    }

    /**
     * Core redirect rules. Starts from a clean state and stops at the first error.
     * @param targetUid only redirect this app's connections, or every app when null
     */
    internal fun buildRedirectScript(
        burpIp: String,
        httpPort: Int,
        httpsPort: Int,
        targetUid: Int? = null
    ): String = buildString {
        val owner = ownerMatch(targetUid)
        appendLine(buildDisableScript())
        appendLine("set -e")
        appendLine("$IPT -t nat -N $CHAIN_NAT_OUT")
        appendLine("$IPT -t nat -A $CHAIN_NAT_OUT -p tcp --dport 80$owner -j DNAT --to-destination $burpIp:$httpPort")
        appendLine("$IPT -t nat -A $CHAIN_NAT_OUT -p tcp --dport 443$owner -j DNAT --to-destination $burpIp:$httpsPort")
        appendLine("$IPT -t nat -I OUTPUT 1 -j $CHAIN_NAT_OUT")
        appendLine("$IPT -t nat -N $CHAIN_NAT_POST")
        for (port in linkedSetOf(httpPort, httpsPort)) {
            appendLine("$IPT -t nat -A $CHAIN_NAT_POST -d $burpIp -p tcp --dport $port -j MASQUERADE")
        }
        appendLine("$IPT -t nat -I POSTROUTING 1 -j $CHAIN_NAT_POST")
    }

    /**
     * Best-effort rules that close the usual ways traffic escapes a TCP redirect.
     * Runs every rule even if one fails, and exits non-zero if any failed.
     */
    internal fun buildLeakBlockingScript(appUid: Int, targetUid: Int? = null): String {
        // In per-app mode only the target loses QUIC and IPv6 web traffic
        val owner = ownerMatch(targetUid)
        val rules = listOf(
            // Fridagate's own connections go out untouched (GitHub downloads keep working)
            "$IPT -t nat -I $CHAIN_NAT_OUT 1 -m owner --uid-owner $appUid -j RETURN",
            // QUIC / HTTP3 runs over UDP 443 and would skip the TCP redirect
            "$IPT -N $CHAIN_FILTER",
            "$IPT -A $CHAIN_FILTER -m owner --uid-owner $appUid -j RETURN",
            "$IPT -A $CHAIN_FILTER -p udp --dport 443$owner -j REJECT",
            "$IPT -I OUTPUT 1 -j $CHAIN_FILTER",
            // IPv6 web traffic can't be sent to an IPv4 Burp: reject it so apps retry over IPv4
            "$IP6T -N $CHAIN_FILTER",
            "$IP6T -A $CHAIN_FILTER -m owner --uid-owner $appUid -j RETURN",
            "$IP6T -A $CHAIN_FILTER -p tcp --dport 80$owner -j REJECT --reject-with tcp-reset",
            "$IP6T -A $CHAIN_FILTER -p tcp --dport 443$owner -j REJECT --reject-with tcp-reset",
            "$IP6T -A $CHAIN_FILTER -p udp --dport 443$owner -j REJECT",
            "$IP6T -I OUTPUT 1 -j $CHAIN_FILTER"
        )
        return buildString {
            appendLine("rc=0")
            rules.forEach { appendLine("$it || rc=1") }
            appendLine("exit \$rc")
        }
    }

    /** " -m owner --uid-owner N" to limit a rule to one app, or "" for every app */
    private fun ownerMatch(targetUid: Int?): String =
        if (targetUid == null) "" else " -m owner --uid-owner $targetUid"

    /** Removes everything Fridagate adds. Safe to run when nothing is installed. */
    internal fun buildDisableScript(): String = buildString {
        // Unhook our chains (loop, in case an interrupted run added a jump twice)
        appendLine("while $IPT -t nat -D OUTPUT -j $CHAIN_NAT_OUT 2>/dev/null; do :; done")
        appendLine("while $IPT -t nat -D POSTROUTING -j $CHAIN_NAT_POST 2>/dev/null; do :; done")
        appendLine("while $IPT -D OUTPUT -j $CHAIN_FILTER 2>/dev/null; do :; done")
        appendLine("while $IP6T -D OUTPUT -j $CHAIN_FILTER 2>/dev/null; do :; done")
        // Empty and delete our chains
        appendLine("$IPT -t nat -F $CHAIN_NAT_OUT 2>/dev/null; $IPT -t nat -X $CHAIN_NAT_OUT 2>/dev/null")
        appendLine("$IPT -t nat -F $CHAIN_NAT_POST 2>/dev/null; $IPT -t nat -X $CHAIN_NAT_POST 2>/dev/null")
        appendLine("$IPT -F $CHAIN_FILTER 2>/dev/null; $IPT -X $CHAIN_FILTER 2>/dev/null")
        appendLine("$IP6T -F $CHAIN_FILTER 2>/dev/null; $IP6T -X $CHAIN_FILTER 2>/dev/null")
        // Rules left by Fridagate 1.0.x, which wrote straight into the built-in chains
        appendLine(
            "$IPT -t nat -S OUTPUT 2>/dev/null | grep -E '^-A OUTPUT -p tcp (-m tcp )?--dport (80|443) -j DNAT' " +
                "| sed 's/^-A /-D /' | while read -r rule; do $IPT -t nat \$rule; done"
        )
        appendLine("while $IPT -t nat -D POSTROUTING -j MASQUERADE 2>/dev/null; do :; done")
        appendLine("true")
    }

    // -------------------------------------------------------------------------
    // System Proxy
    // -------------------------------------------------------------------------

    /**
     * Sets the Android global HTTP proxy (settings put global http_proxy IP:PORT).
     * Apps that ignore the system proxy are not covered; use iptables for those.
     */
    suspend fun setSystemProxy(ip: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        if (!InputValidator.isValidIpv4(ip) || !InputValidator.isValidPort(port)) return@withContext false
        RootUtils.exec("settings put global http_proxy ${ShellUtils.quote("$ip:$port")}").isSuccess
    }

    /**
     * Removes the Android global HTTP proxy.
     * Both ":0" and delete are used because Android versions behave differently.
     */
    suspend fun clearSystemProxy(): Boolean = withContext(Dispatchers.IO) {
        RootUtils.exec(
            "settings put global http_proxy :0; settings delete global http_proxy; " +
                // global_http_proxy persists across reboots on Android 8+; clear it too
                "settings put global global_http_proxy :0; settings delete global global_http_proxy"
        )
        getSystemProxy() == null
    }

    /** The current system proxy as "IP:PORT", or null if none is set */
    suspend fun getSystemProxy(): String? = withContext(Dispatchers.IO) {
        val result = RootUtils.exec("settings get global http_proxy")
        val value = result.stdout.trim()
        if (!result.isSuccess || value.isEmpty() || value == "null" || value == ":0") null else value
    }

    // -------------------------------------------------------------------------
    // Connectivity check
    // -------------------------------------------------------------------------

    /**
     * True if something accepts TCP connections at [ip]:[port].
     * Kept generic on purpose, so Caido or mitmproxy listeners also count.
     */
    suspend fun isBurpReachable(ip: String, port: Int, timeoutMs: Int = 2000): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ip, port), timeoutMs)
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    // -------------------------------------------------------------------------
    // Certificate installation
    // -------------------------------------------------------------------------

    private const val CERT_STAGING_DIR = "/data/local/tmp/fridagate"
    private const val SYSTEM_CA_DIR = "/system/etc/security/cacerts"
    private const val APEX_CA_DIR = "/apex/com.android.conscrypt/cacerts"

    /** Outcome of a CA installation; [hash] names the staged file (<hash>.0) on success */
    data class CaInstallResult(val success: Boolean, val message: String, val hash: String? = null)

    /**
     * Installs the proxy's CA into the system trust store (Android 7 to 14+).
     *
     * Steps:
     *  1. Download the CA from the proxy (see [ProxyTool.caUrl]) inside the app,
     *     compute its Android file name (subject_hash_old) and stage it as PEM in
     *     /data/local/tmp/fridagate/<hash>.0, which survives reboots.
     *  2. Apply the staged file with [applyStagedCertificate].
     *  3. Check that what apps see is exactly the certificate the proxy serves.
     *
     * Not persistent by itself: the overlay is gone after a reboot, unless the boot
     * receiver applies the staged file again. Target apps must be restarted.
     */
    suspend fun installCaCertificate(tool: ProxyTool, ip: String, port: Int): CaInstallResult =
        withContext(Dispatchers.IO) {
            if (!InputValidator.isValidIpv4(ip) || !InputValidator.isValidPort(port)) {
                return@withContext CaInstallResult(false, "Invalid proxy address $ip:$port")
            }
            val cert = fetchCaCertificate(tool, ip, port)
                ?: return@withContext CaInstallResult(
                    false,
                    "Could not download the CA from ${tool.caUrl(ip, port)}. Is ${tool.label} listening on $ip:$port?"
                )
            val hash = stageCertificate(cert)
                ?: return@withContext CaInstallResult(false, "Could not stage the certificate in $CERT_STAGING_DIR")

            val applied = applyStagedCertificate(hash)
            if (!applied.success) return@withContext CaInstallResult(false, applied.message, hash)

            val installed = readInstalledCertificate(hash, isApexStore())
            if (installed == null || !installed.encoded.contentEquals(cert.encoded)) {
                return@withContext CaInstallResult(false, "The install ran but $hash.0 is not the CA ${tool.label} serves", hash)
            }
            CaInstallResult(true, applied.message, hash)
        }

    /**
     * Puts the staged <hash>.0 into the trust store apps read. Needs no network,
     * so the boot receiver uses it to bring the CA back after a reboot.
     *
     *  1. Copy the current trusted CAs plus the staged one into a tmpfs mounted over
     *     /system/etc/security/cacerts, in the global mount namespace.
     *  2. Android 14+: bind that directory over the Conscrypt APEX store inside zygote
     *     (apps launched from now on) and inside every running app.
     *  3. Read the file back the way apps see it and compare it with the staged one.
     */
    suspend fun applyStagedCertificate(hash: String): OpResult = withContext(Dispatchers.IO) {
        if (!Regex("^[0-9a-f]{8}$").matches(hash)) {
            return@withContext OpResult(false, "Invalid certificate hash '$hash'")
        }
        val staged = "$CERT_STAGING_DIR/$hash.0"
        val stagedCert = RootUtils.exec("cat $staged").let { result ->
            if (!result.isSuccess) null else runCatching { CertUtils.parse(result.stdout.toByteArray()) }.getOrNull()
        } ?: return@withContext OpResult(false, "No staged certificate at $staged. Install the CA again.")

        // Android 14+ reads CAs from the Conscrypt APEX instead of /system
        val apexStore = isApexStore()
        val namespaceNote = if (RootUtils.isGlobalMountNamespace || RootUtils.exec("command -v nsenter").isSuccess) {
            ""
        } else {
            " Warning: su has no --mount-master and nsenter is missing, other apps may not see the CA."
        }

        val overlay = RootUtils.exec(
            inGlobalMountNamespace(buildCaOverlayScript(if (apexStore) APEX_CA_DIR else SYSTEM_CA_DIR, staged)),
            timeoutMs = 60_000
        )
        if (!overlay.isSuccess) {
            return@withContext OpResult(false, "Could not build the CA overlay: ${overlay.errorMessage}")
        }

        if (apexStore) {
            val zygotes = findPids("pidof zygote zygote64")
            if (zygotes.isEmpty()) {
                return@withContext OpResult(false, "zygote not found, can't inject into the APEX store")
            }
            val zygoteBind = RootUtils.exec(zygotes.joinToString("\n") { bindCaStoreInto(it) })
            if (!zygoteBind.isSuccess) {
                return@withContext OpResult(false, "Could not inject into zygote: ${zygoteBind.errorMessage}")
            }
            // Apps already running: best effort, zygote already covers every new launch
            val apps = parseChildPids(RootUtils.exec("ps -A -o PID,PPID").stdout, zygotes.toSet())
            if (apps.isNotEmpty()) {
                RootUtils.exec(
                    apps.joinToString("\n", postfix = "\nwait") { "${bindCaStoreInto(it)} 2>/dev/null &" },
                    timeoutMs = 60_000
                )
            }
        }

        val installed = readInstalledCertificate(hash, apexStore)
        val store = if (apexStore) "Conscrypt APEX store (Android 14+)" else "system store"
        if (installed == null || !installed.encoded.contentEquals(stagedCert.encoded)) {
            return@withContext OpResult(false, "The install ran but $hash.0 is not visible in the $store.$namespaceNote")
        }
        OpResult(true, "CA installed as $hash.0 in the $store.$namespaceNote")
    }

    /**
     * Whether the CA the proxy is serving right now is trusted by the system.
     * Compares the actual certificate, so a CA from an older proxy install doesn't count.
     *
     * @return true / false, or null if the proxy can't be reached to fetch its current CA
     */
    suspend fun isCaCertInstalled(tool: ProxyTool, ip: String, port: Int): Boolean? = withContext(Dispatchers.IO) {
        if (!InputValidator.isValidIpv4(ip) || !InputValidator.isValidPort(port)) return@withContext null
        val cert = fetchCaCertificate(tool, ip, port) ?: return@withContext null
        val installed = readInstalledCertificate(CertUtils.subjectHashOld(cert), isApexStore())
            ?: return@withContext false
        installed.encoded.contentEquals(cert.encoded)
    }

    /** True on Android 14+, where apps read CAs from the Conscrypt APEX */
    private fun isApexStore(): Boolean = RootUtils.exec("[ -d $APEX_CA_DIR ]").isSuccess

    /** Writes [cert] as PEM to /data/local/tmp/fridagate/<hash>.0 and returns the hash, or null */
    private fun stageCertificate(cert: X509Certificate): String? {
        val hash = CertUtils.subjectHashOld(cert)
        val staged = "$CERT_STAGING_DIR/$hash.0"
        val result = RootUtils.exec(
            "mkdir -p $CERT_STAGING_DIR && printf '%s' ${ShellUtils.quote(CertUtils.toPem(cert))} > $staged && chmod 644 $staged"
        )
        return if (result.isSuccess) hash else null
    }

    /**
     * Downloads the proxy's CA. Burp and Caido serve it on their listener; mitmproxy only
     * answers http://mitm.it to clients that use it as their proxy.
     * Null if unreachable or not a certificate.
     */
    private fun fetchCaCertificate(tool: ProxyTool, ip: String, port: Int): X509Certificate? {
        return try {
            val proxy = if (tool.caViaProxy) Proxy(Proxy.Type.HTTP, InetSocketAddress(ip, port)) else Proxy.NO_PROXY
            val client = OkHttpClient.Builder()
                .proxy(proxy) // never the system proxy, which may point at the proxy itself
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(tool.caUrl(ip, port)).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else CertUtils.parse(response.body.bytes())
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Shell script that builds the tmpfs overlay. Runs in the global mount namespace.
     *
     * @param sourceDir  store apps currently trust (copied first, the mount hides it)
     * @param stagedCert Burp's CA file, already named <hash>.0
     */
    internal fun buildCaOverlayScript(sourceDir: String, stagedCert: String): String {
        val copy = "$CERT_STAGING_DIR/cacerts"
        return listOf(
            "set -e",
            "rm -rf $copy",
            "mkdir -p -m 700 $copy",
            "cp $sourceDir/* $copy/",
            "cp ${ShellUtils.quote(stagedCert)} $copy/",
            // Mounted once per boot; running again only refreshes the files
            "grep -q ' $SYSTEM_CA_DIR tmpfs ' /proc/mounts || mount -t tmpfs tmpfs $SYSTEM_CA_DIR",
            "cp $copy/* $SYSTEM_CA_DIR/",
            "chown root:root $SYSTEM_CA_DIR $SYSTEM_CA_DIR/*",
            "chmod 755 $SYSTEM_CA_DIR",
            "chmod 644 $SYSTEM_CA_DIR/*",
            "chcon u:object_r:system_file:s0 $SYSTEM_CA_DIR $SYSTEM_CA_DIR/*",
            "rm -rf $copy"
        ).joinToString("\n")
    }

    /** Bind-mounts the overlay over the APEX store inside [pid]'s mount namespace, once */
    private fun bindCaStoreInto(pid: Int): String {
        val inner = "grep -q ' $APEX_CA_DIR tmpfs ' /proc/self/mounts || mount --bind $SYSTEM_CA_DIR $APEX_CA_DIR"
        return "nsenter --mount=/proc/$pid/ns/mnt -- sh -c ${ShellUtils.quote(inner)}"
    }

    /** Reads <hash>.0 from the store as apps see it. Null if it is missing or invalid. */
    private fun readInstalledCertificate(hash: String, apexStore: Boolean): X509Certificate? {
        val command = if (apexStore) {
            val zygote = findPids("pidof zygote64 zygote").firstOrNull() ?: return null
            "nsenter --mount=/proc/$zygote/ns/mnt -- cat $APEX_CA_DIR/$hash.0"
        } else {
            inGlobalMountNamespace("cat $SYSTEM_CA_DIR/$hash.0")
        }
        val result = RootUtils.exec(command)
        if (!result.isSuccess) return null
        return try {
            CertUtils.parse(result.stdout.toByteArray())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Runs [script] in init's (global) mount namespace, so its mounts are visible to every
     * app. Not needed when the root shell already is global (su --mount-master).
     */
    private fun inGlobalMountNamespace(script: String): String {
        if (RootUtils.isGlobalMountNamespace) return script
        val quoted = ShellUtils.quote(script)
        return "if command -v nsenter >/dev/null 2>&1; then nsenter --mount=/proc/1/ns/mnt -- sh -c $quoted; " +
            "else sh -c $quoted; fi"
    }

    /** PIDs printed by [command] (e.g. pidof) */
    private fun findPids(command: String): List<Int> =
        RootUtils.exec(command).stdout.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }

    /** PIDs whose parent is in [parents], from "ps -A -o PID,PPID" output */
    internal fun parseChildPids(psOutput: String, parents: Set<Int>): List<Int> =
        psOutput.lines().mapNotNull { line ->
            val columns = line.trim().split(Regex("\\s+"))
            val pid = columns.getOrNull(0)?.toIntOrNull()
            val parent = columns.getOrNull(1)?.toIntOrNull()
            if (pid != null && parent != null && parent in parents) pid else null
        }
}
