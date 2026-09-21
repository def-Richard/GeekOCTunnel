package com.richard.tunnelkeeper.vpn

import org.junit.Assert.*
import org.junit.Test

class MultiTunnelRoutesTest {
    @Test fun `a site that never supplied routes cannot join an established TUN`() {
        val routes = MultiTunnelRoutes()
        val a = site("a", "192.0.2.0", 24)
        val b = site("b", "198.51.100.0", 24)
        routes.candidate(a); routes.commit(a)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            routes.candidate(b, tunnelEstablished = true)
        }
        assertTrue(failure.message.orEmpty().contains("尚未安装路由"))
        assertEquals("a", routes.owner(ip("192.0.2.20")))
        assertNull(routes.owner(ip("198.51.100.20")))
        assertEquals(a.configuration, routes.candidate(a, tunnelEstablished = true))
    }

    @Test fun `two disjoint servers may use the same assigned VPN address`() {
        val routes = MultiTunnelRoutes()
        val a = site("home", "192.0.2.0", 24)
        val b = site("office", "198.51.100.0", 24)
        routes.candidate(a); routes.commit(a)
        val combined = routes.candidate(b)
        routes.commit(b)
        assertEquals(a.configuration.addresses, combined.addresses)
        assertEquals(2, combined.routes.size)
        assertEquals("home", routes.owner(ip("192.0.2.88")))
        assertEquals("office", routes.owner(ip("198.51.100.10")))
        assertNull(routes.owner(ip("203.0.113.1")))
    }

    @Test fun `overlapping full tunnel and duplicate routes are rejected before altering existing rules`() {
        for (b in listOf(site("b", "192.0.2.0", 24), site("b", "192.0.2.128", 25), site("b", "0.0.0.0", 0))) {
            val routes = MultiTunnelRoutes()
            val a = site("a", "192.0.2.0", 24)
            routes.candidate(a); routes.commit(a)
            assertThrows(IllegalArgumentException::class.java) { routes.candidate(b) }
            assertEquals("a", routes.owner(ip("192.0.2.200")))
            assertNull(routes.owner(ip("203.0.113.1")))
        }
    }

    @Test fun `reconnect may change server assigned address but cannot steal another route`() {
        val routes = MultiTunnelRoutes()
        val a = site("a", "192.0.2.0", 24)
        val b = site("b", "198.51.100.0", 24)
        routes.candidate(a); routes.commit(a)
        routes.candidate(b); routes.commit(b)
        val reconnect = a.copy(configuration = a.configuration.copy(addresses = listOf(NetworkPrefix("203.0.113.9", 32))))
        val merged = routes.candidate(reconnect)
        routes.commit(reconnect)
        assertEquals(a.configuration.addresses, merged.addresses)
        assertEquals("b", routes.owner(ip("198.51.100.10")))
        assertThrows(IllegalArgumentException::class.java) { routes.candidate(site("a", "203.0.113.0", 24)) }
    }

    @Test fun `retained route ownership prevents disconnected site falling through`() {
        val routes = MultiTunnelRoutes()
        val a = site("a", "192.0.2.0", 24)
        routes.candidate(a); routes.commit(a)
        // Android router removes only the endpoint on disconnect. The route stays owned by A.
        assertEquals("a", routes.owner(ip("192.0.2.10")))
        assertThrows(IllegalArgumentException::class.java) { routes.candidate(site("b", "192.0.2.0", 24)) }
    }

    @Test fun `a server cannot inject responses claiming another server network`() {
        val routes = MultiTunnelRoutes()
        val a = site("a", "192.0.2.0", 24)
        val b = site("b", "198.51.100.0", 24)
        routes.candidate(a); routes.commit(a)
        routes.candidate(b); routes.commit(b)
        val packet = ByteArray(28)
        packet[0] = 0x45
        packet[9] = 17
        byteArrayOf(192.toByte(), 0, 2, 9).copyInto(packet, 12)
        assertTrue(routes.acceptsReply("a", packet, packet.size))
        assertFalse(routes.acceptsReply("b", packet, packet.size))
        assertNull(routes.singleSiteId)
    }

    @Test fun `single server retains IPv6 and full tunnel behavior but cannot ambiguously join another`() {
        val routes = MultiTunnelRoutes()
        val a = site("a", "0.0.0.0", 0).let {
            it.copy(configuration = it.configuration.copy(
                addresses = it.configuration.addresses + NetworkPrefix("2001:db8::2", 64),
                routes = it.configuration.routes + NetworkPrefix("::", 0), allowIpv6Bypass = false,
            ))
        }
        assertEquals(a.configuration, routes.candidate(a))
        routes.commit(a)
        assertThrows(IllegalArgumentException::class.java) { routes.candidate(site("b", "198.51.100.0", 24)) }
    }

    private fun site(id: String, network: String, prefix: Int) = MultiTunnelRoutes.Site(id, id, TunnelConfiguration(
        sessionName = id, mtu = 1334, addresses = listOf(NetworkPrefix("10.8.0.2", 32)),
        routes = listOf(NetworkPrefix(network, prefix)), dnsServers = listOf("1.1.1.1"), allowIpv6Bypass = true,
    ))
    private fun ip(value: String) = Ipv4PacketTranslator.address(value)
}
