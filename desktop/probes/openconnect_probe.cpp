// P0 only: local TLS cancellation and device API feasibility; no VPN login or host routes.
#ifdef _WIN32
#include <winsock2.h>
#include <windows.h>
#endif
#ifdef _MSC_VER
#define __attribute__(x)
#endif
#include <openconnect.h>
#ifdef _MSC_VER
#undef __attribute__
#endif
#include <QCoreApplication>
#include <QCommandLineParser>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLibrary>
#include <QTcpServer>
#include <QTcpSocket>
#include <QSysInfo>
#include <QElapsedTimer>
#include <cstdio>
#include <future>
#include <memory>
#include <stdexcept>
#include <thread>
#ifndef _WIN32
#include <sys/socket.h>
#include <unistd.h>
#include <fcntl.h>
#endif
#ifdef __linux__
#include <fcntl.h>
#include <linux/if_tun.h>
#include <net/if.h>
#include <sys/ioctl.h>
#endif

static void check(bool ok, const char *code) {
    if (!ok) throw std::runtime_error(code);
}
template<class T> static T symbol(QLibrary &library, const char *name) {
    auto result = reinterpret_cast<T>(library.resolve(name));
    check(result != nullptr, "openconnect_api_missing");
    return result;
}
#define OC(name) const auto name = symbol<decltype(&::name)>(library, #name)
struct Callbacks {
    std::thread::id worker;
    bool onWorker = true;
    int count = 0;
};
static void progress(void *data, int, const char *, ...) {
    auto &callbacks = *static_cast<Callbacks *>(data);
    callbacks.onWorker &= std::this_thread::get_id() == callbacks.worker;
    ++callbacks.count;
    // OpenConnect logs may contain credentials or topology. Never print the raw format/arguments.
}
static int rejectCertificate(void *, const char *) { return -1; }
static int cancelAuthentication(void *, oc_auth_form *) { return OC_FORM_RESULT_CANCELLED; }

static void output(const QJsonObject &report) {
    const auto json = QJsonDocument(report).toJson(QJsonDocument::Compact) + '\n';
    std::fwrite(json.constData(), 1, size_t(json.size()), stdout);
    std::fflush(stdout);
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    QCommandLineParser parser;
    parser.setApplicationDescription("Offline OpenConnect feasibility probe; no VPN credentials accepted.");
    parser.addHelpOption();
    parser.addOption({"library", "Absolute path to the OpenConnect shared library.", "path"});
    parser.addOption({"isolated-tun", "Linux only: create a temporary unconfigured TUN in a test network namespace."});
    parser.process(app);
    QJsonObject report{{"schemaVersion", 1}, {"platform", QSysInfo::kernelType()},
                       {"architecture", QSysInfo::currentCpuArchitecture()}, {"qtVersion", qVersion()}};
    try {
        auto path = parser.value("library");
        if (!path.isEmpty()) check(QFileInfo(path).isAbsolute() && QFileInfo(path).isFile(), "invalid_library_path");
#ifdef _WIN32
        check(!path.isEmpty(), "library_path_required");
        check(SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_DEFAULT_DIRS), "dll_search_setup_failed");
        const auto dir = QFileInfo(path).absolutePath().toStdWString();
        const auto directoryCookie = AddDllDirectory(dir.c_str());
        check(directoryCookie != nullptr, "dll_search_setup_failed");
        WSADATA winsock{};
        check(WSAStartup(MAKEWORD(2, 2), &winsock) == 0, "winsock_failed");
        HANDLE token = nullptr;
        check(OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token), "token_query_failed");
        TOKEN_ELEVATION elevation{};
        DWORD size = 0;
        const bool tokenRead = GetTokenInformation(token, TokenElevation, &elevation, sizeof(elevation), &size);
        CloseHandle(token);
        check(tokenRead, "token_query_failed");
        report.insert("elevated", bool(elevation.TokenIsElevated));
#else
        if (path.isEmpty()) path = "libopenconnect.so.5";
        report.insert("elevated", geteuid() == 0);
#endif
        QLibrary library(path);
        check(library.load(), "openconnect_load_failed");
        OC(openconnect_get_version);
        OC(openconnect_init_ssl);
        OC(openconnect_vpninfo_new);
        OC(openconnect_vpninfo_free);
        OC(openconnect_setup_cmd_pipe);
        OC(openconnect_parse_url);
        OC(openconnect_set_protocol);
        OC(openconnect_set_system_trust);
        OC(openconnect_obtain_cookie);
        OC(openconnect_setup_tun_fd);
        const auto version = QString::fromUtf8(openconnect_get_version());
        report.insert("openconnectVersion", version);
        report.insert("androidPinnedVersion", "9.12");
        report.insert("apiHeaderVersion", QString("%1.%2").arg(OPENCONNECT_API_VERSION_MAJOR).arg(OPENCONNECT_API_VERSION_MINOR));
        check(openconnect_init_ssl() == 0, "tls_init_failed");
        Callbacks callbacks;
        using Session = std::unique_ptr<openconnect_info, decltype(openconnect_vpninfo_free)>;
        auto create = [&]() {
            auto *info = openconnect_vpninfo_new("GeekOCTunnel-P0", rejectCertificate, nullptr,
                                                cancelAuthentication, progress, &callbacks);
            check(info != nullptr, "session_create_failed");
            return Session(info, openconnect_vpninfo_free);
        };
        {
            auto session = create();
#ifdef _WIN32
            HANDLE reader = nullptr, writer = nullptr;
            check(CreatePipe(&reader, &writer, nullptr, 0), "pipe_create_failed");
            const auto rc = openconnect_setup_tun_fd(session.get(), reinterpret_cast<intptr_t>(reader));
            CloseHandle(writer);
            if (rc != 0) CloseHandle(reader); // Success would transfer ownership to OpenConnect.
            report.insert("genericPipeHandleRejected", rc != 0);
            check(rc != 0, "unexpected_windows_pipe_support");
            report.insert("devicePath", "TAP HANDLE or internal Wintun state; helper integration unresolved");
#else
            int pair[2];
            check(socketpair(AF_UNIX, SOCK_DGRAM, 0, pair) == 0, "socketpair_failed");
            const auto rc = openconnect_setup_tun_fd(session.get(), pair[0]);
            close(pair[1]);
            report.insert("datagramFdAccepted", rc == 0);
            session.reset();
            // vpninfo_free does not close a TUN that never entered mainloop.
            report.insert("callerClosesFdBeforeMainloop", fcntl(pair[0], F_GETFD) >= 0);
            close(pair[0]);
            check(rc == 0, "datagram_fd_rejected");
#endif
        }
        callbacks = {};
        auto session = create();
        const auto command = openconnect_setup_cmd_pipe(session.get());
#ifdef _WIN32
        check(command != INVALID_SOCKET, "command_pipe_failed");
#else
        check(command >= 0, "command_pipe_failed");
#endif
        check(openconnect_set_protocol(session.get(), "anyconnect") == 0, "protocol_failed");
        openconnect_set_system_trust(session.get(), 1);
        QTcpServer listener;
        check(listener.listen(QHostAddress::LocalHost, 0), "loopback_listen_failed");
        const auto url = QString("https://127.0.0.1:%1").arg(listener.serverPort()).toUtf8();
        check(openconnect_parse_url(session.get(), url.constData()) == 0, "url_setup_failed");
        auto task = std::async(std::launch::async, [&] {
            callbacks.worker = std::this_thread::get_id();
            return openconnect_obtain_cookie(session.get());
        });
        const bool accepted = listener.waitForNewConnection(4000);
        std::unique_ptr<QTcpSocket> peer(accepted ? listener.nextPendingConnection() : nullptr);
        const bool receivedHello = peer && (peer->bytesAvailable() > 0 || peer->waitForReadyRead(4000));
        QElapsedTimer elapsed;
        elapsed.start();
        const char cancel = OC_CMD_CANCEL;
#ifdef _WIN32
        const auto sent = send(command, &cancel, 1, 0);
#else
        const auto sent = write(command, &cancel, 1);
#endif
        const bool finished = task.wait_for(std::chrono::seconds(2)) == std::future_status::ready;
        report.insert("cancelMilliseconds", double(elapsed.elapsed()));
        report.insert("tlsClientHelloReceived", receivedHello);
        report.insert("cancelByteWritten", sent == 1);
        if (!finished) {
            // Do not destruct a session while its callback thread is using it.
            report.insert("error", "cancel_timeout");
            report.insert("ok", false);
            output(report);
            std::_Exit(1);
        }
        const auto rc = task.get();
        report.insert("authenticationResult", rc);
        report.insert("cancelDuringTlsHandshake", receivedHello && sent == 1 && rc == OC_FORM_RESULT_CANCELLED);
        report.insert("callbacksOnWorkerThread", callbacks.count > 0 && callbacks.onWorker);
        check(receivedHello && sent == 1 && rc == OC_FORM_RESULT_CANCELLED, "cancel_probe_failed");
        check(callbacks.count > 0 && callbacks.onWorker, "callback_thread_mismatch");
        session.reset();
#ifdef __linux__
        if (parser.isSet("isolated-tun")) {
            // shortcut: P0 tests device ownership only; routes/DNS require the P2 helper transaction.
            const int fd = open("/dev/net/tun", O_RDWR | O_CLOEXEC);
            check(fd >= 0, "tun_open_failed");
            ifreq request{};
            request.ifr_flags = IFF_TUN | IFF_NO_PI;
            std::snprintf(request.ifr_name, IFNAMSIZ, "gocp0%%d");
            const int created = ioctl(fd, TUNSETIFF, &request);
            if (created < 0) { close(fd); throw std::runtime_error("tun_create_denied"); }
            const bool present = if_nametoindex(request.ifr_name) != 0;
            auto tunSession = create();
            const int attached = openconnect_setup_tun_fd(tunSession.get(), fd);
            tunSession.reset();
            const bool needsClose = fcntl(fd, F_GETFD) >= 0;
            if (needsClose) close(fd);
            const bool removed = if_nametoindex(request.ifr_name) == 0;
            report.insert("temporaryTunCreated", present);
            report.insert("temporaryTunAttached", attached == 0);
            report.insert("temporaryTunRequiresCallerClose", needsClose);
            report.insert("temporaryTunRemovedAfterClose", removed);
            check(present && attached == 0 && removed, "tun_lifecycle_failed");
        }
#else
        check(!parser.isSet("isolated-tun"), "tun_probe_linux_only");
#endif
        report.insert("ok", true);
        output(report);
        return 0;
    } catch (const std::exception &error) {
        report.insert("ok", false);
        report.insert("error", error.what());
        output(report);
        return 1;
    }
}
