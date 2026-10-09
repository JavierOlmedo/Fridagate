package com.hackpuntes.fridagate.utils

/**
 * ProxyTool - The interception proxies Fridagate works with.
 *
 * They differ in where they serve their CA certificate and in how they accept
 * the connections redirected by the iptables mode, which arrive without a proxy
 * request (no CONNECT).
 */
enum class ProxyTool(
    val label: String,
    /** What the tool needs for the iptables (transparent) mode */
    val transparentHint: String
) {
    BURP(
        label = "Burp Suite",
        transparentHint = "Enable 'Support invisible proxying' on the Burp listener."
    ),
    CAIDO(
        label = "Caido",
        transparentHint = "Enable invisible proxying on the Caido listener (--invisible in the CLI)."
    ),
    MITMPROXY(
        label = "mitmproxy",
        transparentHint = "mitmproxy can't take the iptables redirect. Use the System Proxy, " +
            "or mitmproxy's WireGuard mode for apps that ignore it."
    );

    /** URL where the tool serves its CA certificate */
    fun caUrl(ip: String, port: Int): String = when (this) {
        BURP -> "http://$ip:$port/cert"
        CAIDO -> "http://$ip:$port/ca.crt"
        MITMPROXY -> "http://mitm.it/cert/pem"
    }

    /** mitmproxy only answers mitm.it to clients that use it as their proxy */
    val caViaProxy: Boolean
        get() = this == MITMPROXY

    companion object {
        /** The tool saved under [name], or Burp when unknown */
        fun fromName(name: String?): ProxyTool = entries.firstOrNull { it.name == name } ?: BURP
    }
}
