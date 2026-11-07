#include "core/DesktopBridge.h"
#include "core/ProfileStore.h"
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLockFile>
#include <QTemporaryDir>
#include <QtTest>

class BridgeTests : public QObject {
    Q_OBJECT
    QJsonObject call(const QString &dir, const QString &operation, QJsonObject extra = {}) {
        extra.insert("schemaVersion", 1);
        extra.insert("requestId", "test-1");
        extra.insert("operation", operation);
        return QJsonDocument::fromJson(geekoc::handleDesktopRequest(dir, QJsonDocument(extra).toJson())).object();
    }
private slots:
    void persistsAndRejectsLostUpdate() {
        QTemporaryDir dir;
        const auto original = call(dir.path(), "snapshot");
        QVERIFY(original.value("ok").toBool());
        const auto document = QJsonDocument::fromJson(geekoc::encodeProfiles({{"one", "Example", "vpn.example.com"}})).object();
        const QJsonObject update{{"document", document}, {"revision", original.value("revision")}};
        QVERIFY(call(dir.path(), "replace", update).value("ok").toBool());
        QCOMPARE(call(dir.path(), "replace", update).value("error").toString(), QString("config_conflict"));
        QCOMPARE(geekoc::loadProfiles(dir.filePath("profiles.json")).size(), 1);
        QCOMPARE(call(dir.path(), "connect", {{"profileId", "one"}}).value("error").toString(), QString("vpn_backend_unavailable"));
        QCOMPARE(call(dir.path(), "connect", {{"profileId", "absent"}}).value("error").toString(), QString("profile_not_found"));
    }
    void rejectsSecretsPathsAndUnsupportedCommands() {
        QTemporaryDir dir;
        QCOMPARE(call(dir.path(), "exec").value("error").toString(), QString("unsupported_operation"));
        QCOMPARE(call(dir.path(), "snapshot", {{"path", "elsewhere"}}).value("error").toString(), QString("unknown_field"));
        const auto current = call(dir.path(), "snapshot");
        QJsonObject document{{"schemaVersion", 1}, {"profiles", QJsonArray{}}, {"password", "synthetic"}};
        QCOMPARE(call(dir.path(), "replace", {{"document", document}, {"revision", current.value("revision")}})
                 .value("error").toString(), QString("unknown_field"));
        QVERIFY(!QFile::exists(dir.filePath("profiles.json")));
        const auto huge = geekoc::handleDesktopRequest(dir.path(), QByteArray(geekoc::MaxRequestBytes + 1, ' '));
        QCOMPARE(QJsonDocument::fromJson(huge).object().value("error").toString(), QString("request_too_large"));
    }
    void respectsLegacyLockAndCorruptFile() {
        QTemporaryDir dir;
        QLockFile lock(dir.filePath("profiles.lock"));
        QVERIFY(lock.tryLock());
        QCOMPARE(call(dir.path(), "snapshot").value("error").toString(), QString("config_locked"));
        lock.unlock();
        QFile file(dir.filePath("profiles.json"));
        QVERIFY(file.open(QIODevice::WriteOnly));
        file.write("future format");
        file.close();
        QCOMPARE(call(dir.path(), "snapshot").value("error").toString(), QString("invalid_json"));
        QVERIFY(file.open(QIODevice::ReadOnly));
        QCOMPARE(file.readAll(), QByteArray("future format"));
    }
};
QTEST_GUILESS_MAIN(BridgeTests)
#include "bridge_test.moc"
