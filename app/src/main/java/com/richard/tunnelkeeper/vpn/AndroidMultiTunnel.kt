package com.richard.tunnelkeeper.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** One Android TUN, one datagram pair per OpenConnect instance, and one sleeping I/O thread. */
internal class AndroidMultiTunnel(
    private val service: VpnService,
    val profileIds: Set<String>,
    private val onReady: () -> Unit,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private data class Endpoint(
        val token: String,
        val site: MultiTunnelRoutes.Site,
        val pipe: ParcelFileDescriptor,
        var sent: Long = 0,
        var received: Long = 0,
        var dropped: Long = 0,
    )

    private data class Command(val run: () -> Unit, val fail: (Throwable) -> Unit)
    private val commands = ConcurrentLinkedQueue<Command>()
    private val accepting = AtomicBoolean(true)
    private val wake = datagramPair()
    private val endpoints = linkedMapOf<String, Endpoint>()
    private val routes = MultiTunnelRoutes()
    private var tunnel: ParcelFileDescriptor? = null
    private var configuration: TunnelConfiguration? = null
    private var commonIpv4: Long? = null
    private val pendingProfiles = profileIds.toMutableSet()
    @Volatile var isReady = false
        private set
    @Volatile private var configuredProfiles: Set<String> = emptySet()
    fun hasConfiguredRoutes(profileId: String): Boolean = profileId in configuredProfiles
    private var running = true
    private val wakeByte = byteArrayOf(1)
    private val worker = Thread(::pump, "GeekOC-packet-router").apply { start() }

    fun platform(profileId: String, profileName: String, token: String): VpnPlatform = object : VpnPlatform {
        override fun protectSocket(socketFileDescriptor: Int): Boolean = service.protect(socketFileDescriptor)

        override fun establishTunnel(configuration: TunnelConfiguration): TunnelFileDescriptor = execute {
            require(profileId in profileIds) { "请先全部断开，再选择新的并行连接组合" }
            val site = MultiTunnelRoutes.Site(profileId, profileName, configuration)
            val merged = routes.candidate(site, tunnelEstablished = isReady)
            if (isReady) {
                require(configuration.mtu >= checkNotNull(this@AndroidMultiTunnel.configuration).mtu) {
                    "服务器 MTU 已降低，请全部断开后重新连接组合"
                }
            }
            val pair = datagramPair()
            try {
                // Native instances must never read the same TUN: each receives a private packet socket.
                if (!isReady) {
                    this@AndroidMultiTunnel.configuration = merged
                    commonIpv4 = merged.addresses.firstOrNull { ':' !in it.address }?.address
                        ?.let(Ipv4PacketTranslator::address)
                }
                routes.commit(site)
                configuredProfiles = configuredProfiles + profileId
                endpoints[profileId]?.pipe?.close()
                endpoints[profileId] = Endpoint(token, site, pair.first)
                pendingProfiles.remove(profileId)
                establishWhenReady()
                ConnectionLog.add("[$profileName] 已加入分流：${configuration.routes.joinToString { "${it.address}/${it.prefixLength}" }}")
                if (endpoints.size > 1) ConnectionLog.add("${endpoints.size} 个服务器并行；DNS 沿用首条连接；断线网段保持阻断")
                object : TunnelFileDescriptor {
                    private val closed = AtomicBoolean(false)
                    override val rawFd: Int get() = pair.second.fd
                    override fun close() {
                        if (!closed.compareAndSet(false, true)) return
                        pair.second.close() // Called only after native has released its borrowed fd.
                        detach(profileId, token)
                    }
                }
            } catch (error: Throwable) {
                pair.first.close()
                pair.second.close()
                throw error
            }
        }
    }

    fun unavailable(profileId: String) = post {
        pendingProfiles.remove(profileId)
        establishWhenReady()
    }

    private fun establishWhenReady() {
        if (isReady || pendingProfiles.isNotEmpty() || endpoints.isEmpty()) return
        // Establish exactly once per selected group. Re-establishing a TUN aborts existing TCP sockets.
        tunnel = establish(checkNotNull(configuration))
        isReady = true
        onReady()
    }

    private fun establish(value: TunnelConfiguration): ParcelFileDescriptor {
        val builder = service.Builder().setSession(value.sessionName).setMtu(value.mtu)
        value.addresses.forEach { builder.addAddress(it.address, it.prefixLength) }
        value.routes.forEach { builder.addRoute(it.address, it.prefixLength) }
        value.dnsServers.forEach(builder::addDnsServer)
        if (value.allowIpv6Bypass) builder.allowFamily(OsConstants.AF_INET6)
        return checkNotNull(builder.establish()) { "Android 未能建立共享 VPN 接口" }
    }

    private fun detach(profileId: String, token: String) {
        post {
            endpoints[profileId]?.takeIf { it.token == token }?.let { endpoint ->
                endpoints.remove(profileId)
                endpoint.pipe.close()
                ConnectionLog.add("[${endpoint.site.name}] 收 ${endpoint.received} B / 发 ${endpoint.sent} B / 丢弃 ${endpoint.dropped} 包；原网段保持阻断")
            }
        }
    }

    private fun <T> execute(action: () -> T): T {
        val future = CompletableFuture<T>()
        synchronized(commands) {
            check(accepting.get()) { "VPN 分流器已停止" }
            commands.add(Command(
                run = { try { future.complete(action()) } catch (error: Throwable) { future.completeExceptionally(error) } },
                fail = { future.completeExceptionally(it) },
            ))
            signal()
        }
        return try { future.get() } catch (error: ExecutionException) { throw error.cause ?: error }
    }

    private fun post(action: () -> Unit) {
        synchronized(commands) {
            if (!accepting.get()) return
            commands.add(Command(action) { })
            signal()
        }
    }

    private fun signal() {
        try { Os.write(wake.second.fileDescriptor, wakeByte, 0, 1) } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EAGAIN) throw error
        }
    }

    private fun pump() {
        val packet = ByteArray(65_535)
        var descriptors = emptyArray<StructPollfd>()
        var currentTunnel: ParcelFileDescriptor? = null
        var currentEndpoints = emptyList<Endpoint>()
        try {
            while (running) {
                var changed = descriptors.isEmpty()
                while (true) {
                    val command = commands.poll() ?: break
                    command.run()
                    changed = true
                }
                if (!running) break
                // Reuse buffers and poll structures on the packet path; rebuild only for control events.
                if (changed) {
                    currentTunnel = tunnel
                    currentEndpoints = endpoints.values.toList()
                    descriptors = buildList {
                        add(poll(wake.first.fileDescriptor))
                        currentTunnel?.let { add(poll(it.fileDescriptor)) }
                        currentEndpoints.forEach { add(poll(it.pipe.fileDescriptor)) }
                    }.toTypedArray()
                }
                try { Os.poll(descriptors, -1) } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.EINTR) continue else throw error
                }
                if (descriptors[0].revents.toInt() != 0) drain(wake.first.fileDescriptor, packet) { }
                if (commands.isNotEmpty()) continue
                var index = 1
                if (currentTunnel != null) {
                    if (readable(descriptors[index++])) {
                        drain(currentTunnel.fileDescriptor, packet) { length -> outgoing(packet, length) }
                    }
                }
                currentEndpoints.forEach { endpoint ->
                    if (readable(descriptors[index++])) {
                        drain(endpoint.pipe.fileDescriptor, packet) { length -> incoming(endpoint, packet, length) }
                    }
                }
            }
        } catch (error: Throwable) {
            if (accepting.get()) onFailure(error.message ?: "VPN 分流器异常停止")
        } finally {
            synchronized(commands) {
                accepting.set(false)
                isReady = false
                while (true) (commands.poll() ?: break).fail(IllegalStateException("VPN 分流器已停止"))
            }
            endpoints.values.forEach { runCatching { it.pipe.close() } }
            endpoints.clear()
            runCatching { tunnel?.close() }
            runCatching { wake.first.close() }
            runCatching { wake.second.close() }
        }
    }

    private fun outgoing(packet: ByteArray, length: Int) {
        if (configuration == null) return
        val version = if (length > 0) (packet[0].toInt() and 255) ushr 4 else return
        val endpoint = when (version) {
            4 -> {
                if (length < 20) return
                val owner = routes.owner(Ipv4PacketTranslator.readAddress(packet, 16)) ?: routes.singleSiteId
                endpoints[owner] ?: return
            }
            6 -> endpoints.values.singleOrNull()?.takeIf { it.site.hasIpv6 } ?: return
            else -> return
        }
        if (version == 4) {
            if (!Ipv4PacketTranslator.translate(packet, length, commonIpv4 ?: return,
                    endpoint.site.ipv4 ?: return, outgoing = true)) {
                endpoint.dropped++
                return
            }
        }
        if (write(endpoint.pipe.fileDescriptor, packet, length)) endpoint.sent += length else endpoint.dropped++
    }

    private fun incoming(endpoint: Endpoint, packet: ByteArray, length: Int) {
        if (configuration == null) return
        val fd = tunnel?.fileDescriptor ?: return
        val version = if (length > 0) (packet[0].toInt() and 255) ushr 4 else return
        when (version) {
            4 -> {
                if (!routes.acceptsReply(endpoint.site.profileId, packet, length)) {
                    endpoint.dropped++
                    return
                }
                if (!Ipv4PacketTranslator.translate(packet, length, endpoint.site.ipv4 ?: return,
                        commonIpv4 ?: return, outgoing = false)) {
                    endpoint.dropped++
                    return
                }
            }
            6 -> if (!endpoint.site.hasIpv6 || endpoints.size != 1) return
            else -> return
        }
        if (write(fd, packet, length)) endpoint.received += length else endpoint.dropped++
    }

    override fun close() {
        synchronized(commands) {
            if (accepting.getAndSet(false)) {
                commands.add(Command({ running = false }) { })
                signal()
            }
        }
        if (Thread.currentThread() != worker) worker.join(5_000)
    }

    private fun drain(fd: FileDescriptor, buffer: ByteArray, consume: (Int) -> Unit) {
        // Bound each batch so a busy server cannot starve the others or delay a disconnect.
        repeat(64) {
            val length = try { Os.read(fd, buffer, 0, buffer.size) } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) return else throw error
            }
            if (length <= 0) return
            consume(length)
        }
    }

    private fun write(fd: FileDescriptor, buffer: ByteArray, length: Int): Boolean = try {
        Os.write(fd, buffer, 0, length) == length
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.ENOBUFS ||
            error.errno == OsConstants.ECONNREFUSED || error.errno == OsConstants.EINTR) false else throw error
    }

    private fun poll(fd: FileDescriptor) = StructPollfd().apply {
        this.fd = fd
        events = OsConstants.POLLIN.toShort()
    }

    private fun readable(poll: StructPollfd): Boolean {
        check(poll.revents.toInt() and OsConstants.POLLNVAL == 0) { "VPN 文件描述符已失效" }
        return poll.revents.toInt() and OsConstants.POLLIN != 0
    }

    companion object {
        internal fun datagramPair(): Pair<ParcelFileDescriptor, ParcelFileDescriptor> {
            val left = FileDescriptor()
            val right = FileDescriptor()
            // Android documents O_NONBLOCK as the identical, API-21-compatible SOCK_NONBLOCK flag.
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM or OsConstants.O_NONBLOCK, 0, left, right)
            try {
                val first = ParcelFileDescriptor.dup(left)
                return try { first to ParcelFileDescriptor.dup(right) } catch (error: Throwable) {
                    first.close()
                    throw error
                }
            } finally {
                Os.close(left)
                Os.close(right)
            }
        }
    }
}
