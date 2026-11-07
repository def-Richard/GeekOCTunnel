#pragma once
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <string>
#include <stdexcept>
#include <utility>
#include <iterator>

namespace geekoc::vpn {
struct WinError : std::runtime_error {
    DWORD code;
    explicit WinError(const char *message, DWORD value = GetLastError()) : std::runtime_error(message), code(value) {}
};
inline void check(bool ok, const char *code) { if (!ok) throw WinError(code); }
class Handle {
    HANDLE handle_ = INVALID_HANDLE_VALUE;
public:
    explicit Handle(HANDLE value = INVALID_HANDLE_VALUE) : handle_(value) {}
    ~Handle() { reset(); }
    Handle(const Handle &) = delete;
    Handle &operator=(const Handle &) = delete;
    Handle(Handle &&other) noexcept : handle_(other.release()) {}
    Handle &operator=(Handle &&other) noexcept { reset(other.release()); return *this; }
    HANDLE get() const { return handle_; }
    bool valid() const { return handle_ && handle_ != INVALID_HANDLE_VALUE; }
    HANDLE release() { return std::exchange(handle_, INVALID_HANDLE_VALUE); }
    void reset(HANDLE value = INVALID_HANDLE_VALUE) { if (valid()) CloseHandle(handle_); handle_ = value; }
};
inline std::wstring resolvedFilePath(const std::wstring &path) {
    Handle file(CreateFileW(path.c_str(), 0, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr));
    check(file.valid(), "executable_path_failed");
    wchar_t finalPath[32768];
    const auto size = GetFinalPathNameByHandleW(file.get(), finalPath, DWORD(std::size(finalPath)), FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
    check(size && size < std::size(finalPath), "executable_path_failed");
    std::wstring result(finalPath, size);
    // UAC runs outside per-user path redirection; launch and compare the same physical file.
    if (result.rfind(L"\\\\?\\UNC\\", 0) == 0) return L"\\\\" + result.substr(8);
    if (result.rfind(L"\\\\?\\", 0) == 0 && result.size() > 6 && result[5] == L':') result.erase(0, 4);
    return result;
}
inline std::wstring executablePath(HANDLE process = nullptr) {
    wchar_t path[32768]; DWORD size = DWORD(std::size(path));
    if (process) check(QueryFullProcessImageNameW(process, 0, path, &size), "parent_identity_failed");
    else { size = GetModuleFileNameW(nullptr, path, DWORD(std::size(path))); check(size && size < std::size(path), "executable_path_failed"); }
    return resolvedFilePath({path, size});
}
inline std::wstring sibling(const wchar_t *name) {
    const auto path = executablePath();
    return path.substr(0, path.find_last_of(L"\\/") + 1) + name;
}
inline bool transfer(HANDLE pipe, bool writing, void *data, DWORD size, HANDLE stop, DWORD timeout = 5000) {
    Handle event(CreateEventW(nullptr, TRUE, FALSE, nullptr));
    if (!event.valid()) return false;
    OVERLAPPED operation{}; operation.hEvent = event.get(); DWORD done = 0;
    const BOOL ok = writing ? WriteFile(pipe, data, size, &done, &operation) : ReadFile(pipe, data, size, &done, &operation);
    if (ok) return done == size;
    if (GetLastError() != ERROR_IO_PENDING) return false;
    HANDLE waits[]{stop, event.get()};
    if (WaitForMultipleObjects(2, waits, FALSE, timeout) == WAIT_OBJECT_0 + 1)
        return GetOverlappedResult(pipe, &operation, &done, FALSE) && done == size;
    CancelIoEx(pipe, &operation);
    GetOverlappedResult(pipe, &operation, &done, TRUE);
    return false;
}
}
