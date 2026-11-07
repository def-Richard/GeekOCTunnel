#pragma once
#include <QList>
#include <QString>

namespace geekoc {
struct Profile {
    QString id;
    QString name;
    QString server;
    bool enableUdp = false;
};

// Throws a stable code, never includes the rejected value in diagnostics.
QString normalizeServer(const QString &input);
QList<Profile> decodeProfiles(const QByteArray &json);
QByteArray encodeProfiles(const QList<Profile> &profiles);
QList<Profile> loadProfiles(const QString &path);
void saveProfiles(const QString &path, const QList<Profile> &profiles);
constexpr qsizetype MaxConfigBytes = 256 * 1024;
constexpr qsizetype MaxProfiles = 128;
}
