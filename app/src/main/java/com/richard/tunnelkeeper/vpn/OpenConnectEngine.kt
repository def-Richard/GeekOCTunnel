package com.richard.tunnelkeeper.vpn

import com.richard.tunnelkeeper.model.ConnectionProfile
import com.richard.tunnelkeeper.model.SslGroup
import com.richard.tunnelkeeper.model.VpnCredentials

data class NetworkPrefix(
    val address: String,
    val prefixLength: Int,
)

data class TunnelConfiguration(
    val sessionName: String,
    val mtu: Int,
    val addresses: List<NetworkPrefix>,
    val routes: List<NetworkPrefix>,
    val dnsServers: List<String>,
    val allowIpv6Bypass: Boolean,
)

data class TunnelDiagnostics(
    val gatewayAddress: String?,
    val configuration: TunnelConfiguration,
    val serverSplitIncludes: List<String>,
    val serverSplitExcludes: List<String>,
)

interface VpnPlatform {
    fun protectSocket(socketFileDescriptor: Int): Boolean
    fun establishTunnel(configuration: TunnelConfiguration): TunnelFileDescriptor?
}

/** The caller owns this descriptor; native OpenConnect only borrows [rawFd]. */
interface TunnelFileDescriptor : AutoCloseable {
    val rawFd: Int
}

enum class EngineFailureKind {
    AUTHENTICATION,
    TLS,
    NETWORK,
    PROTOCOL,
    CANCELLED,
    UNKNOWN,
}

internal object OpenConnectFailurePolicy {
    private val definitiveAuthenticationRejectionPatterns = listOf(
        Regex("(?i)\\bunauthori[sz]ed\\b"),
        Regex("(?i)\\b401\\s+unauthori[sz]ed\\b"),
        Regex("(?i)\\bHTTP(?:/\\d(?:\\.\\d)?)?[^0-9\\r\\n]{0,24}401\\b"),
        Regex("(?i)\\b(?:unexpected|status|result|response(?:\\s+code)?)\\s+401\\b"),
        Regex("(?i)\\b(?:invalid|incorrect|wrong|bad)\\s+(?:user(?:name)?|login|password|passcode|credentials?)\\b"),
        Regex("(?:用户名或密码错误|密码错误|未授权)"),
    )
    private val genericAuthenticationRejectionPatterns = listOf(
        Regex("(?i)\\b(?:authentication|login|password|credentials?)\\s+(?:failed|failure|denied|rejected)\\b"),
        Regex("(?i)\\b(?:failed|failure|denied|rejected)\\s+(?:authentication|login|credentials?)\\b"),
        Regex("(?:认证失败|登录失败|认证被拒绝)"),
    )
    private val tlsSignal = Regex("(?i)(\\b(?:certificate|TLS|SSL|X\\.509|handshake)\\b|证书|握手)")
    private val certificateSignal = Regex("(?i)(\\b(?:certificate|X\\.509)\\b|证书)")
    private val definitiveNetworkFailureSignal = Regex(
        "(?i)(timed out|timeout|unreachable|DNS|failed to (?:open HTTPS connection|connect|resolve)|" +
            "connection (?:was )?(?:refused|reset|closed|failed)|connection failure|" +
            "network (?:error|failure|unavailable)|" +
            "socket (?:error|failure))",
    )
    private val networkSignal = Regex(
        "(?i)\\b(?:resolve|resolution|connect|connection|network|timeout|unreachable|socket|DNS)\\b|timed out",
    )

    fun isExplicitAuthenticationRejection(message: String): Boolean {
        if (tlsSignal.containsMatchIn(message)) return false
        if (definitiveAuthenticationRejectionPatterns.any { it.containsMatchIn(message) }) return true
        if (definitiveNetworkFailureSignal.containsMatchIn(message)) return false
        return genericAuthenticationRejectionPatterns.any { it.containsMatchIn(message) }
    }

    fun failure(message: String): EngineEvent.Failed {
        val kind = when {
            tlsSignal.containsMatchIn(message) -> EngineFailureKind.TLS
            isExplicitAuthenticationRejection(message) -> EngineFailureKind.AUTHENTICATION
            networkSignal.containsMatchIn(message) -> EngineFailureKind.NETWORK
            else -> EngineFailureKind.PROTOCOL
        }
        return EngineEvent.Failed(kind, message)
    }

    fun prefer(
        current: EngineEvent.Failed?,
        candidate: EngineEvent.Failed,
    ): EngineEvent.Failed {
        if (current == null) return candidate
        return if (priority(candidate) > priority(current)) candidate else current
    }

    private fun priority(failure: EngineEvent.Failed): Int = when {
        failure.kind == EngineFailureKind.AUTHENTICATION -> 50
        failure.kind == EngineFailureKind.TLS && certificateSignal.containsMatchIn(failure.message) -> 50
        failure.kind == EngineFailureKind.TLS -> 40
        failure.kind == EngineFailureKind.NETWORK -> 20
        else -> 10
    }
}

sealed interface EngineEvent {
    data class AwaitingAuthentication(
        val challengeId: String,
        val sslGroups: List<SslGroup>,
        val reason: String,
        val suggestedUsername: String,
        val savedCredentialsRejected: Boolean,
    ) : EngineEvent

    data object Authenticating : EngineEvent
    data object ConfiguringTunnel : EngineEvent
    data class TunnelEstablished(val diagnostics: TunnelDiagnostics) : EngineEvent
    data class Warning(val message: String) : EngineEvent
    data class Failed(val kind: EngineFailureKind, val message: String) : EngineEvent
    data object Disconnected : EngineEvent
}

interface OpenConnectSession {
    val id: String

    /** Runs the complete cookie, CSTP, TUN and mainloop lifecycle for one vpninfo instance. */
    suspend fun run(
        profile: ConnectionProfile,
        savedCredentials: VpnCredentials?,
        platform: VpnPlatform,
        onEvent: (EngineEvent) -> Unit,
    )

    /** Continues the currently blocked native auth form in this same session. */
    fun submitAuthentication(challengeId: String, credentials: VpnCredentials)

    fun cancel()
}

fun interface OpenConnectEngine {
    fun createSession(): OpenConnectSession
}

/** JNI is isolated behind this factory so the Service owns exactly one vpninfo per attempt. */
class NativeOpenConnectEngine : OpenConnectEngine {
    override fun createSession(): OpenConnectSession = NativeOpenConnectSession()
}
