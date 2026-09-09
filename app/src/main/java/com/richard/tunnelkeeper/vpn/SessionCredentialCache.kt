package com.richard.tunnelkeeper.vpn

import com.richard.tunnelkeeper.model.VpnCredentials

data class PendingCredentials(
    val credentials: VpnCredentials,
    val saveOnSuccess: Boolean,
)

/** Password handoff within this process. Intent extras contain only the opaque native session ID. */
object SessionCredentialCache {
    private val submissions = mutableMapOf<String, PendingCredentials>()

    @Synchronized
    fun put(challengeId: String, value: PendingCredentials) {
        submissions[challengeId] = value
    }

    @Synchronized
    fun take(challengeId: String): PendingCredentials? = submissions.remove(challengeId)

    @Synchronized
    fun remove(challengeId: String) {
        submissions.remove(challengeId)
    }

    @Synchronized
    fun clear() {
        submissions.clear()
    }
}
