#pragma once
#include <cstdint>
#include <cstddef>

namespace geekoc::vpn {
constexpr uint32_t Magic = 0x474f4331;
constexpr uint32_t MaxRoutes = 64;
struct Route { uint32_t network = 0; uint32_t prefix = 0; };
struct Plan {
    uint32_t magic = Magic, version = 1, bytes = sizeof(Plan);
    uint32_t address = 0, prefix = 32, gateway = 0, mtu = 1400;
    uint32_t routeCount = 0, dnsCount = 0;
    Route routes[MaxRoutes]{};
    uint32_t dns[3]{};
};
struct Reply { uint32_t magic = Magic, phase = 0, error = 0, reserved = 0; uint64_t luid = 0; };
static_assert(sizeof(Plan) == 560);
static_assert(sizeof(Reply) == 24);
inline uint32_t mask(uint32_t prefix) { return prefix ? ~uint32_t(0) << (32 - prefix) : 0; }
inline bool unicast(uint32_t address) { return address && (address >> 24) != 127 && (address >> 24) < 224 && (address >> 24) != 0; }
inline bool contains(const Route &route, uint32_t address) { return route.prefix <= 32 && (address & mask(route.prefix)) == route.network; }
inline bool valid(const Plan &p) {
    if (p.magic != Magic || p.version != 1 || p.bytes != sizeof(Plan) || !unicast(p.address)
        || p.prefix < 1 || p.prefix > 32 || !p.gateway || p.mtu < 576 || p.mtu > 1500
        || !p.routeCount || p.routeCount > MaxRoutes || p.dnsCount > 3) return false;
    for (uint32_t i = 0; i < p.routeCount; ++i) {
        const auto &r = p.routes[i];
        // shortcut: IPv4 split routes only; full tunnel/IPv6 need separate leakage acceptance.
        if (r.prefix < 1 || r.prefix > 32 || !unicast(r.network)
            || (r.network & mask(r.prefix)) != r.network || contains(r, p.gateway)) return false;
        for (uint32_t j = 0; j < i; ++j)
            if (p.routes[j].network == r.network && p.routes[j].prefix == r.prefix) return false;
    }
    for (uint32_t i = 0; i < p.dnsCount; ++i) {
        if (!unicast(p.dns[i])) return false;
        bool routed = false;
        for (uint32_t j = 0; j < p.routeCount; ++j) routed |= contains(p.routes[j], p.dns[i]);
        if (!routed) return false;
    }
    return true;
}
inline bool ipv4Packet(const unsigned char *packet, size_t size) {
    return size >= 20 && size <= 65535 && packet[0] >> 4 == 4
        && (packet[0] & 15) >= 5 && size_t(packet[0] & 15) * 4 <= size
        && ((size_t(packet[2]) << 8) | packet[3]) == size;
}
}
