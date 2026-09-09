package com.richard.tunnelkeeper.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants

class AndroidVpnPlatform(private val service: VpnService) : VpnPlatform {
    override fun protectSocket(socketFileDescriptor: Int): Boolean = service.protect(socketFileDescriptor)

    override fun establishTunnel(configuration: TunnelConfiguration): TunnelFileDescriptor? {
        val builder = service.Builder()
            .setSession(configuration.sessionName)
            .setMtu(configuration.mtu)

        configuration.addresses.forEach { address ->
            builder.addAddress(address.address, address.prefixLength)
        }
        configuration.routes.forEach { route ->
            builder.addRoute(route.address, route.prefixLength)
        }
        configuration.dnsServers.forEach(builder::addDnsServer)
        if (configuration.allowIpv6Bypass) {
            builder.allowFamily(OsConstants.AF_INET6)
        }

        return builder.establish()?.let(::ParcelTunnelFileDescriptor)
    }

    private class ParcelTunnelFileDescriptor(
        private var descriptor: ParcelFileDescriptor?,
    ) : TunnelFileDescriptor {
        override val rawFd: Int
            get() = checkNotNull(descriptor) { "TUN file descriptor is already closed" }.fd

        override fun close() {
            val current = descriptor ?: return
            descriptor = null
            current.close()
        }
    }
}
