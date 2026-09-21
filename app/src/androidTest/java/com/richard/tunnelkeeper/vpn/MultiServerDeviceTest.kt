package com.richard.tunnelkeeper.vpn

import android.content.Intent
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.platform.app.InstrumentationRegistry
import com.richard.tunnelkeeper.MainActivity
import com.richard.tunnelkeeper.data.ProfileRepository
import com.richard.tunnelkeeper.data.SecureCredentialStore
import com.richard.tunnelkeeper.model.ConnectionProfile
import com.richard.tunnelkeeper.model.VpnCredentials
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.net.InetAddress
import java.net.Socket
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Targets contain no secrets. Log in through the app once; read credentials from its encrypted store. */
class MultiServerDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private data class Site(val profile: ConnectionProfile, val credentials: VpnCredentials, val probes: List<String>)

    private fun sites(): List<Site> {
        val file = File(context.filesDir, "verification-input.json")
        assumeTrue("需要在应用私有目录注入验证配置", file.exists())
        val array = JSONArray(file.readText())
        val profiles = ProfileRepository(context).load()
        val credentials = SecureCredentialStore(context)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val configured = profiles.single { it.server == item.getString("server") && !it.id.startsWith("verification-") }
            Site(
                configured.copy(id = "verification-$index", name = item.getString("name")),
                checkNotNull(credentials.read(configured.id)) { "请先在应用中登录并保存测试站点凭据" },
                item.optJSONArray("probes")?.let { probes -> (0 until probes.length()).map(probes::getString) }.orEmpty(),
            )
        }.also { require(it.size >= 2) { "至少需要两个测试服务器" } }
    }

    @Test fun authenticateBothServers() {
        val sites = sites()
        val sessions = sites.map { NativeOpenConnectSession() }
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        watchdog.schedule({ sessions.forEach { it.cancel() } }, 90, TimeUnit.SECONDS)
        try {
            val results = runBlocking {
                sites.zip(sessions).map { (site, session) -> async(Dispatchers.IO) {
                    var diagnostics: TunnelDiagnostics? = null
                    var failure: String? = null
                    var answered = false
                    var selectedGroup = site.credentials.sslGroup
                    var offeredGroups = emptyList<String>()
                    session.run(site.profile, site.credentials, object : VpnPlatform {
                        override fun protectSocket(socketFileDescriptor: Int) = true
                        override fun establishTunnel(configuration: TunnelConfiguration): TunnelFileDescriptor {
                            val pair = AndroidMultiTunnel.datagramPair()
                            return object : TunnelFileDescriptor {
                                override val rawFd get() = pair.first.fd
                                override fun close() { pair.first.close(); pair.second.close() }
                            }
                        }
                    }) { event ->
                        when (event) {
                            is EngineEvent.TunnelEstablished -> { diagnostics = event.diagnostics; session.cancel() }
                            is EngineEvent.Failed -> if (event.kind != EngineFailureKind.CANCELLED) failure = event.message
                            is EngineEvent.AwaitingAuthentication -> {
                                offeredGroups = event.sslGroups.map { "${it.label} [${it.value}]" }
                                val requested = event.sslGroups.singleOrNull {
                                    it.value.equals(site.credentials.sslGroup, ignoreCase = true) ||
                                        it.label.equals(site.credentials.sslGroup, ignoreCase = true)
                                }
                                if (!answered && requested != null) {
                                    answered = true
                                    selectedGroup = requested.value
                                    session.submitAuthentication(event.challengeId, site.credentials.copy(sslGroup = requested.value))
                                } else {
                                    failure = "服务器要求重新认证：${event.reason}；可选 Group：${offeredGroups.joinToString()}"
                                    session.cancel()
                                }
                            }
                            else -> Unit
                        }
                    }
                    val info = diagnostics
                    JSONObject().put("server", site.profile.server).put("name", site.profile.name)
                        .put("authenticated", info != null).put("failure", failure ?: JSONObject.NULL)
                        .put("selectedGroup", selectedGroup).put("offeredGroups", JSONArray(offeredGroups))
                        .put("addresses", JSONArray(info?.configuration?.addresses?.map { it.address }.orEmpty()))
                        .put("routes", JSONArray(info?.configuration?.routes?.map { "${it.address}/${it.prefixLength}" }.orEmpty()))
                } }.awaitAll()
            }
            File(context.filesDir, "verification-auth-results.json").writeText(JSONArray(results).toString(2))
            results.forEach { assertTrue("${it.getString("name")}: ${it.optString("failure")}", it.getBoolean("authenticated")) }
        } finally {
            sessions.forEach { it.cancel() }
            watchdog.shutdownNow()
        }
    }

    @Test fun prepareTestProfilesForSystemConsent() {
        val repository = ProfileRepository(context)
        val credentials = SecureCredentialStore(context)
        sites().forEach { repository.upsert(it.profile); credentials.save(it.profile.id, it.credentials) }
    }

    @Test fun simultaneousRoutingAndDisconnectIsolation() {
        val sites = sites()
        assumeTrue("需要为每台服务器配置至少一个 HTTP 内网探测地址", sites.all { it.probes.isNotEmpty() })
        assertNull("请先授予测试应用 VPN 权限", VpnService.prepare(context))
        val repository = ProfileRepository(context)
        val credentials = SecureCredentialStore(context)
        var persistent: Socket? = null
        val previousQuickProfiles = repository.loadQuickConnectProfiles().map { it.id }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            sites.forEach { repository.upsert(it.profile); credentials.save(it.profile.id, it.credentials) }
            context.startForegroundService(Intent(context, SecureTunnelVpnService::class.java)
                .setAction(SecureTunnelVpnService.ACTION_CONNECT)
                // A missing configuration must release the initial barrier, not prevent healthy sites connecting.
                .putStringArrayListExtra(SecureTunnelVpnService.EXTRA_PROFILE_IDS, ArrayList(listOf("verification-missing") + sites.map { it.profile.id })))
            sites.forEach { site ->
                awaitConnected(site.profile.id)
                awaitSystemRoutes(site)
                verifyHttp(site)
            }
            assertEquals(sites.size, ConnectionStateStore.profiles.value.values.count { it is ConnectionState.Connected })
            assertTrue(ConnectionStateStore.profiles.value["verification-missing"] is ConnectionState.Failed)
            // Retrying a selected-but-never-configured member must not falsely report a routed connection.
            context.startForegroundService(Intent(context, SecureTunnelVpnService::class.java)
                .setAction(SecureTunnelVpnService.ACTION_CONNECT)
                .putExtra(SecureTunnelVpnService.EXTRA_PROFILE_ID, "verification-missing"))
            waitUntil {
                (ConnectionStateStore.profiles.value["verification-missing"] as? ConnectionState.Failed)
                    ?.reason?.contains("尚未安装路由") == true
            }
            assertEquals(sites.size, ConnectionStateStore.profiles.value.values.count { it is ConnectionState.Connected })
            context.startForegroundService(Intent(context, SecureTunnelVpnService::class.java)
                .setAction(SecureTunnelVpnService.ACTION_CONNECT)
                .putExtra(SecureTunnelVpnService.EXTRA_PROFILE_ID, "verification-outside-group"))
            waitUntil { ConnectionStateStore.profiles.value["verification-outside-group"] is ConnectionState.Failed }
            repeat(3) { sites.forEach { verifyHttp(it) } }
            sites.forEach { ping(URI(it.probes.first()).host) }
            persistent = openSocket(URI(sites.last().probes.first()))
            disconnect(sites.first().profile.id)
            waitUntil { ConnectionStateStore.profiles.value[sites.first().profile.id] == null }
            checkStream(checkNotNull(persistent), URI(sites.last().probes.first()))
            persistent?.close()
            persistent = null
            sites.drop(1).forEach { verifyHttp(it) }
            // The departed site's traffic must not fall through to the physical/default network.
            sites.first().probes.forEach { url ->
                assertFalse("断线网段意外绕行：$url", runCatching { http(url, bindToVpn = false) }.isSuccess)
            }
            context.startForegroundService(Intent(context, SecureTunnelVpnService::class.java)
                .setAction(SecureTunnelVpnService.ACTION_CONNECT)
                .putExtra(SecureTunnelVpnService.EXTRA_PROFILE_ID, sites.first().profile.id))
            awaitConnected(sites.first().profile.id)
            sites.forEach { verifyHttp(it) }
            persistent = openSocket(URI(sites.first().probes.first()))
            disconnect(sites.last().profile.id)
            waitUntil { ConnectionStateStore.profiles.value[sites.last().profile.id] == null }
            checkStream(checkNotNull(persistent), URI(sites.first().probes.first()))
            persistent?.close()
            persistent = null
            verifyHttp(sites.first())
            sites.last().probes.forEach { url ->
                assertFalse("断线网段意外绕行：$url", runCatching { http(url, bindToVpn = false) }.isSuccess)
            }
            persistent = openSocket(URI(sites.first().probes.first()))
            context.startForegroundService(Intent(context, SecureTunnelVpnService::class.java)
                .setAction(SecureTunnelVpnService.ACTION_CONNECT)
                .putExtra(SecureTunnelVpnService.EXTRA_PROFILE_ID, sites.last().profile.id))
            awaitConnected(sites.last().profile.id)
            checkStream(checkNotNull(persistent), URI(sites.first().probes.first()))
            persistent?.close()
            persistent = null
            sites.forEach { site ->
                site.probes.forEach { probe ->
                    openSocket(URI(probe), bindToVpn = false).use { socket ->
                        val manager = context.getSystemService(ConnectivityManager::class.java)
                        val addresses = manager.getLinkProperties(manager.activeNetwork)?.linkAddresses.orEmpty()
                        assertTrue("普通应用流量未进入 VPN", addresses.any { it.address == socket.localAddress })
                        checkStream(socket, URI(probe))
                    }
                }
            }
            http("https://www.baidu.com/")
            File(context.filesDir, "verification-data-results.txt").writeText(
                "PASS: simultaneous internal TCP/HTTP, ICMP, existing TCP survival, independent reconnect, both disconnect directions, public HTTPS\n" +
                    sites.joinToString("\n") { "${it.profile.name}: ${it.probes.joinToString()}" },
            )
        } finally {
            persistent?.close()
            disconnect(null)
            waitUntil { ConnectionStateStore.profiles.value.values.none {
                it is ConnectionState.Connected || it is ConnectionState.Connecting || it is ConnectionState.AuthenticationRequired
            } }
            File(context.filesDir, "verification-connection-log.txt").writeText(
                ConnectionLog.entries.value.joinToString("\n") { "${it.timestamp} ${it.message}" },
            )
            sites.forEach { credentials.remove(it.profile.id); repository.remove(it.profile.id) }
            repository.saveQuickConnectProfiles(previousQuickProfiles)
        }
    }

    private fun verifyHttp(site: Site) { site.probes.forEach { http(it) } }
    private fun http(url: String, bindToVpn: Boolean = true) {
        val uri = URI(url)
        if (uri.scheme == "http" || uri.scheme == "ssh") {
            openSocket(uri, bindToVpn).use { checkStream(it, uri) }
            return
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            check(code in 200..499) { "HTTP 探测失败：$code" }
        } finally { connection.disconnect() }
    }

    private fun openSocket(url: URI, bindToVpn: Boolean = true): Socket = Socket().apply {
        soTimeout = 5_000
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val vpn = manager.allNetworks.firstOrNull {
            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } ?: error("未找到 VPN 网络")
        if (bindToVpn) vpn.bindSocket(this)
        connect(InetSocketAddress(url.host, if (url.port > 0) url.port else if (url.scheme == "ssh") 22 else 80), 5_000)
        ConnectionLog.add("[验证] $url：源 ${localAddress.hostAddress}，VPN $vpn")
    }

    private fun checkStream(socket: Socket, url: URI) {
        if (url.scheme == "ssh") {
            val input = socket.getInputStream()
            val banner = StringBuilder()
            while (banner.length < 255) {
                val next = input.read()
                check(next >= 0) { "SSH 连接提前关闭" }
                if (next == 10) break
                banner.append(next.toChar())
            }
            check(banner.startsWith("SSH-")) { "目标没有返回 SSH 标识" }
            socket.getOutputStream().write("SSH-2.0-GeekOCTunnelVerification\r\n".toByteArray(Charsets.US_ASCII))
            check(input.read() >= 0) { "SSH 服务未响应握手" }
            return
        }
        val path = url.rawPath?.ifEmpty { "/" } ?: "/"
        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: ${url.host}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val line = reader.readLine().orEmpty()
        val code = line.split(' ').getOrNull(1)?.toIntOrNull()
        check(code != null && code in 200..499) { "HTTP 探测失败：$line" }
        val buffer = CharArray(8192)
        var size = 0
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            size += count
            check(size <= 1_048_576) { "HTTP 测试响应超过 1 MiB" }
        }
    }

    private fun awaitSystemRoutes(site: Site) = waitUntil {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        val links = network?.let(manager::getLinkProperties)
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true && site.probes.all { probe ->
            links?.routes?.any { it.matches(InetAddress.getByName(URI(probe).host)) } == true
        }
    }

    private fun ping(host: String) {
        val process = ProcessBuilder("ping", "-c", "2", "-W", "2", host).redirectErrorStream(true).start()
        try {
            check(process.waitFor(8, TimeUnit.SECONDS)) { "ICMP 探测超时" }
            val output = process.inputStream.bufferedReader().readText()
            assertEquals("ICMP 探测失败：$output", 0, process.exitValue())
        } finally { process.destroy() }
    }

    private fun awaitConnected(id: String) = waitUntil {
        val state = ConnectionStateStore.profiles.value[id]
        if (state is ConnectionState.Failed) error("$id: ${state.reason}")
        if (state is ConnectionState.AuthenticationRequired) error("$id: 需要重新认证")
        state is ConnectionState.Connected
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (!predicate()) {
            check(System.nanoTime() < deadline) { "等待 VPN 状态超时" }
            Thread.sleep(100)
        }
    }

    private fun disconnect(id: String?) {
        context.startService(Intent(context, SecureTunnelVpnService::class.java)
            .setAction(SecureTunnelVpnService.ACTION_DISCONNECT)
            .putExtra(SecureTunnelVpnService.EXTRA_PROFILE_ID, id))
    }
}
