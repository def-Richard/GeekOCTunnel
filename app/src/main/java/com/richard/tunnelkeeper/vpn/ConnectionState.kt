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
    private val mutableProfiles = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val profiles = mutableProfiles.asStateFlow()

    @Synchronized
    fun update(value: ConnectionState) {
        val id = when (value) {
            is ConnectionState.Connected -> value.profileId
            is ConnectionState.Connecting -> value.profileId
            is ConnectionState.AuthenticationRequired -> value.profileId
            is ConnectionState.Failed -> value.profileId
            ConnectionState.Disconnected -> null
        }
        if (id == null) {
            mutableProfiles.value = emptyMap()
            mutableState.value = value
        } else update(id, value)
    }

    @Synchronized
    fun update(profileId: String, value: ConnectionState) {
        mutableProfiles.value = if (value == ConnectionState.Disconnected) {
            mutableProfiles.value - profileId
        } else mutableProfiles.value + (profileId to value)
        mutableState.value = aggregateConnectionState(mutableProfiles.value.values)
    }
}

internal fun aggregateConnectionState(states: Collection<ConnectionState>): ConnectionState {
    val connected = states.filterIsInstance<ConnectionState.Connected>()
    if (connected.size == 1) return connected.first()
    if (connected.size > 1) return ConnectionState.Connected(
        connected.first().profileId, "${connected.size} 个服务器",
    )
    return states.firstOrNull { it is ConnectionState.AuthenticationRequired }
        ?: states.firstOrNull { it is ConnectionState.Connecting }
        ?: states.firstOrNull { it is ConnectionState.Failed }
        ?: ConnectionState.Disconnected
}
