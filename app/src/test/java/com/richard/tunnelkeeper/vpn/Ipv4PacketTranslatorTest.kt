package com.richard.tunnelkeeper.vpn

import org.junit.Assert.*
import org.junit.Test

class Ipv4PacketTranslatorTest {
    private val phone = ip("10.10.0.2")
    private val office = ip("10.30.0.7")
    private val target = ip("192.0.2.88")

    @Test fun `TCP UDP and ICMP survive translation in both directions with valid checksums`() {
        for (protocol in listOf(6, 17, 1)) {
            for (options in listOf(0, 8)) {
                val original = packet(protocol, phone, target, options = options)
                val outgoing = original.copyOf()
                assertTrue(translate(outgoing, phone, office, true))
                assertEquals(office, Ipv4PacketTranslator.readAddress(outgoing, 12))
                verify(outgoing)
                assertTrue(translate(outgoing, office, phone, true))
                assertArrayEquals(original, outgoing)

                val reply = packet(protocol, target, office, options = options)
                assertTrue(translate(reply, office, phone, false))
                assertEquals(phone, Ipv4PacketTranslator.readAddress(reply, 16))
                verify(reply)
            }
        }
    }

    @Test fun `IPv4 UDP disabled checksum stays zero`() {
        val bytes = packet(17, phone, target)
        set16(bytes, 26, 0)
        assertTrue(translate(bytes, phone, office, true))
        assertEquals(0, get16(bytes, 26))
        assertEquals(0, checksum(bytes.copyOfRange(0, 20)))
    }

    @Test fun `fragmented TCP remains valid after reassembly`() {
        val original = packet(6, phone, target, data = ByteArray(95) { it.toByte() })
        val first = original.copyOfRange(0, 44) // First 24 transport bytes, including TCP checksum.
        set16(first, 2, first.size)
        set16(first, 6, 0x2000)
        fixIpChecksum(first)
        val tail = original.copyOfRange(0, 20) + original.copyOfRange(44, original.size)
        set16(tail, 2, tail.size)
        set16(tail, 6, 3)
        fixIpChecksum(tail)
        assertTrue(translate(first, phone, office, true))
        assertTrue(translate(tail, phone, office, true))
        assertEquals(0, checksum(first.copyOfRange(0, 20)))
        assertEquals(0, checksum(tail.copyOfRange(0, 20)))
        val reassembled = first + tail.copyOfRange(20, tail.size)
        set16(reassembled, 2, reassembled.size)
        set16(reassembled, 6, 0)
        fixIpChecksum(reassembled)
        verify(reassembled)
    }

    @Test fun `ICMP errors translate quoted address header and available transport checksum`() {
        for (quoteFull in listOf(false, true)) {
            for (protocol in listOf(6, 17, 1)) {
                val original = packet(protocol, office, target)
                val quoted = if (quoteFull) original else original.copyOf(28)
                val error = packet(1, target, office, data = quoted, icmpType = 3)
                assertTrue(translate(error, office, phone, false))
                verify(error)
                assertEquals(phone, Ipv4PacketTranslator.readAddress(error, 28 + 12))
                assertEquals(0, checksum(error.copyOfRange(28, 48)))
                if (quoteFull) verify(error.copyOfRange(28, error.size))
            }
        }
    }

    @Test fun `outgoing ICMP errors translate the quoted destination`() {
        val inbound = packet(17, target, phone)
        val error = packet(1, phone, target, data = inbound, icmpType = 11)
        assertTrue(translate(error, phone, office, true))
        verify(error)
        val quoted = error.copyOfRange(28, error.size)
        assertEquals(office, Ipv4PacketTranslator.readAddress(quoted, 16))
        verify(quoted)
    }

    @Test fun `wrong source and malformed input are rejected without escaping array bounds`() {
        val source = packet(6, target, phone)
        val snapshot = source.copyOf()
        assertFalse(translate(source, phone, office, true))
        assertArrayEquals(snapshot, source)
        for (length in 0..80) {
            val bytes = ByteArray(length) { 0x45 }
            assertFalse(translate(bytes, phone, office, true))
        }
        val invalidQuote = packet(1, target, office, data = ByteArray(19), icmpType = 3)
        assertFalse(translate(invalidQuote, office, phone, false))
    }

    @Test fun `checksum adjustment covers carry and zero representations`() {
        for (suffix in 0..255) {
            val source = ip("10.255.255.$suffix")
            val destination = ip("172.16.0.${255 - suffix}")
            for (protocol in listOf(6, 17)) {
                val bytes = packet(protocol, source, target)
                assertTrue(translate(bytes, source, destination, true))
                verify(bytes)
            }
        }
    }

    private fun translate(bytes: ByteArray, from: Long, to: Long, outgoing: Boolean) =
        Ipv4PacketTranslator.translate(bytes, bytes.size, from, to, outgoing)

    private fun ip(value: String) = Ipv4PacketTranslator.address(value)

    private fun packet(
        protocol: Int, source: Long, destination: Long, options: Int = 0,
        data: ByteArray = byteArrayOf(1, 2, 3, 4, 5), icmpType: Int = 8,
    ): ByteArray {
        val header = 20 + options
        val transportHeader = if (protocol == 6) 20 else 8
        val bytes = ByteArray(header + transportHeader + data.size)
        bytes[0] = (0x40 + header / 4).toByte()
        bytes[8] = 64
        bytes[9] = protocol.toByte()
        set16(bytes, 2, bytes.size)
        set32(bytes, 12, source)
        set32(bytes, 16, destination)
        set16(bytes, header, 40123)
        set16(bytes, header + 2, 443)
        when (protocol) {
            6 -> { bytes[header + 12] = 0x50; bytes[header + 13] = 0x18; set16(bytes, header + 14, 32768) }
            17 -> set16(bytes, header + 4, transportHeader + data.size)
            1 -> { bytes[header] = icmpType.toByte(); bytes[header + 1] = 0; set16(bytes, header + 2, 0) }
        }
        data.copyInto(bytes, header + transportHeader)
        val transport = bytes.copyOfRange(header, bytes.size)
        val check = if (protocol == 1) checksum(transport) else checksum(pseudo(bytes, transport.size) + transport)
        set16(bytes, header + when (protocol) { 6 -> 16; 17 -> 6; else -> 2 },
            if (protocol == 17 && check == 0) 0xffff else check)
        fixIpChecksum(bytes)
        return bytes
    }

    private fun verify(bytes: ByteArray) {
        val header = (bytes[0].toInt() and 15) * 4
        assertEquals("IPv4 checksum", 0, checksum(bytes.copyOfRange(0, header)))
        val transport = bytes.copyOfRange(header, bytes.size)
        val protocol = bytes[9].toInt() and 255
        val complete = if (protocol == 1) transport else pseudo(bytes, transport.size) + transport
        assertEquals("Protocol $protocol checksum", 0, checksum(complete))
    }

    private fun fixIpChecksum(bytes: ByteArray) {
        set16(bytes, 10, 0)
        set16(bytes, 10, checksum(bytes.copyOfRange(0, (bytes[0].toInt() and 15) * 4)))
    }

    private fun pseudo(bytes: ByteArray, size: Int) = bytes.copyOfRange(12, 20) +
        byteArrayOf(0, bytes[9], (size ushr 8).toByte(), size.toByte())

    // Independent reference calculation over the full wire packet, not the production incremental formula.
    private fun checksum(bytes: ByteArray): Int {
        var sum = 0L
        bytes.indices.step(2).forEach { index ->
            sum += ((bytes[index].toInt() and 255) shl 8) +
                if (index + 1 < bytes.size) (bytes[index + 1].toInt() and 255) else 0
        }
        while (sum > 0xffff) sum = (sum and 0xffff) + (sum shr 16)
        return sum.toInt().inv() and 0xffff
    }

    private fun get16(bytes: ByteArray, offset: Int) =
        ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
    private fun set16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte(); bytes[offset + 1] = value.toByte()
    }
    private fun set32(bytes: ByteArray, offset: Int, value: Long) {
        set16(bytes, offset, (value ushr 16).toInt()); set16(bytes, offset + 2, value.toInt())
    }
}
