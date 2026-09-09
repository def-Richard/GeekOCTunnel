package com.richard.tunnelkeeper.model

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import java.util.UUID

data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val server: String,
    val ignoreCertificateErrors: Boolean = false,
)

object ProfileValidator {
    fun validate(
        id: String = UUID.randomUUID().toString(),
        name: String,
        server: String,
        ignoreCertificateErrors: Boolean = false,
    ): Result<ConnectionProfile> {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) {
            return Result.failure(IllegalArgumentException("请填写配置名称"))
        }

        val normalizedServer = try {
            ServerAddressNormalizer.normalize(server)
        } catch (error: IllegalArgumentException) {
            return Result.failure(error)
        }
        return Result.success(
            ConnectionProfile(
                id = id,
                name = normalizedName,
                server = normalizedServer,
                ignoreCertificateErrors = ignoreCertificateErrors,
            ),
        )
    }
}

internal object ServerAddressNormalizer {
    private val explicitScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val numericHost = Regex("^[0-9.]+$")

    fun normalize(value: String): String {
        val input = value.trim()
        require(input.isNotEmpty()) { INVALID_SERVER }
        val candidate = if (explicitScheme.containsMatchIn(input)) input else "https://$input"
        val uri = try {
            URI(candidate)
        } catch (error: Exception) {
            throw IllegalArgumentException(INVALID_SERVER, error)
        }

        require(!uri.isOpaque && uri.scheme.equals("https", ignoreCase = true)) { HTTPS_ONLY }
        require(uri.rawUserInfo == null) { INVALID_SERVER }
        require(uri.rawQuery == null && uri.rawFragment == null) { INVALID_SERVER }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { INVALID_SERVER }
        require(uri.rawAuthority?.endsWith(':') != true) { INVALID_SERVER }

        val rawHost = uri.host ?: throw IllegalArgumentException(INVALID_SERVER)
        val normalizedHost = normalizeHost(rawHost)
        val port = uri.port
        require(port == -1 || port in 1..65535) { INVALID_SERVER }
        return if (port == -1 || port == DEFAULT_HTTPS_PORT) normalizedHost else "$normalizedHost:$port"
    }

    private fun normalizeHost(rawHost: String): String {
        val unwrappedHost = rawHost.removePrefix("[").removeSuffix("]")
        require(unwrappedHost.isNotEmpty()) { INVALID_SERVER }
        if (unwrappedHost.contains(':')) {
            require('%' !in unwrappedHost) { INVALID_SERVER }
            val address = try {
                InetAddress.getByName(unwrappedHost)
            } catch (error: Exception) {
                throw IllegalArgumentException(INVALID_SERVER, error)
            }
            require(address is Inet6Address) { INVALID_SERVER }
            return "[${unwrappedHost.lowercase(Locale.ROOT)}]"
        }

        val withoutRootDot = unwrappedHost.removeSuffix(".")
        val asciiHost = try {
            IDN.toASCII(withoutRootDot, IDN.USE_STD3_ASCII_RULES)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(INVALID_SERVER, error)
        }.lowercase(Locale.ROOT)
        require(asciiHost.isNotEmpty() && asciiHost.length <= MAX_HOST_LENGTH) { INVALID_SERVER }
        if (numericHost.matches(asciiHost)) validateIpv4(asciiHost)
        return asciiHost
    }

    private fun validateIpv4(host: String) {
        val octets = host.split('.')
        require(octets.size == 4) { INVALID_SERVER }
        octets.forEach { octet ->
            require(octet.isNotEmpty() && (octet == "0" || !octet.startsWith('0'))) { INVALID_SERVER }
            val value = octet.toIntOrNull()
            require(value != null && value in 0..255) { INVALID_SERVER }
        }
    }

    private const val DEFAULT_HTTPS_PORT = 443
    private const val MAX_HOST_LENGTH = 253
    private const val INVALID_SERVER = "服务器应为 HTTPS 域名、IP 地址或带端口地址"
    private const val HTTPS_ONLY = "服务器只支持 HTTPS"
}
