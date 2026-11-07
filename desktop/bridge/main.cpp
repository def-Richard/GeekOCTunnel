#include "core/DesktopBridge.h"
#include <QCoreApplication>
#include <QCommandLineParser>
#include <QFile>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <cstdio>
#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    app.setApplicationName("GeekOCTunnel-Desktop-Preview");
    app.setOrganizationName("GeekOCTunnel");
    QCommandLineParser parser;
    parser.addHelpOption();
    parser.addOption({"smoke-test", "Use disposable configuration for a process-level transport test."});
    parser.process(app);
    QTemporaryDir temporary;
#ifdef _WIN32
    _setmode(_fileno(stdin), _O_BINARY);
    _setmode(_fileno(stdout), _O_BINARY);
#endif
    QFile input;
    if (!input.open(stdin, QIODevice::ReadOnly)) return 1;
    const auto request = input.read(geekoc::MaxRequestBytes + 1);
    const auto reply = geekoc::handleDesktopRequest(
        parser.isSet("smoke-test") ? temporary.path()
            : QStandardPaths::writableLocation(QStandardPaths::AppConfigLocation), request) + '\n';
    return std::fwrite(reply.constData(), 1, size_t(reply.size()), stdout) == size_t(reply.size()) ? 0 : 1;
}
