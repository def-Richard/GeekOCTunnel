#include "ProfileStore.h"
#include <QFile>
#include <QHostAddress>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>
#include <QSaveFile>
#include <QSet>
#include <QUrl>
#include <stdexcept>

namespace geekoc {
static void require(bool ok, const char *code) {
    if (!ok) throw std::runtime_error(code);
}

QString normalizeServer(const QString &input) {
    auto value = input.trimmed();
    require(!value.isEmpty() && value.size() <= 2048, "invalid_server");
    static const QRegularExpression scheme("^[A-Za-z][A-Za-z0-9+.-]*://");
    if (!scheme.match(value).hasMatch()) value.prepend("https://");
    const QUrl url(value, QUrl::StrictMode);
    require(url.isValid() && url.scheme().compare("https", Qt::CaseInsensitive) == 0,
            "invalid_server");
    // Check raw authority too: QUrl normalizes legacy numeric hosts and empty userinfo.
    const auto authority = value.mid(value.indexOf("://") + 3).section('/', 0, 0);
    require(!authority.contains('@') && !authority.contains('%') && !authority.endsWith(':'),
            "invalid_server");
    require(!url.hasQuery() && !url.hasFragment() && (url.path().isEmpty() || url.path() == "/"),
            "invalid_server");
    QString host;
    if (authority.startsWith('[')) {
        const auto end = authority.indexOf(']');
        require(end > 1, "invalid_server");
        host = authority.mid(1, end - 1).toLower();
        const QHostAddress address(host);
        require(address.protocol() == QAbstractSocket::IPv6Protocol, "invalid_server");
        host = '[' + host + ']';
    } else {
        host = authority.section(':', 0, 0).toLower();
        if (host.endsWith('.')) host.chop(1);
        static const QRegularExpression label("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");
        require(!host.isEmpty() && host.size() <= 253, "invalid_server");
        for (const auto &part : host.split('.'))
            require(label.match(part).hasMatch(), "invalid_server");
        static const QRegularExpression numeric("^[0-9.]+$");
        if (numeric.match(host).hasMatch()) {
            const auto octets = host.split('.');
            require(octets.size() == 4, "invalid_server");
            for (const auto &part : octets) {
                bool ok = false;
                const auto number = part.toUInt(&ok);
                require(ok && number <= 255 && (part == "0" || !part.startsWith('0')), "invalid_server");
            }
        }
    }
    const auto port = url.port(-1);
    require(port == -1 || (port > 0 && port <= 65535), "invalid_server");
    return host + ((port == -1 || port == 443) ? QString() : ':' + QString::number(port));
}

static void keys(const QJsonObject &object, const QSet<QString> &allowed) {
    for (auto it = object.begin(); it != object.end(); ++it)
        require(allowed.contains(it.key()), "unknown_field");
}

QList<Profile> decodeProfiles(const QByteArray &json) {
    require(json.size() <= MaxConfigBytes, "config_too_large");
    QJsonParseError error;
    const auto document = QJsonDocument::fromJson(json, &error);
    require(error.error == QJsonParseError::NoError && document.isObject(), "invalid_json");
    const auto root = document.object();
    keys(root, {"schemaVersion", "profiles"});
    require(root.value("schemaVersion").isDouble() && root.value("schemaVersion").toDouble() == 1,
            "unsupported_schema");
    require(root.value("profiles").isArray(), "invalid_profiles");
    const auto array = root.value("profiles").toArray();
    require(array.size() <= MaxProfiles, "too_many_profiles");
    QList<Profile> result;
    QSet<QString> ids;
    static const QRegularExpression idPattern("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");
    for (const auto &value : array) {
        require(value.isObject(), "invalid_profile");
        const auto object = value.toObject();
        keys(object, {"id", "name", "server", "ignoreCertificateErrors", "enableUdp"});
        require(object.value("id").isString() && object.value("name").isString()
                && object.value("server").isString(), "invalid_profile");
        Profile profile{object.value("id").toString(), object.value("name").toString().trimmed(),
                        normalizeServer(object.value("server").toString())};
        require(!object.contains("enableUdp") || object.value("enableUdp").isBool(), "invalid_profile");
        profile.enableUdp = object.value("enableUdp").toBool(false);
        require(idPattern.match(profile.id).hasMatch() && !ids.contains(profile.id), "invalid_profile_id");
        require(!profile.name.isEmpty() && profile.name.size() <= 128, "invalid_profile_name");
        for (const auto c : profile.name)
            require(!c.isNonCharacter() && (c.isPrint() || c == ' '), "invalid_profile_name");
        // Imported trust decisions cannot disable certificate verification on another device.
        if (object.contains("ignoreCertificateErrors"))
            require(object.value("ignoreCertificateErrors").isBool()
                    && !object.value("ignoreCertificateErrors").toBool(), "certificate_review_required");
        ids.insert(profile.id);
        result.append(profile);
    }
    return result;
}

QByteArray encodeProfiles(const QList<Profile> &profiles) {
    QJsonArray array;
    for (const auto &profile : profiles)
        array.append(QJsonObject{{"id", profile.id}, {"name", profile.name}, {"server", profile.server},
                                 {"ignoreCertificateErrors", false}, {"enableUdp", profile.enableUdp}});
    const auto json = QJsonDocument(QJsonObject{{"schemaVersion", 1}, {"profiles", array}}).toJson();
    decodeProfiles(json);
    return json;
}

QList<Profile> loadProfiles(const QString &path) {
    QFile file(path);
    if (!file.exists()) return {};
    require(file.open(QIODevice::ReadOnly), "config_read_failed");
    require(file.size() <= MaxConfigBytes, "config_too_large");
    return decodeProfiles(file.read(MaxConfigBytes + 1));
}

void saveProfiles(const QString &path, const QList<Profile> &profiles) {
    const auto data = encodeProfiles(profiles);
    QSaveFile file(path);
    file.setDirectWriteFallback(false);
    require(file.open(QIODevice::WriteOnly), "config_write_failed");
    require(file.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner), "config_permissions_failed");
    require(file.write(data) == data.size() && file.commit(), "config_write_failed");
}
}
