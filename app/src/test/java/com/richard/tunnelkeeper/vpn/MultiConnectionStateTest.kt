package com.richard.tunnelkeeper.vpn

import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class MultiConnectionStateTest {
    @Before @After fun reset() { ConnectionStateStore.update(ConnectionState.Disconnected) }

    @Test fun `failed or disconnected second server never turns off the connected tile`() {
        val home = ConnectionState.Connected("home", "Home")
        ConnectionStateStore.update(home)
        ConnectionStateStore.update(ConnectionState.Connecting("office", "Office", ConnectionStage.AUTHENTICATING))
        ConnectionStateStore.update(ConnectionState.Failed("office", "wrong password"))
        assertEquals(home, ConnectionStateStore.state.value)
        assertTrue(ConnectionStateStore.profiles.value["office"] is ConnectionState.Failed)
        ConnectionStateStore.update("office", ConnectionState.Disconnected)
        assertEquals(home, ConnectionStateStore.state.value)
    }

    @Test fun `authentication challenges remain separately addressable`() {
        val a = ConnectionState.AuthenticationRequired("a", "A", "session-a", "challenge-a", emptyList(), "", "")
        val b = a.copy(profileId = "b", sessionId = "session-b", challengeId = "challenge-b")
        ConnectionStateStore.update(a)
        ConnectionStateStore.update(b)
        ConnectionStateStore.update(ConnectionState.Connected("a", "A"))
        assertEquals(b, ConnectionStateStore.profiles.value["b"])
        assertTrue(ConnectionStateStore.state.value is ConnectionState.Connected)
        ConnectionStateStore.update("a", ConnectionState.Disconnected)
        assertEquals(b, ConnectionStateStore.state.value)
    }

    @Test fun `two connections show aggregate count and stop all clears both`() {
        ConnectionStateStore.update(ConnectionState.Connected("a", "A"))
        ConnectionStateStore.update(ConnectionState.Connected("b", "B"))
        assertEquals("2 个服务器", (ConnectionStateStore.state.value as ConnectionState.Connected).profileName)
        ConnectionStateStore.update(ConnectionState.Disconnected)
        assertEquals(ConnectionState.Disconnected, ConnectionStateStore.state.value)
        assertTrue(ConnectionStateStore.profiles.value.isEmpty())
    }
}
