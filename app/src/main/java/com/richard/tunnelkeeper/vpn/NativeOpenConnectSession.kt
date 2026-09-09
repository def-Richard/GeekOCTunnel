package com.richard.tunnelkeeper.vpn

import android.net.http.X509TrustManagerExtensions
import com.richard.tunnelkeeper.BuildConfig
import com.richard.tunnelkeeper.model.ConnectionProfile
import com.richard.tunnelkeeper.model.SslGroup
import com.richard.tunnelkeeper.model.VpnCredentials
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.infradead.libopenconnect.LibOpenConnect

internal data class AuthGroupFormSelection(
    val index: Int,
    val value: String,
    val refreshForm: Boolean,
)

internal object OpenConnectAuthGroupPolicy {
    fun select(
        choiceValues: List<String?>,
        requestedValue: String,
        appliedValue: String?,
    ): AuthGroupFormSelection? {
        if (requestedValue.isBlank()) return null
        val selectedIndex = choiceValues.indexOfFirst { it == requestedValue }
        if (selectedIndex < 0) return null
        return AuthGroupFormSelection(
            index = selectedIndex,
            value = requestedValue,
            refreshForm = appliedValue != requestedValue,
        )
    }
}

internal class NativeOpenConnectSession : OpenConnectSession {
    private sealed interface AuthResponse {
        data class Submit(
            val challengeId: String,
            val credentials: VpnCredentials,
        ) : AuthResponse

        data object Cancel : AuthResponse
    }

    override val id: String = UUID.randomUUID().toString()
    private val cancelled = AtomicBoolean(false)
    private val authResponses = LinkedBlockingQueue<AuthResponse>()
    private val challengeLock = Any()

    @Volatile
    private var bridge: OpenConnectBridge? = null

    private var activeChallengeId: String? = null

    override suspend fun run(
        profile: ConnectionProfile,
        savedCredentials: VpnCredentials?,
        platform: VpnPlatform,
        onEvent: (EngineEvent) -> Unit,
    ) {
        val loadFailure = OpenConnectNativeLibrary.loadFailure
        if (loadFailure != null) {
            onEvent(
                EngineEvent.Failed(
                    EngineFailureKind.PROTOCOL,
                    "无法加载 OpenConnect 原生引擎：${loadFailure.message ?: "未知错误"}",
                ),
            )
            return
        }

        val hostname = URI("https://${profile.server}").host
            ?: run {
                onEvent(EngineEvent.Failed(EngineFailureKind.PROTOCOL, "服务器地址无效"))
                return
            }
        val nativeBridge = OpenConnectBridge(
            profileName = profile.name,
            serverAddress = profile.server,
            ignoreCertificateErrors = profile.ignoreCertificateErrors,
            hostname = hostname,
            platform = platform,
            initialCredentials = savedCredentials,
            eventSink = onEvent,
        )
        bridge = nativeBridge

        try {
            nativeBridge.setLogLevel(LibOpenConnect.PRG_INFO)
            nativeBridge.setSystemTrust(true)
            nativeBridge.setReportedOS("android")
            if (nativeBridge.setProtocol(ANYCONNECT_PROTOCOL) != 0) {
                onEvent(EngineEvent.Failed(EngineFailureKind.PROTOCOL, "原生引擎不支持 AnyConnect 协议"))
                return
            }
            if (nativeBridge.parseURL("https://${profile.server}") != 0) {
                onEvent(EngineEvent.Failed(EngineFailureKind.PROTOCOL, "无法解析 VPN 服务器地址"))
                return
            }

            val cookieResult = nativeBridge.obtainCookie()
            if (cookieResult != 0) {
                onEvent(nativeBridge.failureEvent("无法完成 VPN 认证"))
                return
            }
            if (cancelled.get()) {
                onEvent(EngineEvent.Failed(EngineFailureKind.CANCELLED, "连接已取消"))
                return
            }
            if (nativeBridge.makeCSTPConnection() != 0) {
                onEvent(nativeBridge.failureEvent("无法建立 CSTP 连接"))
                return
            }

            // DTLS is an optimization. A gateway may intentionally offer only the TLS data channel.
            nativeBridge.setupDTLS(DTLS_ATTEMPT_SECONDS)
            val mainloopResult = nativeBridge.mainloop(RECONNECT_TIMEOUT_SECONDS, LibOpenConnect.RECONNECT_INTERVAL_MIN)
            val fatalEvent = nativeBridge.fatalEvent
            when {
                fatalEvent != null -> onEvent(fatalEvent)
                cancelled.get() || nativeBridge.isCanceled ->
                    onEvent(EngineEvent.Failed(EngineFailureKind.CANCELLED, "连接已取消"))
                mainloopResult < 0 -> onEvent(nativeBridge.failureEvent("VPN 数据通道意外结束"))
                else -> onEvent(EngineEvent.Disconnected)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            onEvent(EngineEvent.Failed(EngineFailureKind.CANCELLED, "连接已取消"))
        } catch (error: Throwable) {
            onEvent(
                EngineEvent.Failed(
                    EngineFailureKind.UNKNOWN,
                    error.message?.take(MAX_ERROR_LENGTH) ?: "OpenConnect 原生会话异常结束",
                ),
            )
        } finally {
            synchronized(challengeLock) { activeChallengeId = null }
            bridge = null
            nativeBridge.destroyAndReleaseTunnel()
        }
    }

    override fun submitAuthentication(challengeId: String, credentials: VpnCredentials) {
        synchronized(challengeLock) {
            check(activeChallengeId == challengeId) { "认证请求已经失效" }
            activeChallengeId = null
        }
        check(authResponses.offer(AuthResponse.Submit(challengeId, credentials))) { "无法提交认证信息" }
    }

    override fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            synchronized(challengeLock) { activeChallengeId = null }
            authResponses.clear()
            authResponses.offer(AuthResponse.Cancel)
            bridge?.cancel()
        }
    }

    private inner class OpenConnectBridge(
        private val profileName: String,
        private val serverAddress: String,
        private val ignoreCertificateErrors: Boolean,
        private val hostname: String,
        private val platform: VpnPlatform,
        initialCredentials: VpnCredentials?,
        private val eventSink: (EngineEvent) -> Unit,
    ) : LibOpenConnect(USER_AGENT) {
        private val trustManagerExtensions = X509TrustManagerExtensions(defaultTrustManager())
        private var credentials: VpnCredentials? = initialCredentials
        private var credentialsCameFromSavedStore = initialCredentials != null
        private var credentialsSubmitted = false
        private var appliedAuthGroup: String? = null
        private var lastFailure: EngineEvent.Failed? = null
        private val tunnelLease = NativeTunFileDescriptorLease()
        private val certificateWarningEmitted = AtomicBoolean(false)

        @Volatile
        var fatalEvent: EngineEvent.Failed? = null
            private set

        override fun onProcessAuthForm(authForm: AuthForm): Int {
            if (cancelled.get()) return OC_FORM_RESULT_CANCELLED
            var groups = authForm.sslGroups()
            var currentCredentials = credentials
            var savedCredentialsRejected = false
            var reason = authForm.safeReason()
            var suggestedUsername = currentCredentials?.username.orEmpty()

            if (credentialsSubmitted) {
                val decision = OpenConnectAuthFormPolicy.afterCredentialsSubmitted(
                    reason = reason,
                    sslGroups = groups,
                    credentialsCameFromSavedStore = credentialsCameFromSavedStore,
                )
                if (decision.restartSessionToRefreshGroups) {
                    fatalEvent = EngineEvent.Failed(
                        EngineFailureKind.AUTHENTICATION,
                        reason.ifBlank { "认证被服务器拒绝" },
                    )
                    return OC_FORM_RESULT_ERR
                }
                savedCredentialsRejected = decision.savedCredentialsRejected
                suggestedUsername = currentCredentials?.username.orEmpty()
                groups = OpenConnectAuthFormPolicy.preferSslGroup(
                    groups = groups,
                    preferredValue = currentCredentials?.sslGroup.orEmpty(),
                )
                credentials = null
                currentCredentials = null
                credentialsSubmitted = false
                credentialsCameFromSavedStore = false
                if (reason.isBlank()) reason = "服务器再次请求认证，请重新输入账号和密码"
            }

            val selectedGroup = currentCredentials?.sslGroup.orEmpty()
            if (
                currentCredentials != null &&
                selectedGroup.isNotEmpty() &&
                groups.isNotEmpty() &&
                groups.none { it.value == selectedGroup }
            ) {
                savedCredentialsRejected = credentialsCameFromSavedStore
                suggestedUsername = currentCredentials.username
                credentials = null
                currentCredentials = null
                credentialsCameFromSavedStore = false
                reason = "上次使用的 SSL Group 已不可用，请重新选择"
            }

            if (currentCredentials == null) {
                val response = awaitAuthentication(
                    groups = groups,
                    reason = reason.ifBlank { "请输入账号和密码" },
                    suggestedUsername = suggestedUsername,
                    savedCredentialsRejected = savedCredentialsRejected,
                ) ?: return OC_FORM_RESULT_CANCELLED
                currentCredentials = response
                credentials = response
                credentialsCameFromSavedStore = false
            }

            val authgroup = authForm.authgroupOpt
            if (authgroup != null && currentCredentials.sslGroup.isNotEmpty()) {
                val selection = OpenConnectAuthGroupPolicy.select(
                    choiceValues = authgroup.choices.map { it.name },
                    requestedValue = currentCredentials.sslGroup,
                    appliedValue = appliedAuthGroup,
                )
                if (selection == null) {
                    fatalEvent = EngineEvent.Failed(
                        EngineFailureKind.PROTOCOL,
                        "所选 SSL Group 已不在服务器选项中",
                    )
                    return OC_FORM_RESULT_ERR
                }
                authgroup.value = selection.value
                authForm.authgroupSelection = selection.index
                if (selection.refreshForm) {
                    appliedAuthGroup = selection.value
                    return OC_FORM_RESULT_NEWGROUP
                }
            }

            return fillSimpleUsernamePasswordForm(authForm, currentCredentials)
        }

        private fun awaitAuthentication(
            groups: List<SslGroup>,
            reason: String,
            suggestedUsername: String,
            savedCredentialsRejected: Boolean,
        ): VpnCredentials? {
            val challengeId = UUID.randomUUID().toString()
            synchronized(challengeLock) { activeChallengeId = challengeId }
            eventSink(
                EngineEvent.AwaitingAuthentication(
                    challengeId = challengeId,
                    sslGroups = groups,
                    reason = reason,
                    suggestedUsername = suggestedUsername,
                    savedCredentialsRejected = savedCredentialsRejected,
                ),
            )
            while (true) {
                when (val response = authResponses.take()) {
                    AuthResponse.Cancel -> return null
                    is AuthResponse.Submit -> if (response.challengeId == challengeId) return response.credentials
                }
            }
        }

        private fun fillSimpleUsernamePasswordForm(
            authForm: AuthForm,
            currentCredentials: VpnCredentials,
        ): Int {
            val editable = authForm.opts.filter { (it.flags and OC_FORM_OPT_IGNORE.toLong()) == 0L }
            val textOptions = editable.filter { it.type == OC_FORM_OPT_TEXT }
            val passwordOptions = editable.filter { it.type == OC_FORM_OPT_PASSWORD }
            val unsupported = editable.filter {
                it.type == OC_FORM_OPT_TOKEN ||
                    (it.type == OC_FORM_OPT_SELECT && it !== authForm.authgroupOpt && it.value.isNullOrBlank())
            }
            if (textOptions.size > 1 || passwordOptions.size > 1 || unsupported.isNotEmpty()) {
                fatalEvent = EngineEvent.Failed(
                    EngineFailureKind.PROTOCOL,
                    "服务器要求了账号密码之外的认证字段，当前版本不支持",
                )
                return OC_FORM_RESULT_ERR
            }

            textOptions.firstOrNull()?.value = currentCredentials.username
            passwordOptions.firstOrNull()?.value = currentCredentials.password
            if (passwordOptions.isNotEmpty()) {
                credentialsSubmitted = true
                eventSink(EngineEvent.Authenticating)
            }
            return OC_FORM_RESULT_OK
        }

        override fun onValidatePeerCert(message: String?): Int {
            if (ignoreCertificateErrors) {
                if (certificateWarningEmitted.compareAndSet(false, true)) {
                    val detail = message
                        ?.let(::sanitizeNativeMessage)
                        ?.takeIf(String::isNotBlank)
                        ?.let { "：$it" }
                        .orEmpty()
                    eventSink(
                        EngineEvent.Warning(
                            "安全警告：已按配置忽略服务器 $serverAddress 的证书校验错误$detail",
                        ),
                    )
                }
                return 0
            }

            return try {
                val certificateFactory = CertificateFactory.getInstance("X.509")
                val chain = peerCertChain.map { der ->
                    certificateFactory.generateCertificate(der.inputStream()) as X509Certificate
                }.toTypedArray()
                require(chain.isNotEmpty()) { "服务器未提供证书链" }
                val authType = chain.first().publicKey.algorithm
                trustManagerExtensions.checkServerTrusted(chain, authType, hostname)
                0
            } catch (error: Throwable) {
                val detail = sequenceOf(message, error.message)
                    .filterNotNull()
                    .map(::sanitizeNativeMessage)
                    .firstOrNull(String::isNotBlank)
                recordFailure(
                    EngineEvent.Failed(
                        EngineFailureKind.TLS,
                        if (detail == null) "服务器证书校验失败" else "服务器证书校验失败：$detail",
                    ),
                )
                -1
            }
        }

        override fun onProtectSocket(fd: Int) {
            if (!platform.protectSocket(fd)) {
                fatalEvent = EngineEvent.Failed(EngineFailureKind.NETWORK, "无法保护 VPN 传输套接字")
                cancel()
            }
        }

        override fun onSetupTun() {
            if (tunnelLease.isAttached || cancelled.get()) return
            eventSink(EngineEvent.ConfiguringTunnel)
            try {
                val ipInfo = ipInfo ?: error("服务器没有返回网络配置")
                val negotiatedInfo = NegotiatedIpInfo(
                    ipv4Address = ipInfo.addr,
                    ipv4Netmask = ipInfo.netmask,
                    ipv6Address = ipInfo.addr6,
                    ipv6Netmask = ipInfo.netmask6,
                    dnsServers = ipInfo.DNS.toList(),
                    splitIncludes = ipInfo.splitIncludes.toList(),
                    splitExcludes = ipInfo.splitExcludes.toList(),
                    mtu = ipInfo.MTU,
                )
                val configuration = TunnelConfigurationMapper.create(
                    sessionName = profileName,
                    info = negotiatedInfo,
                )
                val setupResult = tunnelLease.establishAndAttach(
                    establish = {
                        checkNotNull(platform.establishTunnel(configuration)) {
                            "Android 未能建立 VPN TUN 接口"
                        }
                    },
                    attachToNative = ::setupTunFD,
                )
                check(setupResult == 0) { "OpenConnect 未能使用 VPN TUN 接口" }
                eventSink(
                    EngineEvent.TunnelEstablished(
                        diagnostics = TunnelDiagnostics(
                            gatewayAddress = ipInfo.gatewayAddr,
                            configuration = configuration,
                            serverSplitIncludes = negotiatedInfo.splitIncludes,
                            serverSplitExcludes = negotiatedInfo.splitExcludes,
                        ),
                    ),
                )
            } catch (error: Throwable) {
                fatalEvent = EngineEvent.Failed(
                    EngineFailureKind.PROTOCOL,
                    error.message?.take(MAX_ERROR_LENGTH) ?: "无法配置 VPN TUN 接口",
                )
                cancel()
            }
        }

        fun destroyAndReleaseTunnel() {
            tunnelLease.closeAfterNativeCleanup(::destroy)
        }

        override fun onProgress(level: Int, message: String?) {
            if (level != PRG_ERR || message.isNullOrBlank()) return
            val sanitized = sanitizeNativeMessage(message)
            recordFailure(OpenConnectFailurePolicy.failure(sanitized))
        }

        fun failureEvent(fallback: String): EngineEvent.Failed {
            fatalEvent?.let { return it }
            if (cancelled.get() || isCanceled) {
                return EngineEvent.Failed(EngineFailureKind.CANCELLED, "连接已取消")
            }
            return lastFailure ?: OpenConnectFailurePolicy.failure(fallback)
        }

        private fun recordFailure(candidate: EngineEvent.Failed) {
            lastFailure = OpenConnectFailurePolicy.prefer(lastFailure, candidate)
        }

        private fun AuthForm.sslGroups(): List<SslGroup> {
            val groupOption = authgroupOpt ?: return emptyList()
            return groupOption.choices.mapIndexedNotNull { index, choice ->
                val value = choice.name?.takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
                SslGroup(
                    value = value,
                    label = choice.overrideLabel?.takeIf(String::isNotBlank)
                        ?: choice.label?.takeIf(String::isNotBlank)
                        ?: value,
                    isDefault = index == authgroupSelection,
                )
            }
        }

        private fun AuthForm.safeReason(): String = sequenceOf(error, message, banner)
            .filterNotNull()
            .map(::sanitizeNativeMessage)
            .firstOrNull(String::isNotBlank)
            .orEmpty()
    }

    private companion object {
        val USER_AGENT = "GeekOCTunnel Android/${BuildConfig.VERSION_NAME}"
        const val ANYCONNECT_PROTOCOL = "anyconnect"
        const val DTLS_ATTEMPT_SECONDS = 60
        const val RECONNECT_TIMEOUT_SECONDS = 300
        const val MAX_ERROR_LENGTH = 240

        val SECRET_VALUE = Regex("(?i)(password|passwd|cookie|authorization)\\s*[:=]\\s*[^\\s,;]+")

        fun sanitizeNativeMessage(value: String): String = SECRET_VALUE
            .replace(value.replace('\n', ' ').replace('\r', ' ').trim(), "$1=<redacted>")
            .take(MAX_ERROR_LENGTH)

        fun defaultTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        }
    }
}

private object OpenConnectNativeLibrary {
    val loadFailure: Throwable? by lazy {
        runCatching { System.loadLibrary("openconnect") }.exceptionOrNull()
    }
}
