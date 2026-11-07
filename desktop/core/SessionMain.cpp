#ifdef _WIN32
#include "platform/windows/SessionPlatform.h"
#else
#include "platform/linux/SessionPlatform.h"
#endif
#include "core/ProfileStore.h"
#include "core/SessionDiagnostics.h"
#ifdef _MSC_VER
#define __attribute__(x)
#endif
#include <openconnect.h>
#ifdef _MSC_VER
#undef __attribute__
#endif
#include <QCommandLineParser>
#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDateTime>
#include <QFileInfo>
#include <QHostAddress>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLibrary>
#include <QLockFile>
#include <QStandardPaths>
#include <QUuid>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cerrno>
#include <mutex>
#include <thread>

using namespace geekoc;
using namespace geekoc::vpn;

#define OC_FUNCTIONS(F) \
 F(openconnect_vpninfo_new) F(openconnect_vpninfo_free) F(openconnect_init_ssl) \
 F(openconnect_parse_url) F(openconnect_set_protocol) F(openconnect_set_reported_os) \
 F(openconnect_setup_cmd_pipe) F(openconnect_obtain_cookie) F(openconnect_make_cstp_connection) \
 F(openconnect_setup_tun_fd) F(openconnect_mainloop) F(openconnect_get_ip_info) \
 F(openconnect_set_option_value) F(openconnect_get_peer_cert_hash) F(openconnect_check_peer_cert_hash) \
 F(openconnect_set_cafile) F(openconnect_set_loglevel) F(openconnect_disable_ipv6) \
 F(openconnect_set_stats_handler) F(openconnect_set_reqmtu) F(openconnect_setup_dtls) \
 F(openconnect_disable_dtls) F(openconnect_get_dtls_cipher)

struct Api {
    QLibrary library;
#define DECLARE(name) decltype(&::name) name = nullptr;
    OC_FUNCTIONS(DECLARE)
#undef DECLARE
    Api() : library(openconnectLibrary()) {
        initializePlatform();
        check(library.load(), "openconnect_missing");
#define LOAD(name) name = reinterpret_cast<decltype(name)>(library.resolve(#name)); check(name != nullptr, "openconnect_api_missing");
        OC_FUNCTIONS(LOAD)
#undef LOAD
        check(openconnect_init_ssl() == 0, "tls_init_failed");
    }
};
static void require(bool condition, const char *error) { if (!condition) throw std::runtime_error(error); }
static QString safeLabel(const char *value) {
    auto label = QString::fromUtf8(value ? value : "").left(512);
    for (auto &c : label) if (!c.isPrint() && c != '\n') c = ' ';
    return label;
}
struct Session {
    Api api;
    openconnect_info *vpn = nullptr;
    HelperSession helper;
    StopEvent cancelled;
    std::atomic_bool stopping{false}, readerDone{false};
    std::mutex outputMutex, commandMutex, replyMutex;
    std::condition_variable changed;
    CommandSocket command = InvalidCommand;
    QJsonObject response;
    QString challenge, id, credentialTarget;
    QJsonObject pendingCredentials;
    QJsonObject cachedCredentials;
    QJsonObject groupTransitionValues, pendingChoices;
    bool remember = false, credentialAttempted = false, credentialSaved = false;
    std::string failureCode;
    uint32_t serverRouteCount = 0;
    bool autoReconnect = true, ignoreCertificateErrors = false, udpEnabled = false;
    QString transport;
    std::atomic_uint generation{1};
    uint64_t sequence = 0;
    ~Session() {
        std::lock_guard guard(commandMutex);
        command = InvalidCommand;
        if (vpn) api.openconnect_vpninfo_free(vpn);
    }
    void emit(QJsonObject event) {
        std::lock_guard guard(outputMutex);
        event.insert("schemaVersion", 1); event.insert("sessionId", id);
        event.insert("sequence", double(++sequence)); event.insert("generation", int(generation.load()));
        event.insert("timestamp", QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs));
        const auto bytes = QJsonDocument(event).toJson(QJsonDocument::Compact) + '\n';
        if (!writeOutput(bytes)) stopping = true;
    }
    void state(const char *value) { emit({{"event", "state"}, {"state", value}}); }
    void log(const char *code, const char *level = "info") { emit({{"event", "log"}, {"code", code}, {"level", level}}); }
    void reportTransport(bool dtls) {
        const auto current = QString::fromLatin1(dtls ? "DTLS / UDP" : "TLS / TCP");
        if (current == transport) return;
        transport = current;
        emit({{"event", "transport"}, {"transport", transport}, {"udpEnabled", udpEnabled}});
    }
    void freeVpn() {
        std::lock_guard guard(commandMutex);
        command = InvalidCommand;
        if (vpn) { api.openconnect_vpninfo_free(vpn); vpn = nullptr; }
    }
    void sendCommand(char value) {
        std::lock_guard guard(commandMutex);
        if (command != InvalidCommand) writeCommand(command, value);
    }
    void cancel() {
        stopping = true; cancelled.set(); changed.notify_all();
        sendCommand(OC_CMD_CANCEL); helper.requestStop();
    }
    QJsonObject ask(QJsonObject event) {
        const auto token = QUuid::createUuid().toString(QUuid::WithoutBraces);
        { std::lock_guard guard(replyMutex); challenge = token; response = {}; }
        event.insert("challengeId", token); emit(event);
        std::unique_lock lock(replyMutex);
        changed.wait_for(lock, std::chrono::seconds(120), [&] { return stopping || !response.isEmpty(); });
        auto result = response; response = {}; challenge.clear();
        return result;
    }
    QJsonObject savedCredentials() {
        if (credentialAttempted) return cachedCredentials;
        credentialAttempted = true;
        auto bytes = readSecret(credentialTarget);
        const auto object = QJsonDocument::fromJson(bytes).object();
        bytes.fill('\0');
        cachedCredentials = object; return cachedCredentials;
    }
    void saveCredentials() {
        if (!remember || pendingCredentials.isEmpty()) return;
        auto bytes = QJsonDocument(pendingCredentials).toJson(QJsonDocument::Compact);
        credentialSaved = writeSecret(credentialTarget, bytes);
        bytes.fill('\0'); pendingCredentials = {};
        emit({{"event", "credential"}, {"saved", credentialSaved}});
    }
    static int certificate(void *opaque, const char *reason) {
        auto &s = *static_cast<Session *>(opaque);
        if (s.stopping) return -1;
        if (s.ignoreCertificateErrors) { s.log("certificate_verification_disabled", "warning"); return 0; }
        const auto fingerprint = safeLabel(s.api.openconnect_get_peer_cert_hash(s.vpn));
        const auto issue = certificateIssue(reason ? reason : "");
        const auto result = s.ask({{"event", "certificate"}, {"fingerprint", fingerprint}, {"reason", issue}});
        const bool accepted = result.value("accept").toBool() && result.value("fingerprint").toString() == fingerprint && !s.stopping;
        if (!accepted) s.failureCode = result.isEmpty() && !s.stopping ? "certificate_timeout" : "certificate_rejected";
        s.log(accepted ? "certificate_accepted_once" : "certificate_rejected", "warning");
        return accepted ? 0 : -1;
    }
    static int authenticate(void *opaque, oc_auth_form *form) {
        auto &s = *static_cast<Session *>(opaque);
        try {
            if (s.stopping) return OC_FORM_RESULT_CANCELLED;
            const bool rejected = authenticationRejected(form->error, form->message);
            if (rejected) { s.cachedCredentials = {}; s.pendingCredentials = {}; s.groupTransitionValues = {}; s.pendingChoices = {}; s.failureCode = "authentication_rejected"; }
            QJsonArray fields; int count = 0;
            for (auto *opt = form->opts; opt; opt = opt->next) {
                if ((opt->flags & OC_FORM_OPT_IGNORE) || opt->type == OC_FORM_OPT_HIDDEN) continue;
                require(++count <= 16, "unsupported_authentication");
                require(opt->type == OC_FORM_OPT_TEXT || opt->type == OC_FORM_OPT_PASSWORD || opt->type == OC_FORM_OPT_SELECT, "unsupported_authentication");
                QJsonObject field{{"name", safeLabel(opt->name)}, {"label", safeLabel(opt->label)},
                    {"secret", opt->type == OC_FORM_OPT_PASSWORD}, {"type", opt->type == OC_FORM_OPT_SELECT ? "select" : "text"}};
                if (opt->type == OC_FORM_OPT_SELECT) {
                    auto *select = reinterpret_cast<oc_form_opt_select *>(opt);
                    require(select->nr_choices >= 0 && select->nr_choices <= 128, "unsupported_authentication");
                    QJsonArray choices;
                    for (int i = 0; i < select->nr_choices; ++i)
                        choices.append(QJsonObject{{"value", safeLabel(select->choices[i]->name)}, {"label", safeLabel(select->choices[i]->label)}});
                    field.insert("choices", choices);
                }
                fields.append(field);
            }
            if (fields.isEmpty()) return OC_FORM_RESULT_OK;
            auto values = rejected ? QJsonObject{} : s.savedCredentials();
            if (!rejected && !s.groupTransitionValues.isEmpty()) {
                for (auto it=s.groupTransitionValues.begin();it!=s.groupTransitionValues.end();++it) values.insert(it.key(),it.value());
                s.groupTransitionValues = {};
            }
            bool allSaved = !values.isEmpty();
            for (const auto &entry : fields) {
                const auto field = entry.toObject();
                const auto value = values.value(field.value("name").toString());
                allSaved &= value.isString();
                if (field.value("type") == "select") {
                    bool available = false;
                    for (const auto &choice : field.value("choices").toArray()) available |= choice.toObject().value("value") == value;
                    allSaved &= available;
                }
            }
            if (!allSaved) {
                s.state("authenticating");
                const auto reply = s.ask({{"event", "authentication"}, {"fields", fields},
                    {"rejected", rejected}, {"message", safeLabel(form->message)}});
                if (reply.isEmpty() || reply.value("cancel").toBool() || s.stopping) {
                    if (reply.isEmpty() && !s.stopping) s.failureCode = "authentication_timeout";
                    return OC_FORM_RESULT_CANCELLED;
                }
                values = reply.value("values").toObject(); s.remember = reply.value("remember").toBool();
            } else s.log("credentials_reused");
            bool groupChanged = false;
            for (auto *opt = form->opts; opt; opt = opt->next) {
                if ((opt->flags & OC_FORM_OPT_IGNORE) || opt->type == OC_FORM_OPT_HIDDEN) continue;
                const auto name = safeLabel(opt->name);
                require(values.value(name).isString(), "invalid_authentication_reply");
                auto value = values.value(name).toString().toUtf8();
                require(value.size() <= 1024 && !value.contains('\0'), "invalid_authentication_reply");
                if (opt->type == OC_FORM_OPT_SELECT) {
                    auto *select = reinterpret_cast<oc_form_opt_select *>(opt); bool found = false;
                    for (int i = 0; i < select->nr_choices; ++i) if (value == select->choices[i]->name) {
                        found = true;
                        if (select == form->authgroup_opt && i != form->authgroup_selection) { form->authgroup_selection = i; groupChanged = true; }
                    }
                    require(found, "invalid_authentication_reply");
                    s.pendingChoices.insert(name, values.value(name));
                    if (select == form->authgroup_opt) s.pendingCredentials.insert(name, values.value(name));
                }
                require(s.api.openconnect_set_option_value(opt, value.constData()) == 0, "invalid_authentication_reply");
                value.fill('\0');
            }
            // Persist the auth group and stable login fields; OTP and other challenges stay ephemeral.
            for (const auto &key : {QStringLiteral("username"), QStringLiteral("user"), QStringLiteral("password"), QStringLiteral("passwd")})
                if (values.value(key).isString()) s.pendingCredentials.insert(key, values.value(key));
            if (groupChanged) {
                // Group selection refreshes the form; carry this submission once without resubmitting rejected credentials.
                s.groupTransitionValues=s.pendingCredentials;
                for (auto it=s.pendingChoices.begin();it!=s.pendingChoices.end();++it) s.groupTransitionValues.insert(it.key(),it.value());
            }
            return groupChanged ? OC_FORM_RESULT_NEWGROUP : OC_FORM_RESULT_OK;
        } catch (const std::exception &) { s.emit({{"event", "error"}, {"code", "unsupported_authentication"}}); return OC_FORM_RESULT_ERR; }
    }
    static void progress(void *opaque, int level, const char *format, ...) {
        auto &s = *static_cast<Session *>(opaque);
        const auto *code = protocolDiagnostic(format ? format : "");
        if (!code || s.stopping) return;
        const std::string_view value(code);
        if (value == "dtls_connected") s.reportTransport(true);
        else if (value == "dtls_fallback") s.reportTransport(false);
        const bool failed = retryableFailure(value) || value == "invalid_gateway_response";
        if (failed && (s.failureCode.empty() || retryableFailure(s.failureCode))) s.failureCode = code;
        if (value == "tls_connected" && retryableFailure(s.failureCode)) s.failureCode.clear();
        s.log(code, level == PRG_ERR ? "error" : "info");
    }
    static void statistics(void *opaque, const oc_stats *stats) {
        auto &s = *static_cast<Session *>(opaque);
        s.reportTransport(s.api.openconnect_get_dtls_cipher(s.vpn) != nullptr);
        s.emit({{"event", "statistics"}, {"received", double(stats->rx_bytes)}, {"sent", double(stats->tx_bytes)}});
    }
    Plan networkPlan() {
        const oc_ip_info *info = nullptr;
        require(api.openconnect_get_ip_info(vpn, &info, nullptr, nullptr) == 0 && info, "missing_ip_configuration");
        require(!info->addr6 && !info->split_excludes, "unsupported_network_plan");
        Plan plan; bool ok = false;
        plan.address = QHostAddress(QString::fromUtf8(info->addr ? info->addr : "")).toIPv4Address(&ok);
        require(ok, "unsupported_network_plan"); plan.prefix = 32;
        plan.gateway = QHostAddress(QString::fromUtf8(info->gateway_addr ? info->gateway_addr : "")).toIPv4Address(&ok);
        require(ok, "unsupported_network_plan"); plan.mtu = uint32_t(info->mtu);
        auto addRoute = [&](uint32_t network, uint32_t prefix) {
            require(prefix > 0 && prefix <= 32, "full_tunnel_not_supported");
            Route next{network & mask(prefix), prefix};
            for (uint32_t i = 0; i < plan.routeCount; ++i)
                if (plan.routes[i].network == next.network && plan.routes[i].prefix == next.prefix) return;
            require(plan.routeCount < MaxRoutes, "too_many_routes"); plan.routes[plan.routeCount++] = next;
        };
        require(info->split_includes != nullptr, "full_tunnel_not_supported");
        for (auto *route = info->split_includes; route; route = route->next) {
            const auto value = QString::fromUtf8(route->route); const auto parts = value.split('/');
            require(parts.size() == 2, "unsupported_network_plan");
            const auto address = QHostAddress(parts[0]).toIPv4Address(&ok); require(ok, "unsupported_network_plan");
            uint32_t prefix = parts[1].toUInt(&ok);
            if (!ok) {
                const auto netmask = QHostAddress(parts[1]).toIPv4Address(&ok); require(ok, "unsupported_network_plan");
                prefix = 0; while (prefix < 32 && (netmask & (uint32_t(1) << (31 - prefix)))) ++prefix;
                require(netmask == mask(prefix), "unsupported_network_plan");
            }
            addRoute(address, prefix);
        }
        serverRouteCount = plan.routeCount;
        for (const auto dns : info->dns) if (dns && *dns) {
            const auto address = QHostAddress(QString::fromUtf8(dns)).toIPv4Address(&ok); require(ok, "unsupported_network_plan");
            plan.dns[plan.dnsCount++] = address;
            bool routed = false; for (uint32_t i = 0; i < plan.routeCount; ++i) routed |= contains(plan.routes[i], address);
            if (!routed) addRoute(address, 32);
        }
        require(valid(plan), "unsupported_network_plan"); return plan;
    }
    void input() {
        QByteArray pending;
        while (!readerDone) {
            if (!stopping && helper.transportLost()) { emit({{"event","error"},{"code","helper_disconnected"}}); cancel(); }
            char buffer[4096]; const auto size = readInput(buffer, sizeof(buffer));
            if (size < 0) { cancel(); break; }
            if (!size) continue;
            pending.append(buffer, int(size));
            if (pending.size() > 65536) { pending.fill('\0'); cancel(); break; }
            int newline = 0;
            while ((newline = pending.indexOf('\n')) >= 0) {
                auto bytes = pending.left(newline); const auto message = QJsonDocument::fromJson(bytes).object();
                bytes.fill('\0'); pending.remove(0, newline + 1);
                if (message.value("sessionId").toString() != id) { cancel(); return; }
                const auto action = message.value("action").toString();
                if (action == "stop") cancel();
                else if (action == "stats") sendCommand(OC_CMD_STATS);
                else if (action == "reply") {
                    std::lock_guard guard(replyMutex);
                    if (!challenge.isEmpty() && response.isEmpty() && message.value("challengeId").toString() == challenge) {
                        response = message; changed.notify_all();
                    }
                } else { cancel(); return; }
            }
        }
        pending.fill('\0');
    }
    void createVpn(const Profile &profile, const QString &ca) {
        vpn = api.openconnect_vpninfo_new("GeekOCTunnel/0.1", certificate, nullptr, authenticate, progress, this);
        require(vpn != nullptr, "session_create_failed");
        require(api.openconnect_set_protocol(vpn, "anyconnect") == 0, "protocol_failed");
        udpEnabled = profile.enableUdp; transport.clear();
        if (!udpEnabled) require(api.openconnect_disable_dtls(vpn) == 0, "protocol_failed");
#ifdef _WIN32
        api.openconnect_set_reported_os(vpn, "win");
#else
        api.openconnect_set_reported_os(vpn, "linux");
#endif
        api.openconnect_set_loglevel(vpn, PRG_INFO);
        api.openconnect_disable_ipv6(vpn); api.openconnect_set_reqmtu(vpn, 1400);
        api.openconnect_set_stats_handler(vpn, statistics);
        if (!ca.isEmpty()) require(api.openconnect_set_cafile(vpn, ca.toUtf8().constData()) == 0, "test_ca_failed");
        require(api.openconnect_parse_url(vpn, ("https://" + profile.server).toUtf8().constData()) == 0, "invalid_server");
        const auto socket = api.openconnect_setup_cmd_pipe(vpn); require(socket != InvalidCommand, "cancel_setup_failed");
        std::lock_guard guard(commandMutex); command = socket;
    }
    int run(const Profile &profile, const QString &ca) {
        credentialTarget = "GeekOCTunnel/" + profile.id + '/' + QString::fromLatin1(QCryptographicHash::hash(profile.server.toUtf8(), QCryptographicHash::Sha256).toHex());
        std::thread reader([&] { input(); });
        bool clean = true; unsigned retries = 0; std::string outcome;
        while (!stopping) {
            failureCode.clear(); outcome.clear(); int platformError = 0;
            try {
                emit({{"event", "state"}, {"state", "preparing"}, {"profileName", profile.name}}); createVpn(profile, ca);
                require(!stopping, "cancelled");
                const auto authentication = api.openconnect_obtain_cookie(vpn);
                if (authentication != 0) {
                    if (failureCode.empty()) failureCode = stopping || authentication == OC_FORM_RESULT_CANCELLED ? "cancelled"
                        : authentication == -EPERM || authentication == -EACCES ? "authentication_rejected" : "authentication_failed";
                    throw std::runtime_error(failureCode);
                }
                failureCode.clear(); log("authenticated", "success");
                // Keep only stable login fields in this process for transient reconnects; disk storage remains opt-in.
                for (auto it = pendingCredentials.begin(); it != pendingCredentials.end(); ++it) cachedCredentials.insert(it.key(), it.value());
                for (auto it = pendingChoices.begin(); it != pendingChoices.end(); ++it) cachedCredentials.insert(it.key(), it.value());
                saveCredentials(); state("connecting");
                if (api.openconnect_make_cstp_connection(vpn) != 0)
                    throw std::runtime_error(failureCode.empty() ? "tunnel_negotiation_failed" : failureCode);
                failureCode.clear();
                const auto plan = networkPlan();
                auto details=networkDetails(plan, serverRouteCount);
                details.insert("certificateVerification",ignoreCertificateErrors ? "disabled" : "strict");
                emit({{"event", "network"}, {"details", details}});
                reportTransport(false);
                emit({{"event", "permission"}});
                Handle data(helper.start(id.toStdString(), plan, cancelled.get()));
                require(api.openconnect_setup_tun_fd(vpn, tunnelArgument(data.get())) == 0, "tunnel_transport_failed");
#ifdef _WIN32
                data.release(); // Windows OpenConnect owns the HANDLE; POSIX injected descriptors remain caller-owned.
#endif
                retries = 0;
                emit({{"event", "state"}, {"state", "connected"}, {"profileId", profile.id}, {"profileName", profile.name},
                      {"address", QHostAddress(plan.address).toString()}, {"mtu", int(plan.mtu)}, {"routes", int(plan.routeCount)}});
                if (udpEnabled) {
                    log("dtls_negotiating");
                    if (api.openconnect_setup_dtls(vpn, 30) != 0) log("dtls_fallback", "warning");
                } else log("udp_disabled");
                api.openconnect_mainloop(vpn, 0, 1);
                if (!stopping) throw std::runtime_error(failureCode.empty() ? "connection_lost" : failureCode);
            } catch (const PlatformError &error) { outcome = error.what(); platformError = int(error.code); }
              catch (const std::exception &error) { outcome = error.what(); }
            if (!stopping && !outcome.empty() && outcome != "cancelled")
                emit({{"event", "error"}, {"code", QString::fromStdString(outcome)}, {"platformError", platformError}, {"retryable", retryableFailure(outcome)}});
            state("disconnecting"); clean = helper.stop(); freeVpn();
            if (!clean) { emit({{"event", "error"}, {"code", "network_cleanup_unconfirmed"}, {"retryable", false}}); break; }
            if (stopping || !autoReconnect || !retryableFailure(outcome)) break;
            const auto delay = reconnectDelay(retries++);
            emit({{"event", "state"}, {"state", "reconnecting"}, {"attempt", int(retries)}, {"retryInSeconds", delay}});
            std::unique_lock lock(replyMutex);
            changed.wait_for(lock, std::chrono::seconds(delay), [&] { return stopping.load(); });
            if (!stopping) ++generation;
        }
        readerDone = true; changed.notify_all(); reader.join();
        emit({{"event", "state"}, {"state", clean ? "idle" : "failed"}, {"cleanupConfirmed", clean}});
        return clean && (stopping || outcome.empty() || outcome == "cancelled") ? 0 : 1;
    }
};

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv); app.setApplicationName("GeekOCTunnel-Desktop-Preview"); app.setOrganizationName("GeekOCTunnel");
    QCommandLineParser parser; parser.addHelpOption();
    parser.addOption({"test-server", "Use an explicit loopback test server instead of persisted profiles.", "server"});
    parser.addOption({"ca-file", "Additional CA for a loopback integration test only.", "path"}); parser.process(app);
    QString sessionId;
    try {
        QByteArray bytes; char c;
        while (bytes.size() <= 4096 && readInput(&c, 1, true) > 0 && c != '\n') bytes.append(c);
        require(bytes.size() <= 4096, "invalid_start"); const auto start = QJsonDocument::fromJson(bytes).object();
        const auto id = start.value("sessionId").toString(); sessionId = id;
        require(QUuid(id).toString(QUuid::WithoutBraces) == id && !QUuid(id).isNull(), "invalid_session");
        require(start.value("schemaVersion").toInt() == 1, "unsupported_schema");
        Profile profile;
        if (parser.isSet("test-server")) {
            const auto address = normalizeServer(parser.value("test-server"));
            require(address.startsWith("127.0.0.1:") || address.startsWith("localhost:"), "invalid_test_server");
            profile = {"verification-windows", "Local verification", address};
        } else {
            require(!parser.isSet("ca-file"), "invalid_test_server");
            const auto directory = QStandardPaths::writableLocation(QStandardPaths::AppConfigLocation);
            QLockFile lock(directory + "/profiles.lock"); require(lock.tryLock(0), "config_locked");
            for (const auto &candidate : loadProfiles(directory + "/profiles.json"))
                if (candidate.id == start.value("profileId").toString()) profile = candidate;
            require(!profile.id.isEmpty(), "profile_not_found");
        }
        for (const auto key : {"autoReconnect", "ignoreCertificateErrors", "testEnableUdp"})
            require(!start.contains(key) || start.value(key).isBool(), "invalid_start");
        if (parser.isSet("test-server")) profile.enableUdp = start.value("testEnableUdp").toBool(false);
        Session session; session.id = id;
        session.autoReconnect = start.value("autoReconnect").toBool(true);
        session.ignoreCertificateErrors = start.value("ignoreCertificateErrors").toBool(false);
        return session.run(profile, parser.value("ca-file"));
    } catch (const std::exception &) {
        for (const auto &event : {QJsonObject{{"event","error"},{"code","session_start_failed"},{"sequence",1}},
                                 QJsonObject{{"event","state"},{"state","idle"},{"cleanupConfirmed",true},{"sequence",2}}}) {
            auto value = event; value.insert("schemaVersion",1); value.insert("generation",1); value.insert("sessionId",sessionId);
            const auto data = QJsonDocument(value).toJson(QJsonDocument::Compact) + '\n';
            writeOutput(data);
        }
        return 1;
    }
}
