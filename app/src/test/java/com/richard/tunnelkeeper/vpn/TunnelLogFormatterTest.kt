package com.richard.tunnelkeeper.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelLogFormatterTest {
    @Test
    fun `formats successful IPv4 split tunnel details`() {
        val lines = TunnelLogFormatter.lines(
            diagnostics(
                routes = listOf(NetworkPrefix("198.51.100.0", 24)),
                dnsServers = listOf("203.0.113.53"),
                splitIncludes = listOf("198.51.100.0/24"),
                allowIpv6Bypass = true,
            ),
        )

        assertTrue(lines.contains("VPN 网关：203.0.113.10"))
        assertTrue(lines.contains("VPN 地址：192.0.2.43/24"))
        assertTrue(lines.contains("MTU：1334"))
        assertTrue(lines.contains("DNS：203.0.113.53"))
        assertTrue(lines.contains("服务端包含路由：198.51.100.0/24"))
        assertTrue(lines.contains("服务端排除路由：未下发"))
        assertTrue(lines.contains("提交的 VPN 路由：198.51.100.0/24"))
        assertEquals("IPv6：使用默认网络（IPv4 分流）", lines.last())
    }

    @Test
    fun `bounds route diagnostics and removes control characters`() {
        val includes = (1..14).map { index -> "10.0.$index.0/24\n" }
        val lines = TunnelLogFormatter.lines(
            diagnostics(
                routes = emptyList(),
                dnsServers = emptyList(),
                splitIncludes = includes,
                allowIpv6Bypass = false,
            ),
        )
        val includeLine = lines.single { it.startsWith("服务端包含路由：") }

        assertTrue(includeLine.endsWith("另有 2 条"))
        assertFalse(lines.any { line -> line.any(Char::isISOControl) })
        assertTrue(lines.contains("DNS：未下发"))
        assertEquals("IPv6：已阻断（防止全隧道泄漏）", lines.last())
    }

    @Test
    fun `reports full tunnel exclusions and tunneled IPv6`() {
        val details = diagnostics(
            routes = listOf(NetworkPrefix("::", 0)),
            dnsServers = listOf("fd00::1"),
            splitIncludes = emptyList(),
            allowIpv6Bypass = false,
            addresses = listOf(NetworkPrefix("fd00::2", 64)),
            splitExcludes = listOf("fd12::/64"),
        )

        val lines = TunnelLogFormatter.lines(details)

        assertTrue(lines.contains("服务端包含路由：未下发，按全隧道处理"))
        assertTrue(lines.contains("服务端排除路由：fd12::/64"))
        assertEquals("IPv6：按服务端 VPN 路由承载", lines.last())
    }

    private fun diagnostics(
        routes: List<NetworkPrefix>,
        dnsServers: List<String>,
        splitIncludes: List<String>,
        allowIpv6Bypass: Boolean,
        addresses: List<NetworkPrefix> = listOf(NetworkPrefix("192.0.2.43", 24)),
        splitExcludes: List<String> = emptyList(),
    ) = TunnelDiagnostics(
        gatewayAddress = "203.0.113.10",
        configuration = TunnelConfiguration(
            sessionName = "Office",
            mtu = 1334,
            addresses = addresses,
            routes = routes,
            dnsServers = dnsServers,
            allowIpv6Bypass = allowIpv6Bypass,
        ),
        serverSplitIncludes = splitIncludes,
        serverSplitExcludes = splitExcludes,
    )
}
