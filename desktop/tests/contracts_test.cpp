#include "core/ProfileStore.h"
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QTemporaryDir>
#include <QtTest>
#include <stdexcept>

class ContractTests : public QObject {
    Q_OBJECT
private slots:
    void udpOptionPreservesLegacyConnections() {
        auto profiles = geekoc::decodeProfiles(R"({"schemaVersion":1,"profiles":[{"id":"legacy","name":"Legacy","server":"vpn.example.com"}]})");
        QVERIFY(!profiles[0].enableUdp);
        profiles[0].enableUdp = true;
        QVERIFY(geekoc::decodeProfiles(geekoc::encodeProfiles(profiles))[0].enableUdp);
        QVERIFY_EXCEPTION_THROWN(geekoc::decodeProfiles(R"({"schemaVersion":1,"profiles":[{"id":"invalid","name":"Invalid","server":"vpn.example.com","enableUdp":"true"}]})"), std::runtime_error);
    }
    void sharedServerAddresses() {
        QFile fixture(FIXTURE_PATH);
        QVERIFY(fixture.open(QIODevice::ReadOnly));
        int cases = 0;
        while (!fixture.atEnd()) {
            const auto line = QString::fromUtf8(fixture.readLine()).trimmed();
            if (line.isEmpty() || line.startsWith('#')) continue;
            const auto fields = line.split('\t');
            QCOMPARE(fields.size(), 2);
            if (fields[1] == "!") QVERIFY_EXCEPTION_THROWN(geekoc::normalizeServer(fields[0]), std::runtime_error);
            else QCOMPARE(geekoc::normalizeServer(fields[0]), fields[1]);
            ++cases;
        }
        QVERIFY(cases >= 25);
    }
    void roundTripAndPermissions() {
        QTemporaryDir dir;
        const auto path = dir.filePath("profiles.json");
        const QList<geekoc::Profile> profiles{{"test-1", "Example", "vpn.example.com"}};
        geekoc::saveProfiles(path, profiles);
        const auto loaded = geekoc::loadProfiles(path);
        QCOMPARE(loaded.size(), 1);
        QCOMPARE(loaded[0].id, profiles[0].id);
        QCOMPARE(loaded[0].server, profiles[0].server);
#ifndef _WIN32
        QVERIFY(!(QFile::permissions(path) & (QFileDevice::ReadGroup | QFileDevice::ReadOther)));
#endif
    }
    void rejectsUnsafeImports_data() {
        QTest::addColumn<QByteArray>("json");
        QTest::newRow("future schema") << QByteArray(R"({"schemaVersion":2,"profiles":[]})");
        QTest::newRow("fractional schema") << QByteArray(R"({"schemaVersion":1.5,"profiles":[]})");
        QTest::newRow("root secret") << QByteArray(R"({"schemaVersion":1,"profiles":[],"password":"synthetic"})");
        QTest::newRow("wrong array") << QByteArray(R"({"schemaVersion":1,"profiles":{}})");
        QTest::newRow("bad JSON") << QByteArray("{");
        QTest::newRow("oversize") << QByteArray(geekoc::MaxConfigBytes + 1, ' ');
        const QJsonObject valid{{"id", "test-1"}, {"name", "Example"}, {"server", "vpn.example.com"}};
        for (const auto &key : {"password", "cookie", "token", "credentialRef", "privateKey"}) {
            auto profile = valid;
            profile.insert(key, "synthetic");
            QTest::newRow(key) << QJsonDocument(QJsonObject{{"schemaVersion", 1}, {"profiles", QJsonArray{profile}}}).toJson();
        }
        for (const auto &key : {"id", "name", "server"}) {
            auto profile = valid;
            profile.remove(key);
            QTest::newRow(qPrintable(QString("missing %1").arg(key)))
                << QJsonDocument(QJsonObject{{"schemaVersion", 1}, {"profiles", QJsonArray{profile}}}).toJson();
        }
        auto insecure = valid;
        insecure.insert("ignoreCertificateErrors", true);
        QTest::newRow("imported trust") << QJsonDocument(QJsonObject{{"schemaVersion", 1}, {"profiles", QJsonArray{insecure}}}).toJson();
        QTest::newRow("duplicate id") << QJsonDocument(QJsonObject{{"schemaVersion", 1}, {"profiles", QJsonArray{valid, valid}}}).toJson();
        QJsonArray many;
        for (int i = 0; i < 129; ++i) { auto p = valid; p.insert("id", QString::number(i)); many.append(p); }
        QTest::newRow("profile limit") << QJsonDocument(QJsonObject{{"schemaVersion", 1}, {"profiles", many}}).toJson();
    }
    void rejectsUnsafeImports() {
        QFETCH(QByteArray, json);
        QVERIFY_EXCEPTION_THROWN(geekoc::decodeProfiles(json), std::runtime_error);
    }
    void failedSavePreservesOriginal() {
        QTemporaryDir dir;
        const auto path = dir.filePath("profiles.json");
        geekoc::saveProfiles(path, {{"test-1", "Original", "vpn.example.com"}});
        QVERIFY_EXCEPTION_THROWN(geekoc::saveProfiles(path, {{"test-2", "", "vpn.example.com"}}), std::runtime_error);
        QCOMPARE(geekoc::loadProfiles(path)[0].name, "Original");
        QVERIFY_EXCEPTION_THROWN(geekoc::saveProfiles(dir.path(), {}), std::runtime_error);
    }
    void futureFileIsNotOverwrittenOnRead() {
        QTemporaryDir dir;
        const auto path = dir.filePath("profiles.json");
        const QByteArray future(R"({"schemaVersion":99,"profiles":[],"future":true})");
        QFile file(path);
        QVERIFY(file.open(QIODevice::WriteOnly));
        QCOMPARE(file.write(future), future.size());
        file.close();
        QVERIFY_EXCEPTION_THROWN(geekoc::loadProfiles(path), std::runtime_error);
        QVERIFY(file.open(QIODevice::ReadOnly));
        QCOMPARE(file.readAll(), future);
    }
};
QTEST_GUILESS_MAIN(ContractTests)
#include "contracts_test.moc"
