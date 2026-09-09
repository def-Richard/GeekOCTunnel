package com.richard.tunnelkeeper.vpn

/** Keeps the platform-owned descriptor alive for as long as native code can reference its raw fd. */
internal class NativeTunFileDescriptorLease : AutoCloseable {
    private var descriptor: TunnelFileDescriptor? = null
    private var closed = false

    val isAttached: Boolean
        get() = descriptor != null

    fun establishAndAttach(
        establish: () -> TunnelFileDescriptor,
        attachToNative: (Int) -> Int,
    ): Int {
        check(!closed) { "TUN file descriptor lease is already closed" }
        check(descriptor == null) { "TUN file descriptor is already attached" }

        val established = establish()
        descriptor = established
        return attachToNative(established.rawFd)
    }

    fun closeAfterNativeCleanup(nativeCleanup: () -> Unit) {
        try {
            nativeCleanup()
        } finally {
            close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val current = descriptor
        descriptor = null
        current?.close()
    }
}
