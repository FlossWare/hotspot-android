// Updates
internal fun buildConfig(
    socksHost: String,
    socksPort: Int,
    ipv6Enabled: Boolean = false,
): String {
    val mtu = if (ipv6Enabled) IPV6_MIN_MTU else IPV4_DEFAULT_MTU
    return """
        tunnel:
          mtu: $mtu
        socks5:
          port: $socksPort
          address: $socksHost
        dns:
          address: $socksHost
          port: 5353
        mapdns:
          address: $DNS_ADDRESS
          port: 53
          network: 100.64.0.0
          netmask: 255.192.0.0
          cache-size: 10000
        misc:
          log-level: warn
          connect-timeout: 5000
          read-write-timeout: 60000
          limit-nofile: 65535
    """.trimIndent()
}
