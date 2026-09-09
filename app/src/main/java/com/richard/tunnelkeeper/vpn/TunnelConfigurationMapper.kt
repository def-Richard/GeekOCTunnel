package com.richard.tunnelkeeper.vpn

import java.math.BigInteger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal data class NegotiatedIpInfo(
    val ipv4Address: String?,
    val ipv4Netmask: String?,
    val ipv6Address: String?,
    val ipv6Netmask: String?,
    val dnsServers: List<String>,
    val splitIncludes: List<String>,
    val splitExcludes: List<String>,
    val mtu: Int,
)

internal object TunnelConfigurationMapper {
    fun create(sessionName: String, info: NegotiatedIpInfo): TunnelConfiguration {
        val addresses = buildList {
            info.ipv4Address?.takeIf(String::isNotBlank)?.let { address ->
                add(NetworkPrefix(address, netmaskPrefix(info.ipv4Netmask, IPV4_BITS)))
            }
            info.ipv6Address?.takeIf(String::isNotBlank)?.let { address ->
                add(NetworkPrefix(address, netmaskPrefix(info.ipv6Netmask, IPV6_BITS)))
            }
        }
        require(addresses.isNotEmpty()) { "服务器没有返回 VPN 地址" }

        val addressFamilies = addresses.map { parseAddress(it.address).size * BITS_PER_BYTE }.toSet()
        val baseRoutes = if (info.splitIncludes.isEmpty()) {
            addressFamilies.map { bits -> IpNetwork(BigInteger.ZERO, 0, bits) }
        } else {
            info.splitIncludes.map(::parseNetwork).filter { it.bits in addressFamilies }
        }
        require(baseRoutes.isNotEmpty()) { "服务器返回的路由与 VPN 地址族不匹配" }
        var effectiveRoutes = baseRoutes
        info.splitExcludes.map(::parseNetwork).forEach { excluded ->
            effectiveRoutes = effectiveRoutes.flatMap { included -> included.subtract(excluded) }
        }

        val routes = effectiveRoutes
            .distinct()
            .sortedWith(compareBy<IpNetwork>({ it.bits }, { it.prefixLength }, { it.address }))
            .map(IpNetwork::toNetworkPrefix)
        val allowIpv6Bypass = IPV4_BITS in addressFamilies &&
            IPV6_BITS !in addressFamilies &&
            !coversEntireAddressFamily(baseRoutes, IPV4_BITS)
        val dnsServers = info.dnsServers
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .filter { server ->
                val bits = parseAddress(server).size * BITS_PER_BYTE
                bits in addressFamilies || (bits == IPV6_BITS && allowIpv6Bypass)
            }

        return TunnelConfiguration(
            sessionName = sessionName,
            mtu = info.mtu.takeIf { it in MIN_MTU..MAX_MTU } ?: DEFAULT_MTU,
            addresses = addresses,
            routes = routes,
            dnsServers = dnsServers,
            allowIpv6Bypass = allowIpv6Bypass,
        )
    }

    private fun netmaskPrefix(netmask: String?, bits: Int): Int {
        if (netmask.isNullOrBlank()) return bits
        netmask.toIntOrNull()?.let { prefix ->
            require(prefix in 0..bits) { "服务器返回了无效的网络掩码" }
            return prefix
        }
        val bytes = parseAddress(netmask)
        require(bytes.size * BITS_PER_BYTE == bits) { "服务器返回了无效的网络掩码" }
        var prefix = 0
        var foundZero = false
        bytes.forEach { byte ->
            for (shift in 7 downTo 0) {
                val set = (byte.toInt() and (1 shl shift)) != 0
                if (set) {
                    require(!foundZero) { "服务器返回了非连续网络掩码" }
                    prefix++
                } else {
                    foundZero = true
                }
            }
        }
        return prefix
    }

    private fun parseNetwork(value: String): IpNetwork {
        val route = value.trim()
        val separator = route.lastIndexOf('/')
        val addressPart = if (separator >= 0) route.substring(0, separator) else route
        val bytes = parseAddress(addressPart)
        val bits = bytes.size * BITS_PER_BYTE
        val prefix = if (separator >= 0) {
            val mask = route.substring(separator + 1)
            mask.toIntOrNull() ?: dottedIpv4RoutePrefix(mask, bits)
        } else {
            bits
        }
        require(prefix in 0..bits) { "服务器返回了无效路由" }
        return IpNetwork(unsigned(bytes), prefix, bits).normalized()
    }

    private fun dottedIpv4RoutePrefix(mask: String, bits: Int): Int {
        require(bits == IPV4_BITS && mask.matches(IPV4_LITERAL)) { "服务器返回了无效路由" }
        val bytes = parseAddress(mask)
        var prefix = 0
        var foundZero = false
        bytes.forEach { byte ->
            for (shift in 7 downTo 0) {
                val set = (byte.toInt() and (1 shl shift)) != 0
                if (set) {
                    require(!foundZero) { "服务器返回了无效路由" }
                    prefix++
                } else {
                    foundZero = true
                }
            }
        }
        return prefix
    }

    private fun parseAddress(value: String): ByteArray {
        val literal = value.trim().removePrefix("[").removeSuffix("]")
        require(literal.matches(IPV4_LITERAL) || ':' in literal) { "服务器返回了无效 IP 地址" }
        val address = InetAddress.getByName(literal)
        require(address is Inet4Address || address is Inet6Address) { "服务器返回了无效 IP 地址" }
        return address.address
    }

    private fun unsigned(bytes: ByteArray) = BigInteger(1, bytes)

    private fun coversEntireAddressFamily(routes: List<IpNetwork>, bits: Int): Boolean {
        val maximumAddress = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
        var nextUncoveredAddress = BigInteger.ZERO
        val normalizedRoutes = routes
            .filter { it.bits == bits }
            .map(IpNetwork::normalized)
            .sortedBy(IpNetwork::address)

        normalizedRoutes.forEach { route ->
            if (route.address > nextUncoveredAddress) return false
            val routeEnd = route.address.add(
                BigInteger.ONE.shiftLeft(bits - route.prefixLength).subtract(BigInteger.ONE),
            )
            if (routeEnd >= maximumAddress) return true
            if (routeEnd >= nextUncoveredAddress) {
                nextUncoveredAddress = routeEnd.add(BigInteger.ONE)
            }
        }
        return false
    }

    private data class IpNetwork(
        val address: BigInteger,
        val prefixLength: Int,
        val bits: Int,
    ) {
        fun normalized(): IpNetwork = copy(address = address.and(mask(prefixLength, bits)))

        fun subtract(excluded: IpNetwork): List<IpNetwork> {
            val base = normalized()
            val removal = excluded.normalized()
            if (base.bits != removal.bits || !base.overlaps(removal)) return listOf(base)
            if (removal.prefixLength <= base.prefixLength) return emptyList()

            val childPrefix = base.prefixLength + 1
            val rightBit = BigInteger.ONE.shiftLeft(bits - childPrefix)
            val left = IpNetwork(base.address, childPrefix, bits)
            val right = IpNetwork(base.address.or(rightBit), childPrefix, bits)
            return left.subtract(removal) + right.subtract(removal)
        }

        private fun overlaps(other: IpNetwork): Boolean {
            val sharedPrefix = minOf(prefixLength, other.prefixLength)
            val sharedMask = mask(sharedPrefix, bits)
            return address.and(sharedMask) == other.address.and(sharedMask)
        }

        fun toNetworkPrefix(): NetworkPrefix {
            val byteCount = bits / BITS_PER_BYTE
            val source = address.toByteArray()
            val bytes = ByteArray(byteCount)
            val copyLength = minOf(source.size, byteCount)
            source.copyInto(bytes, byteCount - copyLength, source.size - copyLength, source.size)
            return NetworkPrefix(requireNotNull(InetAddress.getByAddress(bytes).hostAddress), prefixLength)
        }
    }

    private fun mask(prefix: Int, bits: Int): BigInteger = when (prefix) {
        0 -> BigInteger.ZERO
        else -> BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
            .xor(BigInteger.ONE.shiftLeft(bits - prefix).subtract(BigInteger.ONE))
    }

    private val IPV4_LITERAL = Regex("^[0-9]{1,3}(?:\\.[0-9]{1,3}){3}$")
    private const val BITS_PER_BYTE = 8
    private const val IPV4_BITS = 32
    private const val IPV6_BITS = 128
    private const val MIN_MTU = 576
    private const val MAX_MTU = 9_000
    private const val DEFAULT_MTU = 1_406
}
