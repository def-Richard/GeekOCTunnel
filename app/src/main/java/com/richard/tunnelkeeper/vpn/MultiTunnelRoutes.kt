package com.richard.tunnelkeeper.vpn

internal data class Ipv4Route(val network: Long, val prefix: Int) {
    private val mask = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
    fun contains(address: Long): Boolean = address and mask == network
    fun overlaps(other: Ipv4Route): Boolean = contains(other.network) || other.contains(network)

    companion object {
        fun parse(value: NetworkPrefix): Ipv4Route {
            require(value.prefixLength in 0..32) { "无效 IPv4 路由" }
            val mask = if (value.prefixLength == 0) 0L else
                (0xffffffffL shl (32 - value.prefixLength)) and 0xffffffffL
            return Ipv4Route(Ipv4PacketTranslator.address(value.address) and mask, value.prefixLength)
        }
    }
}

/** Reservations survive a lost session, so its destinations never fall through to another path. */
internal class MultiTunnelRoutes {
    data class Site(val profileId: String, val name: String, val configuration: TunnelConfiguration) {
        val routes = configuration.routes.filter { ':' !in it.address }.map(Ipv4Route::parse)
        val ipv4 = configuration.addresses.firstOrNull { ':' !in it.address }
            ?.address?.let(Ipv4PacketTranslator::address)
        val hasIpv6 = configuration.addresses.any { ':' in it.address }
    }

    private val sites = linkedMapOf<String, Site>()
    private var base: TunnelConfiguration? = null
    private var lookup = emptyArray<Pair<Ipv4Route, String>>()

    val singleSiteId: String? get() = sites.values.singleOrNull()?.profileId

    fun candidate(site: Site, tunnelEstablished: Boolean = false): TunnelConfiguration {
        val previous = sites[site.profileId]
        require(!tunnelEstablished || previous != null) {
            "${site.name} 首次连接未完成，尚未安装路由；请全部断开后重新连接组合"
        }
        val others = sites.values.filter { it.profileId != site.profileId }
        if (others.isNotEmpty()) {
            require(!site.hasIpv6 && others.none { it.hasIpv6 }) {
                "多服务器连接目前仅支持 IPv4，请使用仅下发 IPv4 地址的配置；单服务器仍支持 IPv6"
            }
            require(site.ipv4 != null && site.routes.isNotEmpty()) { "服务器没有下发可分流的 IPv4 地址和路由" }
            others.forEach { existing ->
                require(site.routes.none { route -> existing.routes.any(route::overlaps) }) {
                    "${site.name} 与 ${existing.name} 的目标网段重叠，无法并行分流（全隧道路由也会冲突）"
                }
            }
        }
        require(previous == null || previous.configuration.routes == site.configuration.routes) {
            "${site.name} 的路由已变化，请全部断开后重连以更新路由"
        }
        val original = base ?: site.configuration
        val all = others + site
        val commonIp = original.addresses.firstOrNull { ':' !in it.address }?.address
            ?.let(Ipv4PacketTranslator::address)
        if (base != null && previous == null && commonIp != null) {
            require(site.routes.none { it.contains(commonIp) }) {
                "${site.name} 的目标网段包含当前手机 VPN 地址，请调整服务端地址池后重试"
            }
        }
        return original.copy(
            sessionName = if (all.size == 1) site.name else "GeekOCTunnel · 多服务器",
            mtu = all.minOf { it.configuration.mtu },
            routes = if (all.size == 1) site.configuration.routes else all.flatMap { it.configuration.routes }
                .distinct().sortedWith(compareBy({ it.address }, { it.prefixLength })),
            // Preserve the first connection's DNS policy. Destination routing also applies to DNS packets.
            allowIpv6Bypass = all.all { it.configuration.allowIpv6Bypass },
        )
    }

    fun commit(site: Site) {
        if (base == null) base = site.configuration
        sites[site.profileId] = site
        lookup = sites.values.flatMap { value -> value.routes.map { it to value.profileId } }.toTypedArray()
    }

    fun owner(address: Long): String? {
        // ponytail: linear scan for a few site routes; use a prefix trie if large route tables are needed.
        var index = 0
        while (index < lookup.size) {
            val (route, profileId) = lookup[index++]
            if (route.contains(address)) return profileId
        }
        return null
    }

    fun acceptsReply(profileId: String, packet: ByteArray, length: Int): Boolean {
        if (length < 20) return false
        if (sites.size == 1) return true
        if (owner(Ipv4PacketTranslator.readAddress(packet, 12)) == profileId) return true
        // ICMP errors can originate at an intermediate router outside the site's destination CIDRs.
        val header = (packet[0].toInt() and 15) * 4
        if (packet[9].toInt() != 1 || header < 20 || length < header + 28) return false
        if ((packet[header].toInt() and 255) !in setOf(3, 4, 5, 11, 12)) return false
        val quoted = header + 8
        if ((packet[quoted].toInt() and 255) ushr 4 != 4) return false
        return owner(Ipv4PacketTranslator.readAddress(packet, quoted + 16)) == profileId
    }
}
