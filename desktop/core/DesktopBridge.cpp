#include "DesktopBridge.h"
#include "ProfileStore.h"
#include <QCryptographicHash>
#include <QDir>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLockFile>
#include <QRegularExpression>
#include <QSet>
#include <stdexcept>

namespace geekoc {
static void require(bool condition, const char *code) {
    if (!condition) throw std::runtime_error(code);
}
static QString revision(const QList<Profile> &profiles) {
    return QString::fromLatin1(QCryptographicHash::hash(encodeProfiles(profiles), QCryptographicHash::Sha256).toHex());
}
QByteArray handleDesktopRequest(const QString &directory, const QByteArray &bytes) {
    QJsonObject reply{{"schemaVersion", 1}, {"ok", false}};
    try {
        require(bytes.size() <= MaxRequestBytes, "request_too_large");
        QJsonParseError error;
        const auto parsed = QJsonDocument::fromJson(bytes, &error);
        require(error.error == QJsonParseError::NoError && parsed.isObject(), "invalid_request");
        const auto request = parsed.object();
        const QSet<QString> allowed{"schemaVersion", "requestId", "operation", "document", "revision", "profileId"};
        for (auto it = request.begin(); it != request.end(); ++it)
            require(allowed.contains(it.key()), "unknown_field");
        require(request.value("schemaVersion").isDouble() && request.value("schemaVersion").toDouble() == 1,
                "unsupported_schema");
        const auto id = request.value("requestId").toString();
        require(QRegularExpression("^[A-Za-z0-9_-]{1,64}$").match(id).hasMatch(), "invalid_request");
        reply.insert("requestId", id);
        const auto operation = request.value("operation").toString();
        require(operation == "snapshot" || operation == "replace" || operation == "connect", "unsupported_operation");
        require(!directory.isEmpty() && QDir().mkpath(directory), "config_write_failed");
        QLockFile lock(QDir(directory).filePath("profiles.lock"));
        require(lock.tryLock(0), "config_locked");
        const auto path = QDir(directory).filePath("profiles.json");
        auto profiles = loadProfiles(path);
        if (operation == "replace") {
            require(request.value("revision").toString() == revision(profiles), "config_conflict");
            require(request.value("document").isObject(), "invalid_profiles");
            const auto next = decodeProfiles(QJsonDocument(request.value("document").toObject()).toJson(QJsonDocument::Compact));
            saveProfiles(path, next);
            profiles = next;
        } else if (operation == "connect") {
            const auto profileId = request.value("profileId").toString();
            bool found = false;
            for (const auto &profile : profiles) found |= profile.id == profileId;
            require(found, "profile_not_found");
            // Legacy configuration-only clients cannot start a privileged session through this bridge.
            throw std::runtime_error("vpn_backend_unavailable");
        }
        reply.insert("document", QJsonDocument::fromJson(encodeProfiles(profiles)).object());
        reply.insert("revision", revision(profiles));
        reply.insert("capabilities", QJsonObject{{"vpn", false}, {"reason", "vpn_backend_unavailable"}});
        reply.insert("ok", true);
    } catch (const std::exception &error) {
        reply.insert("error", error.what());
    }
    return QJsonDocument(reply).toJson(QJsonDocument::Compact);
}
}
