package com.richard.tunnelkeeper.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickConnectTileServiceTest {
    @Test
    fun `active connection states disconnect without checking prerequisites`() {
        val states = listOf(
            ConnectionState.Connected("profile-1", "Office"),
            ConnectionState.Connecting("profile-1", "Office", ConnectionStage.AUTHENTICATING),
            ConnectionState.AuthenticationRequired(
                profileId = "profile-1",
                profileName = "Office",
                sessionId = "session-1",
                challengeId = "challenge-1",
                sslGroups = emptyList(),
                reason = "请输入账号和密码",
                suggestedUsername = "",
            ),
        )

        states.forEach { state ->
            assertEquals(
                QuickTileAction.Disconnect,
                resolveQuickTileAction(state, null, false, false),
            )
        }
    }

    @Test
    fun `inactive state connects only when every prerequisite is available`() {
        assertEquals(
            QuickTileAction.Connect("profile-1"),
            resolveQuickTileAction(ConnectionState.Disconnected, "profile-1", true, true),
        )
        assertEquals(
            QuickTileAction.Connect("profile-1"),
            resolveQuickTileAction(ConnectionState.Failed("profile-1", "failed"), "profile-1", true, true),
        )
    }

    @Test
    fun `inactive state opens app when a prerequisite is missing`() {
        listOf(
            resolveQuickTileAction(ConnectionState.Disconnected, null, true, true),
            resolveQuickTileAction(ConnectionState.Disconnected, "profile-1", false, true),
            resolveQuickTileAction(ConnectionState.Disconnected, "profile-1", true, false),
        ).forEach { action ->
            assertEquals(QuickTileAction.OpenApp, action)
        }
    }
}
