package com.richard.tunnelkeeper.vpn

internal object TunnelLogFormatter {
    fun lines(diagnostics: TunnelDiagnostics): List<String> = buildList {
        diagnostics.gatewayAddress?.takeIf(String::isNotBlank)?.let { gateway ->
            add("VPN 网关：${sanitizeValue(gateway)}")
        }
        val configuration = diagnostics.configuration
        add("VPN 地址：${formatValues(configuration.addresses.map { it.displayValue() })}")
        add("MTU：${configuration.mtu}")
        add("DNS：${formatValues(configuration.dnsServers)}")
        add(
            if (diagnostics.serverSplitIncludes.isEmpty()) {
                "服务端包含路由：未下发，按全隧道处理"
            } else {
                "服务端包含路由：${formatValues(diagnostics.serverSplitIncludes)}"
            },
        )
        add("服务端排除路由：${formatValues(diagnostics.serverSplitExcludes)}")
        add("提交的 VPN 路由：${formatValues(configuration.routes.map { it.displayValue() })}")
        val ipv6Routes = configuration.routes.filter { ':' in it.address }
        add(
            when {
                ipv6Routes.isNotEmpty() -> "IPv6：按服务端 VPN 路由承载"
                configuration.allowIpv6Bypass -> "IPv6：使用默认网络（IPv4 分流）"
                configuration.addresses.any { ':' in it.address } -> "IPv6：未下发 VPN 路由，使用默认网络"
                else -> "IPv6：已阻断（防止全隧道泄漏）"
            },
        )
    }

    private fun formatValues(values: List<String>): String {
        val sanitized = values
            .map(::sanitizeValue)
            .filter(String::isNotBlank)
            .distinct()
        if (sanitized.isEmpty()) return "未下发"
        val visible = sanitized.take(MAX_VISIBLE_VALUES).joinToString(", ")
        return if (sanitized.size > MAX_VISIBLE_VALUES) {
            "$visible，另有 ${sanitized.size - MAX_VISIBLE_VALUES} 条"
        } else {
            visible
        }
    }

    private fun sanitizeValue(value: String): String = buildString(minOf(value.length, MAX_VALUE_LENGTH)) {
        value.take(MAX_VALUE_LENGTH).forEach { character ->
            append(if (character.isISOControl()) ' ' else character)
        }
    }.trim()

    private fun NetworkPrefix.displayValue(): String = "$address/$prefixLength"

    private const val MAX_VISIBLE_VALUES = 12
    private const val MAX_VALUE_LENGTH = 96
}
