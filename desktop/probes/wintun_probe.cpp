// Explicit P0 experiment. Creates only its own temporary adapter; never changes routes/DNS or deletes a driver.
#include <winsock2.h>
#include <windows.h>
#include <ws2ipdef.h>
#include <iphlpapi.h>
#include <netioapi.h>
#include "platform/windows/InterfaceCleanup.h"
#include <wintun.h>
#include <QCommandLineParser>
#include <QCoreApplication>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLibrary>
#include <QSaveFile>
#include <QUuid>
#include <cstdio>
#include <cstring>
#include <memory>
#include <stdexcept>

static void check(bool ok, const char *code) {
    if (!ok) throw std::runtime_error(code);
}
template<class T> static T *resolve(QLibrary &library, const char *name) {
    auto result = reinterpret_cast<T *>(library.resolve(name));
    check(result != nullptr, "wintun_api_missing");
    return result;
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    QCommandLineParser parser;
    parser.addHelpOption();
    parser.addOption({"library", "Absolute path to a trusted, signed wintun.dll.", "path"});
    parser.addOption({"create", "Create/test/remove a temporary adapter. Requires administrator rights."});
    parser.addOption({"output", "Write a JSON test result to this file.", "path"});
    parser.process(app);
    QJsonObject report{{"schemaVersion", 1}, {"ok", false}};
    try {
        const auto path = parser.value("library");
        check(QFileInfo(path).isAbsolute() && QFileInfo(path).isFile(), "invalid_library_path");
        check(SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_DEFAULT_DIRS), "dll_search_setup_failed");
        const auto directory = QFileInfo(path).absolutePath().toStdWString();
        check(AddDllDirectory(directory.c_str()) != nullptr, "dll_search_setup_failed");
        QLibrary library(path);
        check(library.load(), "wintun_load_failed");
        const auto create = resolve<WINTUN_CREATE_ADAPTER_FUNC>(library, "WintunCreateAdapter");
        const auto close = resolve<WINTUN_CLOSE_ADAPTER_FUNC>(library, "WintunCloseAdapter");
        const auto start = resolve<WINTUN_START_SESSION_FUNC>(library, "WintunStartSession");
        const auto end = resolve<WINTUN_END_SESSION_FUNC>(library, "WintunEndSession");
        const auto version = resolve<WINTUN_GET_RUNNING_DRIVER_VERSION_FUNC>(library, "WintunGetRunningDriverVersion");
        const auto luid = resolve<WINTUN_GET_ADAPTER_LUID_FUNC>(library, "WintunGetAdapterLUID");
        const auto event = resolve<WINTUN_GET_READ_WAIT_EVENT_FUNC>(library, "WintunGetReadWaitEvent");
        const auto receive = resolve<WINTUN_RECEIVE_PACKET_FUNC>(library, "WintunReceivePacket");
        const auto release = resolve<WINTUN_RELEASE_RECEIVE_PACKET_FUNC>(library, "WintunReleaseReceivePacket");
        report.insert("runningDriverVersion", double(version()));
        if (parser.isSet("create")) {
            // The random name cannot select or replace an existing VPN adapter.
            const auto name = ("GeekOC-P0-" + QUuid::createUuid().toString(QUuid::WithoutBraces)).toStdWString();
            auto adapter = std::unique_ptr<_WINTUN_ADAPTER, decltype(close)>(create(name.c_str(), L"GeekOC P0", nullptr), close);
            if (!adapter) {
                report.insert("windowsError", int(GetLastError()));
                throw std::runtime_error("wintun_create_failed");
            }
            report.insert("temporaryAdapterCreated", true);
            MIB_IF_ROW2 row{};
            luid(adapter.get(), &row.InterfaceLuid);
            check(GetIfEntry2(&row) == NO_ERROR, "adapter_not_visible");
            auto session = std::unique_ptr<_TUN_SESSION, decltype(end)>(start(adapter.get(), WINTUN_MIN_RING_CAPACITY), end);
            check(session != nullptr, "wintun_session_failed");
            report.insert("ringSessionStarted", true);
            const auto wait = WaitForSingleObject(event(session.get()), 0);
            check(wait == WAIT_OBJECT_0 || wait == WAIT_TIMEOUT, "wintun_wait_failed");
            DWORD size = 0;
            auto *packet = receive(session.get(), &size);
            if (packet) release(session.get(), packet);
            else check(GetLastError() == ERROR_NO_MORE_ITEMS, "wintun_receive_failed");
            report.insert("nonblockingReceiveChecked", true);
            session.reset();
            adapter.reset();
            const auto cleanup = geekoc::vpn::waitForInterfaceRemoval(row.InterfaceLuid);
            report.insert("cleanupQueryError", int(cleanup));
            report.insert("temporaryAdapterRemoved", cleanup == NO_ERROR);
            check(report.value("temporaryAdapterRemoved").toBool(), "adapter_cleanup_not_confirmed");
        }
        report.insert("ok", true);
    } catch (const std::exception &error) {
        report.insert("error", error.what());
    }
    const auto json = QJsonDocument(report).toJson(QJsonDocument::Compact) + '\n';
    if (parser.isSet("output")) {
        QSaveFile file(parser.value("output"));
        file.setDirectWriteFallback(false);
        if (!file.open(QIODevice::WriteOnly) || file.write(json) != json.size() || !file.commit()) return 2;
    } else {
        std::fwrite(json.constData(), 1, size_t(json.size()), stdout);
    }
    return report.value("ok").toBool() ? 0 : 1;
}
