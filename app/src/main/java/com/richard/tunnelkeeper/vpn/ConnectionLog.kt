package com.richard.tunnelkeeper.vpn

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConnectionLogEntry(
    val timestamp: String,
    val message: String,
)

object ConnectionLog {
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val mutableEntries = MutableStateFlow<List<ConnectionLogEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()

    @Synchronized
    fun add(message: String) {
        val normalizedMessage = buildString(minOf(message.length, MAX_MESSAGE_LENGTH)) {
            message.take(MAX_MESSAGE_LENGTH).forEach { character ->
                append(if (character.isISOControl()) ' ' else character)
            }
        }.trim()
        if (normalizedMessage.isEmpty()) return
        mutableEntries.value = (mutableEntries.value + ConnectionLogEntry(
            timestamp = LocalTime.now().format(clockFormat),
            message = normalizedMessage,
        )).takeLast(MAX_ENTRIES)
    }

    private const val MAX_ENTRIES = 120
    private const val MAX_MESSAGE_LENGTH = 1_024
}
