#pragma once
#include "NetworkProtocol.h"
#include <QHostAddress>
#include <QJsonArray>
#include <QJsonObject>
#include <QString>
#include <string_view>

namespace geekoc::vpn {
inline bool authenticationRejected(const char *error, const char *message) {
    if (error && *error) return true;
    const auto text = QString::fromUtf8(message ? message : "").left(512).trimmed().toLower();
    // Some gateways put a refusal in the next form's message instead of its error field.
    for (const auto prefix : {"login failed", "authentication failed", "invalid password", "incorrect password", "invalid credentials", "access denied"}) {
        const auto value = QString::fromLatin1(prefix);
        if (text == value || text.startsWith(value + ':') || text.startsWith(value + '.') || text.startsWith(value + ' ')) return true;
    }
    return false;
}
inline QJsonObject networkDetails(const Plan &plan, uint32_t serverRoutes) {
    if (!valid(plan) || serverRoutes > plan.routeCount) return {};
    QJsonArray routes, dns;
    for (uint32_t i = 0; i < plan.routeCount; ++i)
        routes.append(QJsonObject{{"destination", QHostAddress(plan.routes[i].network).toString() + '/' + QString::number(plan.routes[i].prefix)},
                                  {"source", i < serverRoutes ? "server" : "dns"}});
    for (uint32_t i = 0; i < plan.dnsCount; ++i) dns.append(QHostAddress(plan.dns[i]).toString());
    return {{"address", QHostAddress(plan.address).toString()}, {"prefix", int(plan.prefix)},
            {"gateway", QHostAddress(plan.gateway).toString()}, {"mtu", int(plan.mtu)},
            {"transport", "TLS / TCP"}, {"routes", routes}, {"dns", dns}};
}
inline const char *certificateIssue(std::string_view reason) {
    if (reason == "certificate expired") return "certificate_expired";
    if (reason == "certificate not yet activated") return "certificate_not_yet_valid";
    if (reason == "certificate revoked") return "certificate_revoked";
    if (reason == "certificate does not match hostname" || reason == "certificate does not match SNI") return "certificate_hostname_mismatch";
    if (reason == "signer not found" || reason == "signer not a CA certificate") return "certificate_untrusted_issuer";
    return "certificate_invalid";
}
// Inspect only known format templates. Never format or forward server text, headers or variadic arguments.
inline const char *protocolDiagnostic(std::string_view format) {
    if (format == "Established DTLS connection (using GnuTLS). Ciphersuite %s.\n") return "dtls_connected";
    if (format == "DTLS handshake failed: %s\n" || format == "DTLS Dead Peer Detection detected dead peer!\n"
        || format == "DTLS Rehandshake failed; reconnecting.\n"
        || format == "DTLS session resume failed; possible MITM attack. Disabling DTLS.\n") return "dtls_fallback";
    if (format == "Connected to %s%s%s:%s\n") return "tcp_connected";
    if (format == "SSL negotiation with %s\n") return "tls_negotiating";
    if (format == "Connected to HTTPS on %s with ciphersuite %s\n") return "tls_connected";
    if (format == "Failed to connect to host %s\n") return "gateway_unreachable";
    if (format == "getaddrinfo failed for host '%s': %s\n") return "dns_resolution_failed";
    if (format == "SSL connection failure: %s\n" || format == "SSL connection failure due to fatal alert: %s\n") return "tls_handshake_failed";
    if (format == "Failed to parse server response\n") return "invalid_gateway_response";
    if (format == "Cookie is no longer valid, ending session\n") return "session_expired";
    return nullptr;
}
inline bool retryableFailure(std::string_view code) {
    return code == "connection_lost" || code == "gateway_unreachable" || code == "dns_resolution_failed"
        || code == "tls_handshake_failed" || code == "session_expired";
}
inline int reconnectDelay(unsigned attempt) {
    constexpr int seconds[]{2, 4, 8, 15, 30, 60};
    return seconds[attempt < 6 ? attempt : 5];
}
}
