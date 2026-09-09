package com.richard.tunnelkeeper.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileValidatorTest {
    @Test
    fun `normalizes HTTPS server with non-default port`() {
        val result = ProfileValidator.validate(
            id = "profile-1",
            name = "Office ASA",
            server = "https://vpn.example.com:8443/",
        )

        assertTrue(result.isSuccess)
        assertEquals("vpn.example.com:8443", result.getOrThrow().server)
    }

    @Test
    fun `adds default HTTPS scheme and normalizes hostname`() {
        val result = ProfileValidator.validate(
            name = "Office ASA",
            server = "VPN.Example.COM",
        )

        assertTrue(result.isSuccess)
        assertEquals("vpn.example.com", result.getOrThrow().server)
        assertFalse(result.getOrThrow().ignoreCertificateErrors)
    }

    @Test
    fun `preserves explicit certificate error preference`() {
        val result = ProfileValidator.validate(
            id = "profile-1",
            name = "Lab ocserv",
            server = "vpn.example.com",
            ignoreCertificateErrors = true,
        )

        assertTrue(result.getOrThrow().ignoreCertificateErrors)
    }

    @Test
    fun `normalizes default HTTPS port away`() {
        val result = ProfileValidator.validate(
            name = "Office ASA",
            server = "https://vpn.example.com:443/",
        )

        assertTrue(result.isSuccess)
        assertEquals("vpn.example.com", result.getOrThrow().server)
    }

    @Test
    fun `accepts bracketed IPv6 with optional port`() {
        val withoutPort = ProfileValidator.validate(name = "IPv6", server = "[2001:db8::1]")
        val withPort = ProfileValidator.validate(name = "IPv6", server = "https://[2001:DB8::1]:4443/")

        assertEquals("[2001:db8::1]", withoutPort.getOrThrow().server)
        assertEquals("[2001:db8::1]:4443", withPort.getOrThrow().server)
    }

    @Test
    fun `rejects insecure or ambiguous URL components`() {
        val invalidServers = listOf(
            "http://vpn.example.com",
            "https://user@vpn.example.com",
            "https://vpn.example.com/client",
            "https://vpn.example.com?group=staff",
            "https://vpn.example.com#staff",
            "https://vpn.example.com:",
        )

        invalidServers.forEach { server ->
            assertTrue(server, ProfileValidator.validate(name = "VPN", server = server).isFailure)
        }
    }

    @Test
    fun `rejects malformed ports and IP literals`() {
        val invalidServers = listOf(
            "vpn.example.com:0",
            "vpn.example.com:65536",
            "999.1.1.1",
            "01.2.3.4",
            "2001:db8::1",
        )

        invalidServers.forEach { server ->
            assertTrue(server, ProfileValidator.validate(name = "VPN", server = server).isFailure)
        }
    }

    @Test
    fun `rejects empty name`() {
        val result = ProfileValidator.validate(
            name = "  ",
            server = "vpn.example.com",
        )

        assertFalse(result.isSuccess)
    }
}
