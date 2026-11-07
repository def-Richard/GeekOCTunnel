#include "core/NetworkProtocol.h"
#include "core/SessionDiagnostics.h"
#include <QtTest>
using namespace geekoc::vpn;
class NetworkPlanTests : public QObject {
    Q_OBJECT
    Plan base() { Plan p; p.address = 0xc0000202; p.gateway = 0x7f000001; p.routeCount = 1; p.routes[0] = {0xc0000200, 24}; p.dnsCount = 1; p.dns[0] = 0xc0000201; return p; }
private slots:
    void diagnosticBoundaries() {
        QVERIFY(authenticationRejected(nullptr, "Login failed: please re-enter the password."));
        QVERIFY(authenticationRejected("denied", ""));
        QVERIFY(authenticationRejected(nullptr, "Invalid credentials."));
        QVERIFY(!authenticationRejected(nullptr, "Please enter your password."));
        QVERIFY(!authenticationRejected(nullptr, "If login failed, contact your administrator."));
        QVERIFY(!authenticationRejected(nullptr, "private server response"));
        auto p = base();
        auto details = networkDetails(p, 1);
        QCOMPARE(details.value("address").toString(), QString("192.0.2.2"));
        QCOMPARE(details.value("routes").toArray()[0].toObject().value("destination").toString(), QString("192.0.2.0/24"));
        QCOMPARE(details.value("dns").toArray(), QJsonArray{"192.0.2.1"});
        p.dnsCount = 0; QVERIFY(networkDetails(p, 1).value("dns").toArray().isEmpty());
        p.routeCount = MaxRoutes + 1; QVERIFY(networkDetails(p, 1).isEmpty());
        QCOMPARE(QString(protocolDiagnostic("SSL negotiation with %s\n")), QString("tls_negotiating"));
        QCOMPARE(QString(protocolDiagnostic("Established DTLS connection (using GnuTLS). Ciphersuite %s.\n")), QString("dtls_connected"));
        QCOMPARE(QString(protocolDiagnostic("DTLS handshake failed: %s\n")), QString("dtls_fallback"));
        QVERIFY(!retryableFailure("dtls_fallback"));
        QVERIFY(protocolDiagnostic("Set-Cookie: session-token=private\n") == nullptr);
        QVERIFY(protocolDiagnostic("%s\n") == nullptr);
        QVERIFY(protocolDiagnostic("POST / password=private\n") == nullptr);
        QCOMPARE(QString(certificateIssue("certificate expired")), QString("certificate_expired"));
        QCOMPARE(QString(certificateIssue("private server text")), QString("certificate_invalid"));
        for (const char *code : {"connection_lost","gateway_unreachable","dns_resolution_failed","tls_handshake_failed"}) QVERIFY(retryableFailure(code));
        for (const char *code : {"authentication_rejected","authentication_failed","certificate_rejected","permission_denied","route_conflict","cancelled","network_cleanup_unconfirmed"}) QVERIFY(!retryableFailure(code));
        QCOMPARE(reconnectDelay(0), 2); QCOMPARE(reconnectDelay(2), 8); QCOMPARE(reconnectDelay(100), 60);
    }
    void safetyBoundary() {
        auto p = base(); QVERIFY(valid(p));
        p.routes[0] = {0, 0}; QVERIFY(!valid(p));
        p = base(); p.gateway = 0xc0000203; QVERIFY(!valid(p));
        p = base(); p.routes[0].prefix = 33; QVERIFY(!valid(p));
        p = base(); p.routeCount = MaxRoutes + 1; QVERIFY(!valid(p));
        p = base(); p.dns[0] = 0xc6336401; QVERIFY(!valid(p));
        p = base(); p.routeCount = 2; p.routes[1] = p.routes[0]; QVERIFY(!valid(p));
        p = base(); p.address = 0xe0000001; QVERIFY(!valid(p));
        p = base(); p.mtu = 65535; QVERIFY(!valid(p));
    }
    void packetBounds() {
        unsigned char packet[20] = {0x45, 0, 0, 20}; QVERIFY(ipv4Packet(packet, 20));
        QVERIFY(!ipv4Packet(packet, 19)); packet[0] = 0x65; QVERIFY(!ipv4Packet(packet, 20));
        packet[0] = 0x4f; QVERIFY(!ipv4Packet(packet, 20));
        packet[0] = 0x45; packet[3] = 21; QVERIFY(!ipv4Packet(packet, 20));
    }
};
QTEST_GUILESS_MAIN(NetworkPlanTests)
#include "network_plan_test.moc"
