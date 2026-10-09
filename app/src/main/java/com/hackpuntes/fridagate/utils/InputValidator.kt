package com.hackpuntes.fridagate.utils

/**
 * InputValidator - Checks user input before it reaches root commands or iptables rules.
 *
 * Quoting (ShellUtils.quote) already makes injection impossible. Validation is about
 * giving a clear error up front instead of a cryptic iptables or am failure later.
 */
object InputValidator {

    private val IPV4 = Regex(
        "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"
    )

    // Android package names: two or more dot-separated segments, each starting with a letter
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** True for a dotted IPv4 literal such as 192.168.1.10 (iptables DNAT needs a literal IP) */
    fun isValidIpv4(ip: String): Boolean = IPV4.matches(ip)

    /** True for a TCP port in 1..65535 */
    fun isValidPort(port: Int): Boolean = port in 1..65535

    /** True for a well-formed Android package name such as com.target.app */
    fun isValidPackageName(packageName: String): Boolean = PACKAGE_NAME.matches(packageName)
}
