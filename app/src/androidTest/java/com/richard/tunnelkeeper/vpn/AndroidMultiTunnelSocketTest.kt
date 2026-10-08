package com.richard.tunnelkeeper.vpn

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Local socket checks only: no VPN permission, gateway, profiles or credentials are needed. */
class AndroidMultiTunnelSocketTest {
    @Test(timeout = 15_000)
    fun emptyReadsOnBothEndpointsWouldBlockImmediately() {
        val pair = AndroidMultiTunnel.datagramPair()
        pair.first.use { first ->
            pair.second.use { second ->
                assertWouldBlock(first.fileDescriptor)
                assertWouldBlock(second.fileDescriptor)
            }
        }
    }

    @Test(timeout = 15_000)
    fun duplicatedEndpointsKeepBidirectionalDatagramBoundaries() {
        val pair = AndroidMultiTunnel.datagramPair()
        pair.first.use { first ->
            pair.second.use { second ->
                val a = byteArrayOf(1, 2, 3)
                val b = byteArrayOf(4, 5)
                assertEquals(a.size, Os.write(first.fileDescriptor, a, 0, a.size))
                assertEquals(b.size, Os.write(first.fileDescriptor, b, 0, b.size))
                assertDatagram(second.fileDescriptor, a)
                assertDatagram(second.fileDescriptor, b)
                assertWouldBlock(second.fileDescriptor)

                assertEquals(b.size, Os.write(second.fileDescriptor, b, 0, b.size))
                assertEquals(a.size, Os.write(second.fileDescriptor, a, 0, a.size))
                assertDatagram(first.fileDescriptor, b)
                assertDatagram(first.fileDescriptor, a)
                assertWouldBlock(first.fileDescriptor)
            }
        }
    }

    private fun assertWouldBlock(fd: FileDescriptor) {
        withReadWatchdog(fd) {
            try {
                Os.read(fd, ByteArray(1), 0, 1)
                fail("An empty nonblocking datagram socket must report EAGAIN")
            } catch (error: ErrnoException) {
                assertEquals(OsConstants.EAGAIN, error.errno)
            }
        }
    }

    private fun assertDatagram(fd: FileDescriptor, expected: ByteArray) {
        withReadWatchdog(fd) {
            val buffer = ByteArray(64)
            val count = Os.read(fd, buffer, 0, buffer.size)
            assertEquals(expected.size, count)
            assertArrayEquals(expected, buffer.copyOf(count))
        }
    }

    private fun withReadWatchdog(fd: FileDescriptor, block: () -> Unit) {
        val timedOut = AtomicBoolean(false)
        val armed = AtomicBoolean(true)
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        val timeout = watchdog.schedule({
            if (armed.compareAndSet(true, false)) {
                timedOut.set(true)
                // Wake a mistakenly blocking read before failing, so the test cannot hang on it.
                runCatching { Os.shutdown(fd, OsConstants.SHUT_RDWR) }
            }
        }, 3, TimeUnit.SECONDS)
        try {
            block()
        } finally {
            armed.set(false)
            timeout.cancel(false)
            watchdog.shutdown()
            // A watchdog that already won the race must finish before these FDs are reused/closed.
            assertTrue("Read watchdog did not stop", watchdog.awaitTermination(5, TimeUnit.SECONDS))
            assertFalse("Datagram read blocked instead of returning immediately", timedOut.get())
        }
    }
}
