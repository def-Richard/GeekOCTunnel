package com.richard.tunnelkeeper.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTunFileDescriptorLeaseTest {
    @Test
    fun `successful native attachment retains descriptor until disconnect`() {
        val descriptor = FakeTunnelFileDescriptor(41)
        val lease = NativeTunFileDescriptorLease()
        var borrowedFd = -1

        val result = lease.establishAndAttach(
            establish = { descriptor },
            attachToNative = { fd ->
                borrowedFd = fd
                0
            },
        )

        assertEquals(0, result)
        assertEquals(41, borrowedFd)
        assertTrue(lease.isAttached)
        assertEquals(0, descriptor.closeCount)

        lease.close()
        assertEquals(1, descriptor.closeCount)
        assertFalse(lease.isAttached)
    }

    @Test
    fun `native setup failure retains borrowed descriptor until session cleanup`() {
        val descriptor = FakeTunnelFileDescriptor(42)
        val lease = NativeTunFileDescriptorLease()

        val result = lease.establishAndAttach(
            establish = { descriptor },
            attachToNative = { -5 },
        )

        assertEquals(-5, result)
        assertEquals(0, descriptor.closeCount)
        lease.close()
        assertEquals(1, descriptor.closeCount)
    }

    @Test
    fun `native setup exception retains borrowed descriptor until session cleanup`() {
        val descriptor = FakeTunnelFileDescriptor(43)
        val lease = NativeTunFileDescriptorLease()

        assertThrows(IllegalStateException::class.java) {
            lease.establishAndAttach(
                establish = { descriptor },
                attachToNative = { error("native setup failed") },
            )
        }

        assertEquals(0, descriptor.closeCount)
        lease.close()
        assertEquals(1, descriptor.closeCount)
    }

    @Test
    fun `descriptor closes after native cleanup even when cleanup fails`() {
        val descriptor = FakeTunnelFileDescriptor(47)
        val lease = NativeTunFileDescriptorLease()
        lease.establishAndAttach(establish = { descriptor }, attachToNative = { 0 })

        assertThrows(IllegalArgumentException::class.java) {
            lease.closeAfterNativeCleanup {
                assertEquals(0, descriptor.closeCount)
                throw IllegalArgumentException("native destroy failed")
            }
        }

        assertEquals(1, descriptor.closeCount)
        assertFalse(lease.isAttached)
    }

    @Test
    fun `cancel before tunnel establishment creates no descriptor`() {
        val lease = NativeTunFileDescriptorLease()
        var establishCalled = false
        lease.close()

        assertThrows(IllegalStateException::class.java) {
            lease.establishAndAttach(
                establish = {
                    establishCalled = true
                    FakeTunnelFileDescriptor(44)
                },
                attachToNative = { 0 },
            )
        }

        assertFalse(establishCalled)
    }

    @Test
    fun `repeated attachment is rejected without creating another descriptor`() {
        val first = FakeTunnelFileDescriptor(45)
        val lease = NativeTunFileDescriptorLease()
        lease.establishAndAttach(establish = { first }, attachToNative = { 0 })
        var secondEstablishCalled = false

        assertThrows(IllegalStateException::class.java) {
            lease.establishAndAttach(
                establish = {
                    secondEstablishCalled = true
                    FakeTunnelFileDescriptor(46)
                },
                attachToNative = { 0 },
            )
        }

        assertFalse(secondEstablishCalled)
        lease.close()
        lease.close()
        assertEquals(1, first.closeCount)
    }

    private class FakeTunnelFileDescriptor(
        override val rawFd: Int,
    ) : TunnelFileDescriptor {
        var closeCount = 0
            private set

        override fun close() {
            closeCount += 1
        }
    }
}
