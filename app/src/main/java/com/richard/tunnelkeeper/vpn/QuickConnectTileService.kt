package com.richard.tunnelkeeper.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.richard.tunnelkeeper.MainActivity
import com.richard.tunnelkeeper.R
import com.richard.tunnelkeeper.data.ProfileRepository
import com.richard.tunnelkeeper.data.SecureCredentialStore
import com.richard.tunnelkeeper.model.ConnectionProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal sealed interface QuickTileAction {
    data object Disconnect : QuickTileAction
    data class Connect(val profileId: String) : QuickTileAction
    data object OpenApp : QuickTileAction
}

internal fun resolveQuickTileAction(
    state: ConnectionState,
    selectedProfileId: String?,
    hasSavedCredentials: Boolean,
    vpnPermissionGranted: Boolean,
): QuickTileAction = when (state) {
    is ConnectionState.Connected,
    is ConnectionState.Connecting,
    is ConnectionState.AuthenticationRequired,
    -> QuickTileAction.Disconnect

    ConnectionState.Disconnected,
    is ConnectionState.Failed,
    -> if (selectedProfileId != null && hasSavedCredentials && vpnPermissionGranted) {
        QuickTileAction.Connect(selectedProfileId)
    } else {
        QuickTileAction.OpenApp
    }
}

class QuickConnectTileService : TileService() {
    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        stateJob?.cancel()
        stateJob = tileScope.launch {
            ConnectionStateStore.state.collectLatest { state ->
                refreshTile(state, loadSelectedProfile()?.name)
            }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        stateJob?.cancel()
        tileScope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val state = ConnectionStateStore.state.value
        if (resolveQuickTileAction(state, null, false, false) == QuickTileAction.Disconnect) {
            requestDisconnect()
            return
        }

        val profiles = runCatching { ProfileRepository(applicationContext).loadQuickConnectProfiles() }.getOrDefault(emptyList())
        val profile = profiles.firstOrNull()
        val hasSavedCredentials = profiles.isNotEmpty() && runCatching {
            val credentials = SecureCredentialStore(applicationContext)
            profiles.all { credentials.read(it.id) != null }
        }.getOrDefault(false)
        val vpnPermissionGranted = profile != null &&
            hasSavedCredentials &&
            VpnService.prepare(this) == null

        when (
            val action = resolveQuickTileAction(
                state = state,
                selectedProfileId = profile?.id,
                hasSavedCredentials = hasSavedCredentials,
                vpnPermissionGranted = vpnPermissionGranted,
            )
        ) {
            QuickTileAction.Disconnect -> requestDisconnect()
            is QuickTileAction.Connect -> startQuickConnection(profiles)
            QuickTileAction.OpenApp -> {
                val reason = when {
                    profile == null -> "快捷设置：请先在应用内选择连接配置"
                    !hasSavedCredentials -> "快捷设置：请先在应用内连接并保存密码"
                    else -> "快捷设置：请先在应用内授予 VPN 权限"
                }
                openMainActivity(reason)
            }
        }
    }

    private fun requestDisconnect() {
        runCatching {
            checkNotNull(
                startService(
                    Intent(this, SecureTunnelVpnService::class.java)
                        .setAction(SecureTunnelVpnService.ACTION_DISCONNECT),
                ),
            )
        }.onSuccess {
            updateTile(Tile.STATE_INACTIVE, getString(R.string.quick_settings_tile_disconnecting))
        }.onFailure { error ->
            openMainActivity("快捷设置无法断开 VPN：${error.message ?: "服务不可用"}")
        }
    }

    private fun startQuickConnection(profiles: List<ConnectionProfile>) {
        profiles.forEach { profile -> ConnectionStateStore.update(
            ConnectionState.Connecting(
                profileId = profile.id,
                profileName = profile.name,
                stage = ConnectionStage.STARTING_AUTHENTICATION,
            ),
        ) }
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, SecureTunnelVpnService::class.java)
                    .setAction(SecureTunnelVpnService.ACTION_CONNECT)
                    .putStringArrayListExtra(SecureTunnelVpnService.EXTRA_PROFILE_IDS, ArrayList(profiles.map { it.id })),
            )
        }.onSuccess {
            refreshTile(ConnectionStateStore.state.value, profiles.firstOrNull()?.name)
        }.onFailure { error ->
            val message = error.message ?: "无法启动 VPN 服务"
            ConnectionLog.add("快捷设置连接失败：$message")
            profiles.forEach { ConnectionStateStore.update(ConnectionState.Failed(it.id, message)) }
            refreshTile(ConnectionStateStore.state.value, profiles.firstOrNull()?.name)
            openMainActivity(null)
        }
    }

    private fun loadSelectedProfile(): ConnectionProfile? = runCatching {
        val repository = ProfileRepository(applicationContext)
        val profiles = repository.load()
        val selectedProfileId = repository.loadSelectedProfileId(profiles)
        profiles.firstOrNull { it.id == selectedProfileId }
    }.getOrNull()

    private fun refreshTile(state: ConnectionState, selectedProfileName: String?) {
        val subtitle = when (state) {
            is ConnectionState.Connected -> getString(R.string.quick_settings_tile_connected, state.profileName)
            is ConnectionState.Connecting -> getString(R.string.quick_settings_tile_connecting, state.profileName)
            is ConnectionState.AuthenticationRequired ->
                getString(R.string.quick_settings_tile_waiting_for_authentication, state.profileName)
            is ConnectionState.Failed -> getString(R.string.quick_settings_tile_failed)
            ConnectionState.Disconnected -> selectedProfileName?.let {
                getString(R.string.quick_settings_tile_disconnected, it)
            } ?: getString(R.string.quick_settings_tile_not_configured)
        }
        val tileState = when (state) {
            is ConnectionState.Connected,
            is ConnectionState.Connecting,
            is ConnectionState.AuthenticationRequired,
            -> Tile.STATE_ACTIVE

            ConnectionState.Disconnected,
            is ConnectionState.Failed,
            -> Tile.STATE_INACTIVE
        }
        updateTile(tileState, subtitle)
    }

    private fun updateTile(state: Int, subtitle: String) {
        val tile = qsTile ?: return
        tile.state = state
        tile.label = getString(R.string.quick_settings_tile_label)
        tile.contentDescription = "${tile.label}，$subtitle"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
        tile.updateTile()
    }

    private fun openMainActivity(logMessage: String?) {
        logMessage?.let(ConnectionLog::add)
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                OPEN_APP_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val OPEN_APP_REQUEST_CODE = 2001
    }
}
