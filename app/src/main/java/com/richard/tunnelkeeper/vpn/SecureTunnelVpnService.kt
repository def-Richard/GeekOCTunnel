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
    )

    private val engine: OpenConnectEngine = NativeOpenConnectEngine()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandMutex = Mutex()
    private lateinit var credentialStore: SecureCredentialStore
    private var activeAttempt: ActiveAttempt? = null
    private var engineJob: Job? = null
    private var latestStartId = 0
    private var revocationInProgress = false

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
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                try {
                    startForeground(NOTIFICATION_ID, createNotification(getString(R.string.app_name), "正在启动 VPN"))
                } catch (error: Throwable) {
                    failWithoutAttempt(profileId, error.message ?: "无法启动 VPN 前台服务", startId)
                    return START_NOT_STICKY
                }
                serviceScope.launch {
                    commandMutex.withLock {
                        startConnection(profileId, forceInteractive = false, startId = startId)
                    }
                }
            }

            ACTION_SUBMIT_AUTHENTICATION -> submitAuthentication(
                sessionId = intent.getStringExtra(EXTRA_SESSION_ID),
                challengeId = intent.getStringExtra(EXTRA_CHALLENGE_ID),
                startId = startId,
            )

            ACTION_DISCONNECT -> serviceScope.launch {
                commandMutex.withLock { disconnect(startId, userInitiated = true) }
            }

            else -> stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun startConnection(
        profileId: String?,
        forceInteractive: Boolean,
        startId: Int,
        authenticationHint: AuthenticationHint? = null,
    ) {
        cancelActiveAttemptAndJoin()
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
        activeAttempt = attempt

        updateNotification(profile.name, "正在认证")
        ConnectionLog.add(
            if (savedCredentials == null) {
                "正在获取认证信息和 SSL Group"
            } else {
                "正在使用保存的凭据和 SSL Group"
            },
        )
        updateConnecting(attempt, ConnectionStage.STARTING_AUTHENTICATION)

        engineJob = serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    session.run(
                        profile = profile,
                        savedCredentials = savedCredentials,
                        platform = AndroidVpnPlatform(this@SecureTunnelVpnService),
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
            if (activeAttempt == null) stopSelfResult(startId)
            return
        }
        val submission = SessionCredentialCache.take(challengeId) ?: return
        val attempt = activeAttempt
        if (
            attempt == null ||
            attempt.session.id != sessionId ||
            attempt.activeChallengeId != challengeId
        ) {
            if (attempt == null) stopSelfResult(startId)
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
        ConnectionLog.add("正在验证账号和 SSL Group")
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
        val attempt = activeAttempt?.takeIf { it.id == attemptId } ?: return
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
            is EngineEvent.Warning -> ConnectionLog.add(event.message)
            is EngineEvent.TunnelEstablished -> {
                if (attempt.saveOnSuccess) {
                    attempt.credentials?.let { value ->
                        runCatching { credentialStore.save(attempt.profile.id, value) }
                        .onFailure { ConnectionLog.add("VPN 已连接，但保存密码失败：${it.message ?: "凭据存储异常"}") }
                    }
                }
                TunnelLogFormatter.lines(event.diagnostics).forEach(ConnectionLog::add)
                ConnectionLog.add("已连接 ${attempt.profile.name}")
                ConnectionStateStore.update(ConnectionState.Connected(attempt.profile.id, attempt.profile.name))
                updateNotification(attempt.profile.name, "VPN 已连接")
            }

            is EngineEvent.Failed -> when {
                event.kind == EngineFailureKind.CANCELLED -> finishAttempt(attempt, keepFailureState = false)
                event.kind == EngineFailureKind.AUTHENTICATION &&
                    attempt.credentials != null &&
                    !attempt.authenticationRestartScheduled -> restartAfterAuthenticationFailure(attempt)
                else -> {
                    ConnectionLog.add("连接失败：${event.message}")
                    ConnectionStateStore.update(ConnectionState.Failed(attempt.profile.id, event.message))
                    finishAttempt(attempt, keepFailureState = true)
                }
            }

            EngineEvent.Disconnected -> {
                val wasConnected = ConnectionStateStore.state.value is ConnectionState.Connected
                if (wasConnected) ConnectionLog.add("VPN 连接已结束")
                finishAttempt(attempt, keepFailureState = false)
            }
        }
    }

    private fun restartAfterAuthenticationFailure(attempt: ActiveAttempt) {
        attempt.authenticationRestartScheduled = true
        val authenticationHint = attempt.authenticationHint
        ConnectionLog.add("认证被服务器拒绝，正在重新获取 SSL Group")
        runCatching { credentialStore.remove(attempt.profile.id) }
            .onFailure { ConnectionLog.add("无法删除失效凭据：${it.message ?: "凭据存储异常"}") }
        serviceScope.launch {
            commandMutex.withLock {
                if (activeAttempt?.id != attempt.id) return@withLock
                val profileId = attempt.profile.id
                val startId = latestStartId
                cancelActiveAttemptAndJoin()
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

    private suspend fun disconnect(startId: Int, userInitiated: Boolean) {
        val hadAttempt = activeAttempt != null
        cancelActiveAttemptAndJoin()
        if (userInitiated || hadAttempt) ConnectionLog.add("已断开连接")
        ConnectionStateStore.update(ConnectionState.Disconnected)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    private suspend fun cancelActiveAttemptAndJoin() {
        val attempt = activeAttempt
        val job = engineJob
        activeAttempt = null
        engineJob = null
        attempt?.activeChallengeId?.let(SessionCredentialCache::remove)
        runCatching { attempt?.session?.cancel() }
            .onFailure { ConnectionLog.add("取消 OpenConnect 会话失败：${it.message ?: "未知错误"}") }
        job?.cancelAndJoin()
    }

    private fun finishAttempt(attempt: ActiveAttempt, keepFailureState: Boolean) {
        if (activeAttempt?.id != attempt.id) return
        activeAttempt = null
        attempt.activeChallengeId?.let(SessionCredentialCache::remove)
        runCatching { attempt.session.cancel() }
        engineJob?.cancel()
        engineJob = null
        if (!keepFailureState) ConnectionStateStore.update(ConnectionState.Disconnected)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(latestStartId)
    }

    private fun failWithoutAttempt(profileId: String?, message: String, startId: Int) {
        ConnectionLog.add("连接失败：$message")
        ConnectionStateStore.update(ConnectionState.Failed(profileId, message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
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
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            createNotification(profileName, status),
        )
    }

    override fun onRevoke() {
        ConnectionLog.add("系统已撤销 VPN 权限")
        revocationInProgress = true
        val attempt = activeAttempt
        attempt?.activeChallengeId?.let(SessionCredentialCache::remove)
        runCatching { attempt?.session?.cancel() }
            .onFailure { ConnectionLog.add("取消 OpenConnect 会话失败：${it.message ?: "未知错误"}") }
        engineJob?.cancel()
        ConnectionStateStore.update(ConnectionState.Disconnected)
        serviceScope.cancel()
        super.onRevoke()
    }

    override fun onDestroy() {
        val interruptedAttempt = activeAttempt
        activeAttempt = null
        interruptedAttempt?.activeChallengeId?.let(SessionCredentialCache::remove)
        runCatching { interruptedAttempt?.session?.cancel() }
        engineJob?.cancel()
        engineJob = null
        SessionCredentialCache.clear()
        if (
            interruptedAttempt != null &&
            !revocationInProgress &&
            ConnectionStateStore.state.value !is ConnectionState.Failed
        ) {
            ConnectionStateStore.update(
                ConnectionState.Failed(interruptedAttempt.profile.id, "VPN 服务已停止"),
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "com.richard.tunnelkeeper.CONNECT"
        const val ACTION_SUBMIT_AUTHENTICATION = "com.richard.tunnelkeeper.SUBMIT_AUTHENTICATION"
        const val ACTION_DISCONNECT = "com.richard.tunnelkeeper.DISCONNECT"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_CHALLENGE_ID = "challenge_id"
        private const val CHANNEL_ID = "vpn_connection"
        private const val NOTIFICATION_ID = 1001
    }
}
