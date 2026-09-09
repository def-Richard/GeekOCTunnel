package com.richard.tunnelkeeper.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenConnectFailurePolicyTest {
    @Test
    fun `preserves certificate failure over later generic HTTPS failure`() {
        val certificateFailure = EngineEvent.Failed(
            EngineFailureKind.TLS,
            "服务器证书校验失败：Trust anchor for certification path not found",
        )
        val genericFailure = OpenConnectFailurePolicy.failure(
            "Failed to open HTTPS connection to vpn.example.com",
        )

        assertEquals(
            certificateFailure,
            OpenConnectFailurePolicy.prefer(certificateFailure, genericFailure),
        )
    }

    @Test
    fun `preserves TLS failure over later generic HTTPS failure`() {
        val tlsFailure = OpenConnectFailurePolicy.failure("SSL connection failure")
        val genericFailure = OpenConnectFailurePolicy.failure(
            "Failed to open HTTPS connection to vpn.example.com",
        )

        assertEquals(EngineFailureKind.TLS, tlsFailure.kind)
        assertEquals(tlsFailure, OpenConnectFailurePolicy.prefer(tlsFailure, genericFailure))
    }

    @Test
    fun `recognizes only explicit authentication rejection messages`() {
        listOf(
            "HTTP response code 401",
            "401 Unauthorized",
            "Invalid password",
            "Login failed",
            "Authentication rejected",
            "Invalid credentials",
        ).forEach { message ->
            assertTrue(message, OpenConnectFailurePolicy.isExplicitAuthenticationRejection(message))
            assertEquals(EngineFailureKind.AUTHENTICATION, OpenConnectFailurePolicy.failure(message).kind)
        }
    }

    @Test
    fun `does not treat TLS network or generic credential text as rejection`() {
        listOf(
            "Failed to open HTTPS connection to vpn.example.com",
            "SSL certificate authentication failed",
            "TLS handshake failed",
            "Network timeout while connecting",
            "Authentication failed: network timeout",
            "Login failed because the connection was reset",
            "Please enter your username and password",
            "Failed to complete authentication",
            "403 Forbidden",
        ).forEach { message ->
            assertFalse(message, OpenConnectFailurePolicy.isExplicitAuthenticationRejection(message))
        }

        assertEquals(
            EngineFailureKind.TLS,
            OpenConnectFailurePolicy.failure("SSL certificate authentication failed").kind,
        )
        assertEquals(
            EngineFailureKind.NETWORK,
            OpenConnectFailurePolicy.failure("Failed to open HTTPS connection to vpn.example.com").kind,
        )
        assertEquals(
            EngineFailureKind.NETWORK,
            OpenConnectFailurePolicy.failure("Authentication failed: network timeout").kind,
        )
    }
}
