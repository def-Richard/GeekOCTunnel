package com.richard.tunnelkeeper.vpn

import com.richard.tunnelkeeper.model.SslGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data class Connecting(
        val profileId: String,
        val profileName: String,
        val stage: ConnectionStage,
    ) : ConnectionState

    data class AuthenticationRequired(
        val profileId: String,
        val profileName: String,
        val sessionId: String,
        val challengeId: String,
        val sslGroups: List<SslGroup>,
        val reason: String,
        val suggestedUsername: String,
    ) : ConnectionState

    data class Connected(
        val profileId: String,
        val profileName: String,
    ) : ConnectionState

    data class Failed(
        val profileId: String?,
        val reason: String,
    ) : ConnectionState
}

enum class ConnectionStage {
    STARTING_AUTHENTICATION,
    AUTHENTICATING,
    CONFIGURING_TUNNEL,
}

object ConnectionStateStore {
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state = mutableState.asStateFlow()

    fun update(value: ConnectionState) {
        mutableState.value = value
    }
}
