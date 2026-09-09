package com.richard.tunnelkeeper.model

data class SslGroup(
    val value: String,
    val label: String,
    val isDefault: Boolean = false,
)

data class VpnCredentials(
    val username: String,
    val password: String,
    val sslGroup: String,
)
