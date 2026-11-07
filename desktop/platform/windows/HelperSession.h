#pragma once
#include "WinHandles.h"
#include "core/NetworkProtocol.h"
#include <sddl.h>
#include <shellapi.h>
#include <mutex>

namespace geekoc::vpn {
class HelperSession {
    Handle process_, control_, data_, ioStop_{CreateEventW(nullptr, TRUE, FALSE, nullptr)};
    std::mutex controlMutex_;
    bool started_ = false;
    static Handle pipe(const std::wstring &name) {
        HANDLE raw = nullptr; check(OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &raw), "token_query_failed"); Handle token(raw);
        DWORD bytes = 0; GetTokenInformation(token.get(), TokenUser, nullptr, 0, &bytes);
        std::string storage(bytes, '\0');
        check(GetTokenInformation(token.get(), TokenUser, storage.data(), bytes, &bytes), "token_query_failed");
        wchar_t *user = nullptr;
        check(ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER *>(storage.data())->User.Sid, &user), "sid_failed");
        const auto sddl = L"D:P(A;;GA;;;SY)(A;;GA;;;" + std::wstring(user) + L")"; LocalFree(user);
        PSECURITY_DESCRIPTOR descriptor = nullptr;
        check(ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(), SDDL_REVISION_1, &descriptor, nullptr), "pipe_acl_failed");
        SECURITY_ATTRIBUTES attributes{sizeof(attributes), descriptor, FALSE};
        Handle result(CreateNamedPipeW(name.c_str(), PIPE_ACCESS_DUPLEX | FILE_FLAG_OVERLAPPED | FILE_FLAG_FIRST_PIPE_INSTANCE,
            PIPE_TYPE_MESSAGE | PIPE_READMODE_MESSAGE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
            1, 256 * 1024, 256 * 1024, 5000, &attributes));
        LocalFree(descriptor); check(result.valid(), "pipe_create_failed"); return result;
    }
    static void accept(HANDLE pipe, HANDLE stop, DWORD expectedPid) {
        Handle event(CreateEventW(nullptr, TRUE, FALSE, nullptr)); OVERLAPPED operation{}; operation.hEvent = event.get();
        if (!ConnectNamedPipe(pipe, &operation)) {
            const auto error = GetLastError();
            if (error == ERROR_IO_PENDING) {
                HANDLE waits[]{stop, event.get()};
                const auto wait = WaitForMultipleObjects(2, waits, FALSE, 10000);
                DWORD bytes = 0;
                if (wait != WAIT_OBJECT_0 + 1) {
                    CancelIoEx(pipe, &operation); GetOverlappedResult(pipe, &operation, &bytes, TRUE);
                    throw WinError("helper_connect_timeout", ERROR_TIMEOUT);
                }
                check(GetOverlappedResult(pipe, &operation, &bytes, FALSE), "helper_connect_failed");
            } else check(error == ERROR_PIPE_CONNECTED, "helper_connect_failed");
        }
        DWORD pid = 0;
        check(GetNamedPipeClientProcessId(pipe, &pid) && pid == expectedPid, "helper_peer_mismatch");
    }
public:
    ~HelperSession() { stop(); }
    bool transportLost() { return false; } // The Windows packet pipe reports Helper death to OpenConnect.
    HANDLE start(const std::string &key, const Plan &plan, HANDLE cancelled) {
        const std::wstring id(key.begin(), key.end());
        check(valid(plan), "invalid_network_plan");
        const auto prefix = L"\\\\.\\pipe\\GeekOCTunnel-" + id;
        { std::lock_guard guard(controlMutex_); control_ = pipe(prefix + L"-control"); }
        data_ = pipe(prefix + L"-data");
        const auto executable = sibling(L"geekoctunnel-helper.exe");
        const auto arguments = L"--parent " + std::to_wstring(GetCurrentProcessId()) + L" --session " + id;
        SHELLEXECUTEINFOW launch{}; launch.cbSize = sizeof(launch); launch.fMask = SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NOASYNC | SEE_MASK_FLAG_NO_UI;
        launch.lpVerb = L"runas"; launch.lpFile = executable.c_str(); launch.lpParameters = arguments.c_str(); launch.nShow = SW_HIDE;
        if (!ShellExecuteExW(&launch)) {
            const auto error = GetLastError();
            throw WinError(error == ERROR_CANCELLED ? "permission_denied" : "helper_launch_failed", error);
        }
        process_.reset(launch.hProcess); const auto pid = GetProcessId(process_.get());
        accept(control_.get(), cancelled, pid); accept(data_.get(), cancelled, pid);
        check(WaitForSingleObject(cancelled, 0) == WAIT_TIMEOUT, "cancelled");
        Plan copy = plan;
        check(transfer(control_.get(), true, &copy, sizeof(copy), cancelled), "helper_plan_failed");
        Reply response;
        check(transfer(control_.get(), false, &response, sizeof(response), cancelled, 30000), "helper_setup_failed");
        if (response.magic != Magic || response.phase != 1 || response.error)
            throw WinError(response.error == ERROR_OBJECT_ALREADY_EXISTS ? "route_conflict" : "network_setup_failed", response.error);
        started_ = true;
        return data_.release(); // OpenConnect owns this handle after successful attachment.
    }
    void requestStop() {
        std::lock_guard guard(controlMutex_);
        if (control_.valid()) { unsigned char command = 1; transfer(control_.get(), true, &command, 1, ioStop_.get(), 1000); }
    }
    bool stop() {
        requestStop();
        std::lock_guard guard(controlMutex_);
        bool clean = true;
        if (process_.valid()) {
            if (started_) {
                Reply response;
                clean = transfer(control_.get(), false, &response, sizeof(response), ioStop_.get(), 10000)
                    && response.magic == Magic && response.phase == 2 && response.error == 0;
            }
            clean &= WaitForSingleObject(process_.get(), 10000) == WAIT_OBJECT_0;
            DWORD exit = STILL_ACTIVE;
            clean &= GetExitCodeProcess(process_.get(), &exit) && exit == 0;
        }
        control_.reset(); data_.reset(); process_.reset(); started_ = false;
        return clean;
    }
};
}
