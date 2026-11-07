#pragma once
#include <QByteArray>
#include <QString>

namespace geekoc {
constexpr qsizetype MaxRequestBytes = 300 * 1024;
// The directory is selected by the native host, never by the web request.
QByteArray handleDesktopRequest(const QString &directory, const QByteArray &request);
}
