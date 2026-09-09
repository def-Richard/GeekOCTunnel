package com.richard.tunnelkeeper.vpn

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelConfigurationMapperTest {
    @Test
    fun `creates full tunnel and converts IPv4 netmask`() {
        val configuration = TunnelConfigurationMapper.create(
            "Office",
            NegotiatedIpInfo(
                ipv4Address = "10.8.0.2",
                ipv4Netmask = "255.255.255.0",
                ipv6Address = null,
                ipv6Netmask = null,
                dnsServers = listOf("10.8.0.1"),
                splitIncludes = emptyList(),
                splitExcludes = emptyList(),
                mtu = 1350,
            ),
        )

        assertEquals(NetworkPrefix("10.8.0.2", 24), configuration.addresses.single())
        assertTrue(configuration.routes.contains(NetworkPrefix("0.0.0.0", 0)))
        assertFalse(configuration.routes.contains(NetworkPrefix("10.8.0.1", 32)))
        assertEquals(listOf("10.8.0.1"), configuration.dnsServers)
        assertFalse(configuration.allowIpv6Bypass)
        assertEquals(1350, configuration.mtu)
    }

    @Test
    fun `subtracts split exclusions without routing excluded address`() {
        val configuration = TunnelConfigurationMapper.create(
            "Office",
            NegotiatedIpInfo(
                ipv4Address = "10.8.0.2",
                ipv4Netmask = "24",
                ipv6Address = null,
                ipv6Netmask = null,
                dnsServers = emptyList(),
                splitIncludes = listOf("0.0.0.0/0"),
                splitExcludes = listOf("192.168.1.0/24"),
                mtu = 0,
            ),
        )

        assertFalse(configuration.routes.contains(NetworkPrefix("0.0.0.0", 0)))
        assertFalse(configuration.routes.contains(NetworkPrefix("192.168.1.0", 24)))
        assertTrue(configuration.routes.size > 1)
        assertFalse(configuration.routes.any { it.contains("192.168.1.1") })
        assertTrue(configuration.routes.any { it.contains("192.168.0.1") })
        assertTrue(configuration.routes.any { it.contains("192.168.2.1") })
        assertFalse(configuration.allowIpv6Bypass)
        assertEquals(1406, configuration.mtu)
    }

    @Test
    fun `supports IPv6 prefix netmask`() {
        val configuration = TunnelConfigurationMapper.create(
            "IPv6",
            NegotiatedIpInfo(
                ipv4Address = null,
                ipv4Netmask = null,
                ipv6Address = "fd00::2",
                ipv6Netmask = "64",
                dnsServers = listOf("fd00::1"),
                splitIncludes = emptyList(),
                splitExcludes = emptyList(),
                mtu = 1500,
            ),
        )

        assertEquals(64, configuration.addresses.single().prefixLength)
        assertTrue(configuration.routes.any { it.prefixLength == 0 && ':' in it.address })
        assertFalse(configuration.routes.any { it.prefixLength == 128 })
        assertFalse(configuration.allowIpv6Bypass)
    }

    @Test
    fun `keeps public DNS outside an IPv4 split route and allows IPv6 bypass`() {
        val configuration = TunnelConfigurationMapper.create(
            "Office",
            NegotiatedIpInfo(
                ipv4Address = "192.0.2.43",
                ipv4Netmask = "255.255.255.0",
                ipv6Address = null,
                ipv6Netmask = null,
                dnsServers = listOf("203.0.113.53"),
                splitIncludes = listOf("198.51.100.0/24"),
                splitExcludes = emptyList(),
                mtu = 1334,
            ),
        )

        assertEquals(listOf(NetworkPrefix("198.51.100.0", 24)), configuration.routes)
        assertEquals(listOf("203.0.113.53"), configuration.dnsServers)
        assertTrue(configuration.allowIpv6Bypass)
    }

    @Test
    fun `does not allow IPv6 bypass for an explicit IPv4 default route`() {
        val configuration = createWithSplitInclude("0.0.0.0/0")

        assertEquals(listOf(NetworkPrefix("0.0.0.0", 0)), configuration.routes)
        assertFalse(configuration.allowIpv6Bypass)
    }

    @Test
    fun `does not submit IPv6 DNS for an IPv4 only full tunnel`() {
        val configuration = TunnelConfigurationMapper.create(
            "Full tunnel",
            NegotiatedIpInfo(
                ipv4Address = "10.8.0.2",
                ipv4Netmask = "24",
                ipv6Address = null,
                ipv6Netmask = null,
                dnsServers = listOf("10.8.0.1", "2001:4860:4860::8888"),
                splitIncludes = emptyList(),
                splitExcludes = emptyList(),
                mtu = 1400,
            ),
        )

        assertEquals(listOf("10.8.0.1"), configuration.dnsServers)
        assertFalse(configuration.allowIpv6Bypass)
    }

    @Test
    fun `keeps IPv6 DNS when an IPv4 split tunnel allows IPv6 bypass`() {
        val configuration = TunnelConfigurationMapper.create(
            "Split tunnel",
            NegotiatedIpInfo(
                ipv4Address = "10.8.0.2",
                ipv4Netmask = "24",
                ipv6Address = null,
                ipv6Netmask = null,
                dnsServers = listOf("2001:4860:4860::8888"),
                splitIncludes = listOf("10.9.0.0/16"),
                splitExcludes = emptyList(),
                mtu = 1400,
            ),
        )

        assertEquals(listOf("2001:4860:4860::8888"), configuration.dnsServers)
        assertTrue(configuration.allowIpv6Bypass)
    }

    @Test
    fun `does not allow IPv6 bypass when split routes cover all IPv4 addresses`() {
        val configuration = createWithSplitIncludes(listOf("0.0.0.0/1", "128.0.0.0/1"))

        assertEquals(2, configuration.routes.size)
        assertFalse(configuration.allowIpv6Bypass)
    }

    @Test
    fun `rejects split routes that do not match the negotiated address family`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            createWithSplitInclude("fd10::/64")
        }

        assertEquals("服务器返回的路由与 VPN 地址族不匹配", error.message)
    }

    @Test
    fun `does not bypass IPv6 when a dual stack split tunnel provides IPv6`() {
        val configuration = TunnelConfigurationMapper.create(
            "Dual stack",
            NegotiatedIpInfo(
                ipv4Address = "10.8.0.2",
                ipv4Netmask = "24",
                ipv6Address = "fd00::2",
                ipv6Netmask = "64",
                dnsServers = emptyList(),
                splitIncludes = listOf("10.9.0.0/16", "fd10::/64"),
                splitExcludes = emptyList(),
                mtu = 1400,
            ),
        )

        assertEquals(2, configuration.routes.size)
        assertFalse(configuration.allowIpv6Bypass)
    }

    @Test
    fun `accepts ocserv IPv4 route with dotted netmask`() {
        val configuration = createWithSplitInclude("198.51.100.117/255.255.255.255")

        assertEquals(listOf(NetworkPrefix("198.51.100.117", 32)), configuration.routes)
        assertTrue(configuration.allowIpv6Bypass)
    }

    @Test
    fun `rejects non-contiguous dotted route netmask`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            createWithSplitInclude("198.51.100.117/255.0.255.0")
        }

        assertEquals("服务器返回了无效路由", error.message)
    }

    @Test
    fun `rejects route option text instead of a network`() {
        assertThrows(IllegalArgumentException::class.java) {
            createWithSplitInclude("route=198.51.100.117/255.255.255.255")
        }
    }

    private fun createWithSplitInclude(route: String) = createWithSplitIncludes(listOf(route))

    private fun createWithSplitIncludes(routes: List<String>) = TunnelConfigurationMapper.create(
        "ocserv",
        NegotiatedIpInfo(
            ipv4Address = "10.77.0.55",
            ipv4Netmask = "255.255.255.0",
            ipv6Address = null,
            ipv6Netmask = null,
            dnsServers = emptyList(),
            splitIncludes = routes,
            splitExcludes = emptyList(),
            mtu = 1500,
        ),
    )

    private fun NetworkPrefix.contains(value: String): Boolean {
        val routeBytes = InetAddress.getByName(address).address
        val valueBytes = InetAddress.getByName(value).address
        if (routeBytes.size != valueBytes.size) return false
        repeat(prefixLength) { bitIndex ->
            val mask = 1 shl (7 - bitIndex % 8)
            val byteIndex = bitIndex / 8
            if ((routeBytes[byteIndex].toInt() and mask) != (valueBytes[byteIndex].toInt() and mask)) {
                return false
            }
        }
        return true
    }
}
