package com.richard.tunnelkeeper

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.richard.tunnelkeeper.data.ProfileRepository
import com.richard.tunnelkeeper.data.SecureCredentialStore
import com.richard.tunnelkeeper.model.ConnectionProfile
import com.richard.tunnelkeeper.model.ProfileValidator
import com.richard.tunnelkeeper.model.SslGroup
import com.richard.tunnelkeeper.model.VpnCredentials
import com.richard.tunnelkeeper.ui.theme.GeekBackground
import com.richard.tunnelkeeper.ui.theme.GeekError
import com.richard.tunnelkeeper.ui.theme.GeekGreen
import com.richard.tunnelkeeper.ui.theme.GeekLime
import com.richard.tunnelkeeper.ui.theme.GeekOCTunnelTheme
import com.richard.tunnelkeeper.ui.theme.GeekOutline
import com.richard.tunnelkeeper.ui.theme.GeekPanel
import com.richard.tunnelkeeper.ui.theme.GeekPanelRaised
import com.richard.tunnelkeeper.ui.theme.GeekPurple
import com.richard.tunnelkeeper.ui.theme.GeekText
import com.richard.tunnelkeeper.ui.theme.GeekTextMuted
import com.richard.tunnelkeeper.ui.theme.GeekWarning
import com.richard.tunnelkeeper.vpn.ConnectionLog
import com.richard.tunnelkeeper.vpn.ConnectionLogEntry
import com.richard.tunnelkeeper.vpn.ConnectionStage
import com.richard.tunnelkeeper.vpn.ConnectionState
import com.richard.tunnelkeeper.vpn.ConnectionStateStore
import com.richard.tunnelkeeper.vpn.PendingCredentials
import com.richard.tunnelkeeper.vpn.SecureTunnelVpnService
import com.richard.tunnelkeeper.vpn.SessionCredentialCache

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(GeekBackground.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(GeekBackground.toArgb()),
        )
        val profiles = ProfileRepository(applicationContext)
        val credentials = SecureCredentialStore(applicationContext)
        setContent {
            GeekOCTunnelTheme {
                GeekOCTunnelApp(profiles, credentials)
            }
        }
    }
}

@Composable
private fun GeekOCTunnelApp(repository: ProfileRepository, credentials: SecureCredentialStore) {
    val initialProfiles = remember { repository.load() }
    var profiles by remember { mutableStateOf(initialProfiles) }
    var selectedProfileId by rememberSaveable {
        mutableStateOf(repository.loadSelectedProfileId(initialProfiles))
    }
    var editingProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showProfileMenu by remember { mutableStateOf(false) }
    var pendingProfileIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var showParallelSelector by remember { mutableStateOf(false) }
    var parallelIds by remember { mutableStateOf(repository.loadQuickConnectProfiles().map { it.id }.toSet()) }
    val profileStates by ConnectionStateStore.profiles.collectAsStateWithLifecycle()
    val connectedProfileNames = profiles.mapNotNull { profile ->
        (profileStates[profile.id] as? ConnectionState.Connected)?.profileName
    }.joinToString(" · ")
    val connectionState = profileStates[selectedProfileId] ?: ConnectionState.Disconnected
    val activeCount = profileStates.values.count {
        it is ConnectionState.Connected || it is ConnectionState.Connecting || it is ConnectionState.AuthenticationRequired
    }
    val logEntries by ConnectionLog.entries.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }
    val controlsLocked = connectionState !is ConnectionState.Disconnected &&
        connectionState !is ConnectionState.Failed

    fun startProfiles(selected: List<ConnectionProfile>) {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SecureTunnelVpnService::class.java)
                    .setAction(SecureTunnelVpnService.ACTION_CONNECT)
                    .putStringArrayListExtra(SecureTunnelVpnService.EXTRA_PROFILE_IDS, ArrayList(selected.map { it.id })),
            )
        }.onFailure { error ->
            val message = error.message ?: "无法启动 VPN 服务"
            ConnectionLog.add("连接失败：$message")
            selected.forEach { ConnectionStateStore.update(ConnectionState.Failed(it.id, message)) }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val ids = pendingProfileIds.toList()
        pendingProfileIds = arrayListOf()
        if (result.resultCode == Activity.RESULT_OK) {
            val selected = repository.load().filter { it.id in ids }
            if (selected.isNotEmpty()) {
                startProfiles(selected)
            } else {
                ConnectionLog.add("连接配置已不存在，请重新选择")
                ids.forEach { ConnectionStateStore.update(it, ConnectionState.Disconnected) }
            }
        } else {
            ConnectionLog.add("VPN 权限未授予")
            ids.forEach { ConnectionStateStore.update(it, ConnectionState.Disconnected) }
        }
    }

    fun requestProfiles(selected: List<ConnectionProfile>) {
        selected.forEach { profile -> ConnectionStateStore.update(
            ConnectionState.Connecting(
                profileId = profile.id,
                profileName = profile.name,
                stage = ConnectionStage.STARTING_AUTHENTICATION,
            ),
        ) }
        val permissionIntent = VpnService.prepare(context)
        if (permissionIntent == null) {
            startProfiles(selected)
        } else {
            pendingProfileIds = ArrayList(selected.map { it.id })
            permissionLauncher.launch(permissionIntent)
        }
    }

    fun requestConnection(profile: ConnectionProfile) = requestProfiles(listOf(profile))

    fun disconnectProfile(profileId: String?) {
        runCatching {
            context.startService(
                Intent(context, SecureTunnelVpnService::class.java)
                    .setAction(SecureTunnelVpnService.ACTION_DISCONNECT)
                    .putExtra(SecureTunnelVpnService.EXTRA_PROFILE_ID, profileId),
            )
        }.onFailure { error ->
            ConnectionLog.add("断开失败：${error.message ?: "无法访问 VPN 服务"}")
        }
    }

    fun disconnect() = disconnectProfile(selectedProfileId)

    fun switchAccount(profile: ConnectionProfile) {
        if (connectionState !is ConnectionState.Disconnected && connectionState !is ConnectionState.Failed) {
            ConnectionLog.add("请先断开当前连接，再切换账号")
            return
        }
        runCatching { credentials.remove(profile.id) }
            .onSuccess {
                ConnectionLog.add("已清除保存的账号，正在重新获取认证信息")
                requestConnection(profile)
            }
            .onFailure { error ->
                val message = "无法清除保存的账号：${error.message ?: "凭据存储异常"}"
                ConnectionLog.add(message)
                ConnectionStateStore.update(ConnectionState.Failed(profile.id, message))
            }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = GeekBackground) {
        Scaffold(
            containerColor = GeekBackground,
            topBar = {
                GeekTopBar(
                    addEnabled = true,
                    onAddProfile = {
                        editingProfile = null
                        showEditor = true
                    },
                )
            },
        ) { padding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                val useLandscapeLayout = maxWidth >= 600.dp && maxHeight < 520.dp
                if (useLandscapeLayout) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(0.44f).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ConnectionControl(
                                state = connectionState,
                                connectedProfileNames = connectedProfileNames,
                                compact = true,
                                onToggle = { active ->
                                    if (active) disconnect() else selectedProfile?.let(::requestConnection)
                                        ?: ConnectionLog.add("请先新增一个 VPN 配置")
                                },
                            )
                            ProfileSelector(
                                profile = selectedProfile,
                                expanded = showProfileMenu,
                                onExpandedChange = { showProfileMenu = it },
                                profiles = profiles,
                                profileStates = profileStates,
                                enabled = !controlsLocked,
                                onSelected = {
                                    repository.select(it.id)
                                    selectedProfileId = it.id
                                },
                                onEdit = {
                                    editingProfile = selectedProfile
                                    showEditor = true
                                },
                                onSwitchAccount = { selectedProfile?.let(::switchAccount) },
                            )
                            MultiConnectionStatus(activeCount, profiles.size > 1,
                                onParallel = { showParallelSelector = true }, onDisconnectAll = { disconnectProfile(null) })
                        }
                        ActivityLog(
                            logEntries = logEntries,
                            modifier = Modifier.weight(0.56f).fillMaxHeight(),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ConnectionControl(
                            state = connectionState,
                            connectedProfileNames = connectedProfileNames,
                            compact = false,
                            onToggle = { active ->
                                if (active) disconnect() else selectedProfile?.let(::requestConnection)
                                    ?: ConnectionLog.add("请先新增一个 VPN 配置")
                            },
                        )
                        ProfileSelector(
                            profile = selectedProfile,
                            expanded = showProfileMenu,
                            onExpandedChange = { showProfileMenu = it },
                            profiles = profiles,
                            profileStates = profileStates,
                            enabled = !controlsLocked,
                            onSelected = {
                                repository.select(it.id)
                                selectedProfileId = it.id
                            },
                            onEdit = {
                                editingProfile = selectedProfile
                                showEditor = true
                            },
                            onSwitchAccount = { selectedProfile?.let(::switchAccount) },
                        )
                        MultiConnectionStatus(activeCount, profiles.size > 1,
                            onParallel = { showParallelSelector = true }, onDisconnectAll = { disconnectProfile(null) })
                        ActivityLog(
                            logEntries = logEntries,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    if (showParallelSelector) {
        AlertDialog(
            onDismissRequest = { showParallelSelector = false },
            title = { Text("选择同时连接的服务器") },
            text = {
                Column {
                    Text("先选择组合，再统一连接。支持不同的 IPv4 网段；连接后可分别断开、重连。",
                        style = MaterialTheme.typography.bodySmall)
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(profiles, key = { it.id }) { profile ->
                            Row(modifier = Modifier.fillMaxWidth().toggleable(
                                value = profile.id in parallelIds,
                                role = Role.Checkbox,
                                onValueChange = { checked ->
                                    parallelIds = if (checked) parallelIds + profile.id else parallelIds - profile.id
                                },
                            ).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = profile.id in parallelIds, onCheckedChange = null)
                                Column {
                                    Text(profile.name)
                                    Text(profile.server, style = MaterialTheme.typography.bodySmall, color = GeekTextMuted)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = profiles.any { it.id in parallelIds }, onClick = {
                    val selected = profiles.filter { it.id in parallelIds }
                    showParallelSelector = false
                    selectedProfileId = selected.first().id
                    repository.select(selected.first().id)
                    requestProfiles(selected)
                }) { Text("连接所选") }
            },
            dismissButton = { TextButton(onClick = { showParallelSelector = false }) { Text("取消") } },
        )
    }

    if (showEditor) {
        ProfileEditor(
            profile = editingProfile,
            onDismiss = { showEditor = false },
            onSave = { profile ->
                if (editingProfile != null && editingProfile?.server != profile.server) {
                    credentials.remove(profile.id)
                }
                repository.upsert(profile)
                profiles = repository.load()
                repository.select(profile.id)
                selectedProfileId = profile.id
                showEditor = false
            },
        )
    }

    profileStates.values.filterIsInstance<ConnectionState.AuthenticationRequired>().firstOrNull()?.let { authentication ->
        CredentialDialog(
            profileName = authentication.profileName,
            sessionId = authentication.sessionId,
            challengeId = authentication.challengeId,
            message = authentication.reason,
            groups = authentication.sslGroups,
            initialUsername = authentication.suggestedUsername,
            onDismiss = { disconnectProfile(authentication.profileId) },
            onAuthenticate = { username, password, group, rememberPassword ->
                val normalizedUsername = username.trim()
                if (normalizedUsername.isEmpty() || password.isEmpty()) return@CredentialDialog false
                runCatching {
                    SessionCredentialCache.put(
                        authentication.challengeId,
                        PendingCredentials(
                            credentials = VpnCredentials(normalizedUsername, password, group),
                            saveOnSuccess = rememberPassword,
                        ),
                    )
                    checkNotNull(
                        context.startService(
                            Intent(context, SecureTunnelVpnService::class.java)
                                .setAction(SecureTunnelVpnService.ACTION_SUBMIT_AUTHENTICATION)
                                .putExtra(SecureTunnelVpnService.EXTRA_SESSION_ID, authentication.sessionId)
                                .putExtra(SecureTunnelVpnService.EXTRA_CHALLENGE_ID, authentication.challengeId),
                        ),
                    )
                    true
                }.getOrElse { error ->
                    SessionCredentialCache.remove(authentication.challengeId)
                    ConnectionLog.add("无法提交认证信息：${error.message ?: "VPN 服务不可用"}")
                    false
                }
            },
        )
    }
}

@Composable
private fun GeekTopBar(addEnabled: Boolean, onAddProfile: () -> Unit) {
    Surface(color = GeekBackground) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(60.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = buildAnnotatedString {
                    append("GeekOC")
                    withStyle(SpanStyle(color = GeekGreen)) { append("Tunnel") }
                },
                color = GeekText,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.weight(1f))
            IconButton(enabled = addEnabled, onClick = onAddProfile) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = "新增配置",
                    tint = if (addEnabled) GeekText else GeekTextMuted.copy(alpha = 0.45f),
                )
            }
        }
    }
}

private data class ConnectionPresentation(
    val title: String,
    val accent: Color,
    val active: Boolean,
    val busy: Boolean,
)

@Composable
private fun ConnectionControl(
    state: ConnectionState,
    connectedProfileNames: String,
    compact: Boolean,
    onToggle: (active: Boolean) -> Unit,
) {
    val presentation = when (state) {
        ConnectionState.Disconnected -> ConnectionPresentation(
            title = "未连接",
            accent = GeekPurple,
            active = false,
            busy = false,
        )

        is ConnectionState.Connecting -> ConnectionPresentation(
            title = when (state.stage) {
                ConnectionStage.STARTING_AUTHENTICATION -> "正在获取认证信息"
                ConnectionStage.AUTHENTICATING -> "正在认证"
                ConnectionStage.CONFIGURING_TUNNEL -> "正在建立隧道"
            },
            accent = GeekWarning,
            active = true,
            busy = true,
        )

        is ConnectionState.AuthenticationRequired -> ConnectionPresentation(
            title = "等待认证",
            accent = GeekWarning,
            active = true,
            busy = false,
        )

        is ConnectionState.Connected -> ConnectionPresentation(
            title = "隧道已建立",
            accent = GeekGreen,
            active = true,
            busy = false,
        )

        is ConnectionState.Failed -> ConnectionPresentation(
            title = "连接失败",
            accent = GeekError,
            active = false,
            busy = false,
        )
    }
    val outerSize = if (compact) 118.dp else 146.dp
    val buttonSize = if (compact) 82.dp else 104.dp
    val panelHeight = if (compact) 158.dp else 208.dp
    val actionLabel = if (presentation.active) "断开连接" else "连接"
    val powerTint = if (presentation.accent == GeekWarning) GeekBackground else Color.White

    Column(
        modifier = Modifier.fillMaxWidth().height(panelHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(outerSize)) {
                val ringWidth = 1.dp.toPx()
                drawCircle(
                    color = presentation.accent.copy(alpha = 0.12f),
                    radius = size.minDimension * 0.49f,
                    style = Stroke(width = ringWidth),
                )
                drawCircle(
                    color = presentation.accent.copy(alpha = 0.2f),
                    radius = size.minDimension * 0.39f,
                    style = Stroke(width = ringWidth),
                )
            }
            if (presentation.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(buttonSize + 14.dp),
                    color = presentation.accent,
                    trackColor = presentation.accent.copy(alpha = 0.14f),
                    strokeWidth = 2.dp,
                )
            }
            Surface(
                modifier = Modifier
                    .size(buttonSize)
                    .clip(CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = actionLabel,
                        onClick = { onToggle(presentation.active) },
                    ),
                shape = CircleShape,
                color = presentation.accent,
                shadowElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_power),
                        contentDescription = actionLabel,
                        tint = powerTint,
                        modifier = Modifier.size(if (compact) 34.dp else 42.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(if (compact) 5.dp else 9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(7.dp),
                shape = CircleShape,
                color = presentation.accent,
            ) {}
            Spacer(Modifier.width(7.dp))
            Text(
                text = presentation.title,
                color = GeekText,
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = connectedProfileNames,
            color = GeekTextMuted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.widthIn(max = if (compact) 240.dp else 320.dp)
                .horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun ProfileSelector(
    profile: ConnectionProfile?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    profiles: List<ConnectionProfile>,
    profileStates: Map<String, ConnectionState>,
    enabled: Boolean,
    onSelected: (ConnectionProfile) -> Unit,
    onEdit: () -> Unit,
    onSwitchAccount: () -> Unit,
) {
    val canOpen = profiles.isNotEmpty()
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = canOpen, onClick = { onExpandedChange(true) }),
            shape = RoundedCornerShape(8.dp),
            color = GeekPanel,
            border = BorderStroke(1.dp, GeekOutline),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_server),
                    contentDescription = null,
                    tint = if (profile == null) GeekTextMuted else GeekPurple,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile?.name ?: "选择配置",
                        color = if (profile == null) GeekTextMuted else GeekText,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = profile?.server ?: "",
                        color = GeekTextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_down),
                    contentDescription = "选择配置",
                    tint = if (canOpen) GeekTextMuted else GeekTextMuted.copy(alpha = 0.35f),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            containerColor = GeekPanelRaised,
        ) {
            profiles.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Column {
                            val status = when (profileStates[item.id]) {
                                is ConnectionState.Connected -> " · 已连接"
                                is ConnectionState.Connecting -> " · 连接中"
                                is ConnectionState.AuthenticationRequired -> " · 等待认证"
                                is ConnectionState.Failed -> " · 失败"
                                else -> ""
                            }
                            Text(item.name + status, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                item.server,
                                color = GeekTextMuted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    enabled = true,
                    onClick = {
                        onSelected(item)
                        onExpandedChange(false)
                    },
                )
            }
            if (profile != null) {
                HorizontalDivider(color = GeekOutline)
                DropdownMenuItem(
                    text = { Text("编辑当前配置") },
                    leadingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_edit),
                            contentDescription = null,
                            tint = GeekTextMuted,
                        )
                    },
                    enabled = enabled,
                    onClick = {
                        onExpandedChange(false)
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text("切换账号并重新登录") },
                    leadingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_account_switch),
                            contentDescription = null,
                            tint = GeekGreen,
                        )
                    },
                    enabled = enabled,
                    onClick = {
                        onExpandedChange(false)
                        onSwitchAccount()
                    },
                )
            }
        }
    }
}

@Composable
private fun MultiConnectionStatus(activeCount: Int, canParallel: Boolean, onParallel: () -> Unit, onDisconnectAll: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (activeCount == 0) "选择多个服务器，按目标 IPv4 网段分流" else "$activeCount 个连接运行中 · 选择配置可分别查看或断开",
            color = GeekTextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        if (activeCount > 0) TextButton(onClick = onDisconnectAll) { Text("全部断开") }
        else if (canParallel) TextButton(onClick = onParallel) { Text("并行连接") }
    }
}

@Composable
private fun ActivityLog(logEntries: List<ConnectionLogEntry>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(logEntries.size) {
        if (logEntries.isNotEmpty()) listState.scrollToItem(logEntries.lastIndex)
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_terminal),
                contentDescription = null,
                tint = GeekGreen,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "连接日志",
                color = GeekText,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = logEntries.size.toString().padStart(2, '0'),
                color = GeekTextMuted,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(8.dp),
            color = GeekPanel,
            border = BorderStroke(1.dp, GeekOutline),
        ) {
            if (logEntries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "SESSION IDLE",
                        color = GeekTextMuted.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(logEntries) { entry ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = entry.timestamp,
                                color = GeekGreen.copy(alpha = 0.78f),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(64.dp),
                            )
                            Text(
                                text = entry.message,
                                color = GeekText.copy(alpha = 0.9f),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileEditor(
    profile: ConnectionProfile?,
    onDismiss: () -> Unit,
    onSave: (ConnectionProfile) -> Unit,
) {
    var name by remember(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var server by remember(profile?.id) { mutableStateOf(profile?.server.orEmpty()) }
    var ignoreCertificateErrors by remember(profile?.id) {
        mutableStateOf(profile?.ignoreCertificateErrors ?: false)
    }
    var error by remember(profile?.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 440.dp),
        shape = RoundedCornerShape(8.dp),
        containerColor = GeekPanel,
        titleContentColor = GeekText,
        textContentColor = GeekTextMuted,
        title = { Text(if (profile == null) "新增配置" else "编辑配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("名称") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("服务器") },
                    singleLine = true,
                )
                HorizontalDivider(color = GeekOutline)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "忽略所有证书错误",
                            color = if (ignoreCertificateErrors) GeekWarning else GeekText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "仅用于你确认可信的自签名服务器",
                            color = GeekTextMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = ignoreCertificateErrors,
                        onCheckedChange = { ignoreCertificateErrors = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GeekBackground,
                            checkedTrackColor = GeekWarning,
                        ),
                    )
                }
                if (ignoreCertificateErrors) {
                    Text(
                        text = "开启后无法验证服务器身份，可能遭受中间人攻击。",
                        color = GeekWarning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    ProfileValidator.validate(
                        id = profile?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name,
                        server = server,
                        ignoreCertificateErrors = ignoreCertificateErrors,
                    ).onSuccess(onSave).onFailure { error = it.message }
                },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GeekPurple),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CredentialDialog(
    profileName: String,
    sessionId: String,
    challengeId: String,
    message: String,
    groups: List<SslGroup>,
    initialUsername: String,
    onDismiss: () -> Unit,
    onAuthenticate: (String, String, String, Boolean) -> Boolean,
) {
    var username by remember(sessionId, challengeId) { mutableStateOf(initialUsername) }
    var password by remember(sessionId, challengeId) { mutableStateOf("") }
    var rememberPassword by remember(sessionId, challengeId) { mutableStateOf(false) }
    var selectedGroupValue by remember(sessionId, challengeId, groups) {
        mutableStateOf(groups.firstOrNull { it.isDefault }?.value ?: groups.firstOrNull()?.value.orEmpty())
    }
    var groupMenuExpanded by remember(challengeId) { mutableStateOf(false) }
    var error by remember(challengeId) { mutableStateOf<String?>(null) }
    var submitting by remember(challengeId) { mutableStateOf(false) }
    val selectedGroup = groups.firstOrNull { it.value == selectedGroupValue }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        modifier = Modifier.widthIn(max = 440.dp),
        shape = RoundedCornerShape(8.dp),
        containerColor = GeekPanel,
        titleContentColor = GeekText,
        textContentColor = GeekTextMuted,
        title = {
            Text(
                text = profileName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (message.isNotBlank()) {
                    Text(message, color = GeekTextMuted, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !submitting,
                    label = { Text("用户名") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !submitting,
                    label = { Text("密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { groupMenuExpanded = true },
                        enabled = groups.isNotEmpty() && !submitting,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GeekText),
                        border = BorderStroke(1.dp, GeekOutline),
                    ) {
                        Text(
                            text = selectedGroup?.label ?: "默认 SSL Group",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            painterResource(R.drawable.ic_chevron_down),
                            contentDescription = "选择 SSL Group",
                            tint = GeekTextMuted,
                        )
                    }
                    DropdownMenu(
                        expanded = groupMenuExpanded,
                        onDismissRequest = { groupMenuExpanded = false },
                        containerColor = GeekPanelRaised,
                    ) {
                        groups.forEach { group ->
                            DropdownMenuItem(
                                text = { Text(group.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = {
                                    selectedGroupValue = group.value
                                    groupMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !submitting) { rememberPassword = !rememberPassword },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = rememberPassword,
                        onCheckedChange = { rememberPassword = it },
                        enabled = !submitting,
                    )
                    Text("保存密码", color = GeekText)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !submitting,
                onClick = {
                    if (username.trim().isEmpty() || password.isEmpty()) {
                        error = "请输入账号和密码"
                    } else {
                        submitting = onAuthenticate(username, password, selectedGroupValue, rememberPassword)
                    }
                },
                modifier = Modifier.widthIn(min = 104.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GeekPurple),
            ) { Text(if (submitting) "正在连接" else "连接") }
        },
        dismissButton = {
            TextButton(enabled = !submitting, onClick = onDismiss) { Text("取消") }
        },
    )
}
