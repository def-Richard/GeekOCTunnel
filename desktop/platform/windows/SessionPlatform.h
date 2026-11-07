#pragma once
#include "HelperSession.h"
#include <wincred.h>
#include <QByteArray>
#include <QCoreApplication>
#include <QString>

namespace geekoc::vpn {
using PlatformError = WinError;
using CommandSocket = SOCKET;
constexpr auto InvalidCommand = INVALID_SOCKET;
class StopEvent : public Handle {
public:
    StopEvent() : Handle(CreateEventW(nullptr, TRUE, FALSE, nullptr)) { check(valid(), "cancel_setup_failed"); }
    void set() { SetEvent(get()); }
};
inline QString openconnectLibrary() { return QCoreApplication::applicationDirPath() + "/libopenconnect-5.dll"; }
inline void initializePlatform() {
    WSADATA data{}; check(WSAStartup(MAKEWORD(2,2), &data) == 0, "winsock_failed");
    check(SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_SYSTEM32 | LOAD_LIBRARY_SEARCH_USER_DIRS), "library_search_failed");
    check(AddDllDirectory(QCoreApplication::applicationDirPath().toStdWString().c_str()) != nullptr, "library_search_failed");
}
inline void writeCommand(CommandSocket socket, char value) { send(socket, &value, 1, 0); }
inline intptr_t tunnelArgument(HANDLE handle) { return reinterpret_cast<intptr_t>(handle); }
inline int readInput(char *buffer, size_t capacity, bool wait = false) {
    DWORD available = 0, size = 0;
    if (!wait) {
        if (!PeekNamedPipe(GetStdHandle(STD_INPUT_HANDLE), nullptr, 0, nullptr, &available, nullptr)) return -1;
        if (!available) { Sleep(200); return 0; }
    }
    if (!ReadFile(GetStdHandle(STD_INPUT_HANDLE), buffer, DWORD(capacity), &size, nullptr) || !size) return -1;
    return int(size);
}
inline bool writeOutput(const QByteArray &bytes) {
    DWORD written = 0;
    return WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), bytes.constData(), DWORD(bytes.size()), &written, nullptr) && written == bytes.size();
}
inline QByteArray readSecret(const QString &target) {
    PCREDENTIALW credential = nullptr;
    if (!CredReadW(target.toStdWString().c_str(), CRED_TYPE_GENERIC, 0, &credential)) return {};
    QByteArray bytes(reinterpret_cast<const char *>(credential->CredentialBlob), int(credential->CredentialBlobSize));
    SecureZeroMemory(credential->CredentialBlob, credential->CredentialBlobSize); CredFree(credential);
    return bytes;
}
inline bool writeSecret(const QString &target, const QByteArray &bytes) {
    if (bytes.size() > CRED_MAX_CREDENTIAL_BLOB_SIZE) return false;
    auto name = target.toStdWString();
    CREDENTIALW credential{}; credential.Type = CRED_TYPE_GENERIC; credential.TargetName = name.data();
    credential.Persist = CRED_PERSIST_LOCAL_MACHINE; credential.CredentialBlobSize = DWORD(bytes.size());
    credential.CredentialBlob = reinterpret_cast<LPBYTE>(const_cast<char *>(bytes.constData()));
    return CredWriteW(&credential, 0);
}
}
