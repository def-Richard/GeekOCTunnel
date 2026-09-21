package com.richard.tunnelkeeper.vpn

/** In-place, stateless address translation. Ports and ICMP identifiers remain unchanged. */
internal object Ipv4PacketTranslator {
    fun address(value: String): Long {
        val parts = value.split('.')
        require(parts.size == 4) { "无效 IPv4 地址：$value" }
        return parts.fold(0L) { result, part ->
            val octet = part.toIntOrNull()
            require(octet != null && octet in 0..255) { "无效 IPv4 地址：$value" }
            (result shl 8) or octet.toLong()
        }
    }

    fun readAddress(packet: ByteArray, offset: Int): Long =
        (word(packet, offset).toLong() shl 16) or word(packet, offset + 2).toLong()

    fun translate(
        packet: ByteArray,
        length: Int,
        from: Long,
        to: Long,
        outgoing: Boolean,
    ): Boolean {
        if (length !in 20..packet.size || u8(packet, 0) ushr 4 != 4) return false
        val header = (u8(packet, 0) and 15) * 4
        if (header !in 20..length || word(packet, 2) != length) return false
        val addressOffset = if (outgoing) 12 else 16
        if (readAddress(packet, addressOffset) != from) return false
        if (from == to) return true
        val firstFragment = word(packet, 6) and 0x1fff == 0
        val protocol = u8(packet, 9)
        if (firstFragment) {
            val required = when (protocol) { 6 -> 18; 17 -> 8; 1 -> 8; else -> return false }
            if (length - header < required) return false
            if (protocol == 1 && u8(packet, header) in ERROR_TYPES) {
                val quote = header + 8
                if (length - quote < 20 || u8(packet, quote) ushr 4 != 4) return false
                val quoteHeader = (u8(packet, quote) and 15) * 4
                if (quoteHeader < 20 || quote + quoteHeader > length) return false
                // An ICMP error quotes the packet travelling in the opposite direction.
                val quoteAddress = quote + if (outgoing) 16 else 12
                if (readAddress(packet, quoteAddress) != from) return false
                val before = sum(packet, quote, length)
                rewriteHeader(packet, quote, length, from, to, !outgoing, quoted = true)
                val after = sum(packet, quote, length)
                putWord(packet, header + 2, adjust(word(packet, header + 2), before, after))
            }
        }
        rewriteHeader(packet, 0, length, from, to, outgoing, quoted = false)
        return true
    }

    private fun rewriteHeader(
        bytes: ByteArray, start: Int, end: Int, from: Long, to: Long,
        source: Boolean, quoted: Boolean,
    ) {
        val addressOffset = start + if (source) 12 else 16
        val fromSum = fold((from ushr 16).toInt() + (from and 0xffff).toInt())
        val toSum = fold((to ushr 16).toInt() + (to and 0xffff).toInt())
        putWord(bytes, addressOffset, (to ushr 16).toInt())
        putWord(bytes, addressOffset + 2, to.toInt())
        putWord(bytes, start + 10, adjust(word(bytes, start + 10), fromSum, toSum))
        if (word(bytes, start + 6) and 0x1fff != 0) return
        val transport = start + (u8(bytes, start) and 15) * 4
        val protocol = u8(bytes, start + 9)
        val checkOffset = when (protocol) { 6 -> transport + 16; 17 -> transport + 6; else -> return }
        // ICMP quotations often contain only eight bytes of the original transport header.
        if (quoted && checkOffset + 2 > end) return
        val old = word(bytes, checkOffset)
        if (protocol == 17 && old == 0) return // IPv4 UDP zero means checksum disabled.
        var replacement = adjust(old, fromSum, toSum)
        if (protocol == 17 && replacement == 0) replacement = 0xffff
        putWord(bytes, checkOffset, replacement)
    }

    private fun adjust(checksum: Int, old: Int, new: Int): Int =
        fold((checksum xor 0xffff) + (old xor 0xffff) + new) xor 0xffff

    private fun sum(bytes: ByteArray, start: Int, end: Int): Int {
        var total = 0
        var index = start
        while (index + 1 < end) {
            total += word(bytes, index)
            index += 2
        }
        if (index < end) total += u8(bytes, index) shl 8
        return fold(total)
    }

    private fun fold(value: Int): Int {
        var result = value
        while (result ushr 16 != 0) result = (result and 0xffff) + (result ushr 16)
        return result
    }

    private fun u8(bytes: ByteArray, offset: Int) = bytes[offset].toInt() and 255
    private fun word(bytes: ByteArray, offset: Int) = (u8(bytes, offset) shl 8) or u8(bytes, offset + 1)
    private fun putWord(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private val ERROR_TYPES = setOf(3, 4, 5, 11, 12)
}
