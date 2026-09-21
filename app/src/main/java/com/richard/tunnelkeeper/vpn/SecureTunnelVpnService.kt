package com.richard.tunnelkeeper.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import androidx.core.app.NotificationCompat
import com.richard.tunnelkeeper.MainActivity
import com.richard.tunnelkeeper.R
import com.richard.tunnelkeeper.data.ProfileRepository
import com.richard.tunnelkeeper.data.SecureCredentialStore
import com.richard.tunnelkeeper.model.ConnectionProfile
import com.richard.tunnelkeeper.model.VpnCredentials
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SecureTunnelVpnService : VpnService() {
    private data class AuthenticationHint(
        val username: String,
        val sslGroup: String,
    )

    private data class ActiveAttempt(
        val id: String,
        val profile: ConnectionProfile,
        val session: OpenConnectSession,
        var credentials: VpnCredentials?,
        var authenticationHint: AuthenticationHint?,
        var saveOnSuccess: Boolean,
        var activeChallengeId: String?,
        var authenticationRestartScheduled: Boolean,
        var job: Job? = null,
        var finishing: Boolean = false,
        var pendingDiagnostics: TunnelDiagnostics? = null,
    )

    private val engine: OpenConnectEngine = NativeOpenConnectEngine()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandMutex = Mutex()
    private lateinit var credentialStore: SecureCredentialStore
    private val attempts = linkedMapOf<String, ActiveAttempt>()
    private var router: AndroidMultiTunnel? = null
    private var routerToken: Any? = null
    private var latestStartId = 0
    private var revocationInProgress = false
    private var startingBatch = false

    override fun onCreate() {
        super.onCreate()
        credentialStore = SecureCredentialStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.vpn_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_CONNECT -> {
                val profileIds = intent.getStringArrayListExtra(EXTRA_PROFILE_IDS)
                    ?: listOfNotNull(intent.getStringExtra(EXTRA_PROFILE_ID))
                try {
                    startForeground(NOTIFICATION_ID, createNotification(getString(R.string.app_name), "正在启动 VPN"))
                } catch (error: Throwable) {
                    profileIds.forEach { failWithoutAttempt(it, error.message ?: "无法启动 VPN 前台服务", startId) }
                    return START_NOT_STICKY
                }
                serviceScope.launch {
                    commandMutex.withLock {
                        if (profileIds.isEmpty()) failWithoutAttempt(null, "请先选择连接配置", startId)
                        if (router == null && profileIds.isNotEmpty()) {
                            try { router = createRouter(profileIds.toSet()) } catch (error: Throwable) {
                                profileIds.forEach { failWithoutAttempt(it, error.message ?: "无法创建分流器", startId) }
                                return@withLock
                            }
                        }
                        startingBatch = true
                        try { profileIds.distinct().forEach { startConnection(it, forceInteractive = false, startId = startId) } }
                        finally { startingBatch = false }
                        if (attempts.isEmpty()) stopIfIdle(startId)
                    }
                }
            }

            ACTION_SUBMIT_AUTHENTICATION -> submitAuthentication(
                sessionId = intent.getStringExtra(EXTRA_SESSION_ID),
                challengeId = intent.getStringExtra(EXTRA_CHALLENGE_ID),
                startId = startId,
            )

            ACTION_DISCONNECT -> serviceScope.launch {
                commandMutex.withLock {
                    disconnect(startId, userInitiated = true, profileId = intent.getStringExtra(EXTRA_PROFILE_ID))
                }
            }

            else -> if (attempts.isEmpty()) stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun startConnection(
        profileId: String?,
        forceInteractive: Boolean,
        startId: Int,
        authenticationHint: AuthenticationHint? = null,
    ) {
        if (profileId != null && attempts.containsKey(profileId)) return
        if (profileId != null && router?.profileIds?.contains(profileId) == false) {
            failWithoutAttempt(profileId, "请先全部断开，再通过「并行连接」选择新的服务器组合", startId)
            return
        }
        val currentRouter = router
        if (profileId != null && currentRouter?.isReady == true && !currentRouter.hasConfiguredRoutes(profileId)) {
            failWithoutAttempt(profileId, "此配置首次连接未完成，尚未安装路由；请全部断开后重新连接组合", startId)
            return
        }
        val profile = runCatching {
            ProfileRepository(this).load().firstOrNull { it.id == profileId }
        }.getOrElse { error ->
            failWithoutAttempt(profileId, error.message ?: "无法读取连接配置", startId)
            return
        }
        if (profile == null) {
            failWithoutAttempt(profileId, "找不到连接配置", startId)
            return
        }
        if (prepare(this) != null) {
            failWithoutAttempt(profile.id, "尚未授予 VPN 权限", startId)
            return
        }

        val savedCredentials = if (forceInteractive) {
            null
        } else {
            runCatching { credentialStore.read(profile.id) }.getOrElse { error ->
                ConnectionLog.add("无法读取保存的认证信息：${error.message ?: "凭据存储异常"}")
                null
            }
        }
        val session = runCatching { engine.createSession() }.getOrElse { error ->
            failWithoutAttempt(profile.id, error.message ?: "无法创建 OpenConnect 会话", startId)
            return
        }
        val attempt = ActiveAttempt(
            id = UUID.randomUUID().toString(),
            profile = profile,
            session = session,
            credentials = savedCredentials,
            authenticationHint = authenticationHint ?: savedCredentials?.let {
                AuthenticationHint(username = it.username, sslGroup = it.sslGroup)
            },
            saveOnSuccess = false,
            activeChallengeId = null,
            authenticationRestartScheduled = false,
        )
        if (router == null) {
            router = runCatching {
                createRouter(setOf(profile.id))
            }.getOrElse { error ->
                runCatching { session.cancel() }
                failWithoutAttempt(profile.id, error.message ?: "无法创建分流器", startId)
                return
            }
        }
        attempts[profile.id] = attempt
        val platform = checkNotNull(router).platform(profile.id, profile.name, attempt.id)

        updateNotification(profile.name, "正在认证")
        ConnectionLog.add(
            if (savedCredentials == null) {
                "[${profile.name}] 正在获取认证信息和 SSL Group"
            } else {
                "[${profile.name}] 正在使用保存的凭据和 SSL Group"
            },
        )
        updateConnecting(attempt, ConnectionStage.STARTING_AUTHENTICATION)

        attempt.job = serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    session.run(
                        profile = profile,
                        savedCredentials = savedCredentials,
                        platform = platform,
                    ) { event ->
                        serviceScope.launch { handleEngineEvent(attempt.id, event) }
                    }
                }
            } catch (_: CancellationException) {
                // The command coordinator already owns the terminal state for an intentional cancel.
            } catch (error: Throwable) {
                handleEngineEvent(
                    attempt.id,
                    EngineEvent.Failed(
                        EngineFailureKind.UNKNOWN,
                        error.message ?: "OpenConnect 会话异常结束",
                    ),
                )
            }
        }
    }

    private fun submitAuthentication(sessionId: String?, challengeId: String?, startId: Int) {
        if (sessionId == null || challengeId == null) {
            if (attempts.isEmpty()) stopSelfResult(startId)
            return
        }
        val submission = SessionCredentialCache.take(challengeId) ?: return
        val attempt = attempts.values.firstOrNull { it.session.id == sessionId }
        if (
            attempt == null ||
            attempt.session.id != sessionId ||
            attempt.activeChallengeId != challengeId
        ) {
            if (attempts.isEmpty()) stopSelfResult(startId)
            return
        }

        attempt.activeChallengeId = null
        attempt.credentials = submission.credentials
        attempt.authenticationHint = AuthenticationHint(
            username = submission.credentials.username,
            sslGroup = submission.credentials.sslGroup,
        )
        attempt.saveOnSuccess = submission.saveOnSuccess
        updateConnecting(attempt, ConnectionStage.AUTHENTICATING)
        ConnectionLog.add("[${attempt.profile.name}] 正在验证账号和 SSL Group")
        runCatching { attempt.session.submitAuthentication(challengeId, submission.credentials) }
            .onFailure { error ->
                handleEngineEvent(
                    attempt.id,
                    EngineEvent.Failed(
                        EngineFailureKind.UNKNOWN,
                        error.message ?: "无法提交认证信息",
                    ),
                )
            }
    }

    private fun handleEngineEvent(attemptId: String, event: EngineEvent) {
        val attempt = attempts.values.firstOrNull { it.id == attemptId && !it.finishing } ?: return
        when (event) {
            is EngineEvent.AwaitingAuthentication -> {
                attempt.activeChallengeId?.let(SessionCredentialCache::remove)
                attempt.activeChallengeId = event.challengeId
                val groups = OpenConnectAuthFormPolicy.preferSslGroup(
                    groups = event.sslGroups,
                    preferredValue = attempt.authenticationHint?.sslGroup.orEmpty(),
                )
                val suggestedUsername = event.suggestedUsername.ifBlank {
                    attempt.authenticationHint?.username.orEmpty()
                }
                if (event.savedCredentialsRejected) {
                    runCatching { credentialStore.remove(attempt.profile.id) }
                        .onFailure { ConnectionLog.add("无法删除失效凭据：${it.message ?: "凭据存储异常"}") }
                    attempt.credentials = null
                    attempt.saveOnSuccess = false
                    ConnectionLog.add("保存的认证信息已被服务器拒绝，请重新输入")
                } else {
                    ConnectionLog.add("服务器返回 ${groups.size} 个 SSL Group")
                }
                ConnectionStateStore.update(
                    ConnectionState.AuthenticationRequired(
                        profileId = attempt.profile.id,
                        profileName = attempt.profile.name,
                        sessionId = attempt.session.id,
                        challengeId = event.challengeId,
                        sslGroups = groups,
                        reason = event.reason,
                        suggestedUsername = suggestedUsername,
                    ),
                )
                updateNotification(attempt.profile.name, "等待认证")
            }

            EngineEvent.Authenticating -> updateConnecting(attempt, ConnectionStage.AUTHENTICATING)
            EngineEvent.ConfiguringTunnel -> updateConnecting(attempt, ConnectionStage.CONFIGURING_TUNNEL)
            is EngineEvent.Warning -> ConnectionLog.add("[${attempt.profile.name}] ${event.message}")
            is EngineEvent.TunnelEstablished -> {
                attempt.pendingDiagnostics = event.diagnostics
                if (router?.isReady == true) completeReadyAttempts()
                else updateConnecting(attempt, ConnectionStage.CONFIGURING_TUNNEL)
            }

            is EngineEvent.Failed -> when {
                event.kind == EngineFailureKind.CANCELLED -> finishAttempt(attempt, keepFailureState = false)
                event.kind == EngineFailureKind.AUTHENTICATION &&
                    attempt.credentials != null &&
                    !attempt.authenticationRestartScheduled -> restartAfterAuthenticationFailure(attempt)
                else -> {
                    ConnectionLog.add("[${attempt.profile.name}] 连接失败：${event.message}")
                    ConnectionStateStore.update(ConnectionState.Failed(attempt.profile.id, event.message))
                    finishAttempt(attempt, keepFailureState = true)
                }
            }

            EngineEvent.Disconnected -> {
                val wasConnected = ConnectionStateStore.profiles.value[attempt.profile.id] is ConnectionState.Connected
                if (wasConnected) ConnectionLog.add("[${attempt.profile.name}] VPN 连接已结束")
                finishAttempt(attempt, keepFailureState = false)
            }
        }
    }

    private fun createRouter(profileIds: Set<String>): AndroidMultiTunnel {
        val token = Any()
        routerToken = token
        return AndroidMultiTunnel(
            this, profileIds,
            onReady = { serviceScope.launch { if (routerToken === token) completeReadyAttempts() } },
            onFailure = { message ->
                serviceScope.launch {
                    commandMutex.withLock {
                        if (routerToken !== token) return@withLock
                        ConnectionLog.add("共享 VPN 接口异常：$message")
                        val ids = attempts.keys.toList()
                        disconnect(latestStartId, userInitiated = false)
                        ids.forEach { ConnectionStateStore.update(ConnectionState.Failed(it, message)) }
                    }
                }
            },
        )
    }

    private fun completeReadyAttempts() {
        if (router?.isReady != true) return
        attempts.values.filterNot { it.finishing }.forEach { attempt ->
            val diagnostics = attempt.pendingDiagnostics ?: return@forEach
            attempt.pendingDiagnostics = null
            if (attempt.saveOnSuccess) attempt.credentials?.let { value ->
                runCatching { credentialStore.save(attempt.profile.id, value) }
                    .onFailure { ConnectionLog.add("[${attempt.profile.name}] VPN 已连接，但保存密码失败：${it.message}") }
            }
            TunnelLogFormatter.lines(diagnostics).forEach { ConnectionLog.add("[${attempt.profile.name}] $it") }
            ConnectionLog.add("已连接 ${attempt.profile.name}")
            ConnectionStateStore.update(ConnectionState.Connected(attempt.profile.id, attempt.profile.name))
        }
        rememberActiveProfiles()
        updateNotification("", "VPN 已连接")
    }

    private fun restartAfterAuthenticationFailure(attempt: ActiveAttempt) {
        attempt.authenticationRestartScheduled = true
        val authenticationHint = attempt.authenticationHint
        ConnectionLog.add("认证被服务器拒绝，正在重新获取 SSL Group")
        runCatching { credentialStore.remove(attempt.profile.id) }
            .onFailure { ConnectionLog.add("无法删除失效凭据：${it.message ?: "凭据存储异常"}") }
        serviceScope.launch {
            commandMutex.withLock {
                if (attempts[attempt.profile.id]?.id != attempt.id) return@withLock
                val profileId = attempt.profile.id
                val startId = latestStartId
                cancelAttemptAndJoin(profileId)
                startConnection(
                    profileId = profileId,
                    forceInteractive = true,
                    startId = startId,
                    authenticationHint = authenticationHint,
                )
            }
        }
    }

    private fun updateConnecting(attempt: ActiveAttempt, stage: ConnectionStage) {
        ConnectionStateStore.update(
            ConnectionState.Connecting(
                profileId = attempt.profile.id,
                profileName = attempt.profile.name,
                stage = stage,
            ),
        )
    }

    private suspend fun disconnect(startId: Int, userInitiated: Boolean, profileId: String? = null) {
        val targets = if (profileId == null) attempts.keys.toList() else listOf(profileId)
        targets.forEach { id ->
            val name = attempts[id]?.profile?.name
            cancelAttemptAndJoin(id)
            router?.unavailable(id)
            ConnectionStateStore.update(id, ConnectionState.Disconnected)
            if (name != null) ConnectionLog.add("[$name] 已断开连接")
        }
        if (profileId == null) ConnectionStateStore.update(ConnectionState.Disconnected)
        if (userInitiated && profileId != null && attempts.isNotEmpty()) rememberActiveProfiles()
        stopIfIdle(startId)
    }

    private suspend fun cancelAttemptAndJoin(profileId: String) {
        val attempt = attempts.remove(profileId)
        attempt?.activeChallengeId?.let(SessionCredentialCache::remove)
        runCatching { attempt?.session?.cancel() }
            .onFailure { ConnectionLog.add("取消 OpenConnect 会话失败：${it.message ?: "未知错误"}") }
        attempt?.job?.cancelAndJoin()
    }

    private fun finishAttempt(attempt: ActiveAttempt, keepFailureState: Boolean) {
        if (attempts[attempt.profile.id]?.id != attempt.id || attempt.finishing) return
        attempt.finishing = true
        val finishingStartId = latestStartId
        serviceScope.launch {
            commandMutex.withLock {
                if (attempts[attempt.profile.id]?.id != attempt.id) return@withLock
                cancelAttemptAndJoin(attempt.profile.id)
                router?.unavailable(attempt.profile.id)
                if (!keepFailureState) ConnectionStateStore.update(attempt.profile.id, ConnectionState.Disconnected)
                stopIfIdle(finishingStartId)
            }
        }
    }

    private suspend fun stopIfIdle(startId: Int) {
        if (attempts.isNotEmpty()) {
            updateNotification("", "")
            return
        }
        val closing = router
        router = null
        routerToken = null
        withContext(Dispatchers.IO) { closing?.close() }
        if (startId == latestStartId) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
    }

    private fun rememberActiveProfiles() {
        runCatching { ProfileRepository(this).saveQuickConnectProfiles(attempts.keys.toList()) }
            .onFailure { ConnectionLog.add("无法保存快捷连接组合：${it.message}") }
    }

    private fun failWithoutAttempt(profileId: String?, message: String, startId: Int) {
        profileId?.let { router?.unavailable(it) }
        ConnectionLog.add("连接失败：$message")
        if (profileId != null) ConnectionStateStore.update(ConnectionState.Failed(profileId, message))
        else ConnectionLog.add(message)
        if (attempts.isEmpty() && !startingBatch && startId == latestStartId) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        } else updateNotification("", "")
    }

    private fun createNotification(profileName: String, status: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_quick_connect_tile)
        .setContentTitle(profileName)
        .setContentText(status)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setOngoing(true)
        .build()

    private fun updateNotification(profileName: String, status: String) {
        val states = ConnectionStateStore.profiles.value.values
        val connected = states.count { it is ConnectionState.Connected }
        val pending = states.count { it is ConnectionState.Connecting || it is ConnectionState.AuthenticationRequired }
        val summary = if (states.size > 1) "已连接 $connected 个 · 等待 $pending 个" else status
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            createNotification(if (states.size > 1 || profileName.isBlank()) getString(R.string.app_name) else profileName, summary),
        )
    }

    override fun onRevoke() {
        ConnectionLog.add("系统已撤销 VPN 权限")
        revocationInProgress = true
        attempts.values.forEach { attempt ->
            attempt.activeChallengeId?.let(SessionCredentialCache::remove)
            runCatching { attempt.session.cancel() }
            attempt.job?.cancel()
        }
        ConnectionStateStore.update(ConnectionState.Disconnected)
        serviceScope.cancel()
        super.onRevoke()
    }

    override fun onDestroy() {
        val interrupted = attempts.values.toList()
        attempts.clear()
        interrupted.forEach { attempt ->
            attempt.activeChallengeId?.let(SessionCredentialCache::remove)
            runCatching { attempt.session.cancel() }
            attempt.job?.cancel()
            if (!revocationInProgress && ConnectionStateStore.profiles.value[attempt.profile.id] !is ConnectionState.Failed) {
                ConnectionStateStore.update(ConnectionState.Failed(attempt.profile.id, "VPN 服务已停止"))
            }
        }
        routerToken = null
        router?.close()
        router = null
        SessionCredentialCache.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "com.richard.tunnelkeeper.CONNECT"
        const val ACTION_SUBMIT_AUTHENTICATION = "com.richard.tunnelkeeper.SUBMIT_AUTHENTICATION"
        const val ACTION_DISCONNECT = "com.richard.tunnelkeeper.DISCONNECT"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_PROFILE_IDS = "profile_ids"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_CHALLENGE_ID = "challenge_id"
        private const val CHANNEL_ID = "vpn_connection"
        private const val NOTIFICATION_ID = 1001
    }
}
