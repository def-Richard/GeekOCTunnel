#include "platform/windows/WinHandles.h"
#include <QtTest>
using namespace geekoc::vpn;
class WindowsPathsTests : public QObject {
    Q_OBJECT
private slots:
    void parentIdentityUsesTheSamePhysicalFile() {
        const auto path = executablePath();
        QVERIFY(!path.empty());
        QVERIFY(path.rfind(L"\\\\?\\", 0) != 0);
        QVERIFY(GetFileAttributesW(path.c_str()) != INVALID_FILE_ATTRIBUTES);
        Handle process(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, GetCurrentProcessId()));
        QVERIFY(process.valid());
        QCOMPARE(QString::fromStdWString(executablePath(process.get())).toLower(), QString::fromStdWString(path).toLower());
        const auto slash = path.find_last_of(L'\\');
        const auto alias = path.substr(0, slash) + L"\\.\\" + path.substr(slash + 1);
        QCOMPARE(QString::fromStdWString(resolvedFilePath(alias)).toLower(), QString::fromStdWString(path).toLower());
        QVERIFY_EXCEPTION_THROWN(resolvedFilePath(sibling(L"missing-test-component.exe")), WinError);
    }
};
QTEST_GUILESS_MAIN(WindowsPathsTests)
#include "windows_paths_test.moc"
