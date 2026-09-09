package com.richard.tunnelkeeper.vpn

import com.richard.tunnelkeeper.model.SslGroup

internal data class SubmittedAuthFormDecision(
    val restartSessionToRefreshGroups: Boolean,
    val savedCredentialsRejected: Boolean,
)

internal object OpenConnectAuthFormPolicy {
    fun afterCredentialsSubmitted(
        reason: String,
        sslGroups: List<SslGroup>,
        credentialsCameFromSavedStore: Boolean,
    ): SubmittedAuthFormDecision {
        val explicitlyRejected = OpenConnectFailurePolicy.isExplicitAuthenticationRejection(reason)
        return SubmittedAuthFormDecision(
            restartSessionToRefreshGroups = explicitlyRejected && sslGroups.isEmpty(),
            savedCredentialsRejected = explicitlyRejected && credentialsCameFromSavedStore,
        )
    }

    fun preferSslGroup(groups: List<SslGroup>, preferredValue: String): List<SslGroup> {
        if (preferredValue.isBlank() || groups.none { it.value == preferredValue }) return groups
        return groups.map { group ->
            val isPreferred = group.value == preferredValue
            if (group.isDefault == isPreferred) group else group.copy(isDefault = isPreferred)
        }
    }
}
