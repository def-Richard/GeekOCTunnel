#include "platform/windows/WinHandles.h"
#include "core/NetworkProtocol.h"
#include "platform/windows/InterfaceCleanup.h"
#include <ws2ipdef.h>
#include <iphlpapi.h>
#include <sddl.h>
#include <bcrypt.h>
#include <wintun.h>
#include <algorithm>
#include <array>
#include <memory>
#include <thread>
#include <vector>

using namespace geekoc::vpn;

class NetworkSetupLock {
    Handle mutex_;
    bool acquired_ = false;
public:
    NetworkSetupLock() {
        PSECURITY_DESCRIPTOR descriptor = nullptr;
        check(ConvertStringSecurityDescriptorToSecurityDescriptorW(L"D:P(A;;GA;;;SY)(A;;GA;;;BA)", SDDL_REVISION_1, &descriptor, nullptr), "network_lock_failed");
        SECURITY_ATTRIBUTES attributes{sizeof(attributes), descriptor, FALSE};
        mutex_.reset(CreateMutexW(&attributes, FALSE, L"Global\\GeekOCTunnel.NetworkSetup.v1"));
        LocalFree(descriptor); check(mutex_.valid(), "network_lock_failed");
        const auto result = WaitForSingleObject(mutex_.get(), 20000);
        acquired_ = result == WAIT_OBJECT_0 || result == WAIT_ABANDONED;
        check(acquired_, "network_setup_busy");
    }
    void unlock() { if (acquired_) { ReleaseMutex(mutex_.get()); acquired_ = false; } }
    ~NetworkSetupLock() { unlock(); }
};

static std::wstring sid(HANDLE process) {
    HANDLE raw = nullptr; check(OpenProcessToken(process, TOKEN_QUERY, &raw), "token_query_failed"); Handle token(raw);
    DWORD bytes = 0; GetTokenInformation(token.get(), TokenUser, nullptr, 0, &bytes);
    std::vector<unsigned char> buffer(bytes);
    check(GetTokenInformation(token.get(), TokenUser, buffer.data(), bytes, &bytes), "token_query_failed");
    wchar_t *text = nullptr; check(ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER *>(buffer.data())->User.Sid, &text), "sid_failed");
    std::wstring result(text); LocalFree(text); return result;
}
static HMODULE verifiedDriver(Handle &file) {
    const auto path = sibling(L"wintun.dll");
    file.reset(CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr));
    check(file.valid(), "driver_missing");
    BCRYPT_ALG_HANDLE algorithm = nullptr; BCRYPT_HASH_HANDLE hash = nullptr;
    check(BCryptOpenAlgorithmProvider(&algorithm, BCRYPT_SHA256_ALGORITHM, nullptr, 0) == 0, "driver_hash_failed");
    const auto created = BCryptCreateHash(algorithm, &hash, nullptr, 0, nullptr, 0, 0);
    if (created != 0) { BCryptCloseAlgorithmProvider(algorithm, 0); throw WinError("driver_hash_failed"); }
    std::array<unsigned char, 16384> data{}; DWORD count = 0; size_t total = 0; bool ok = true;
    while (true) {
        if (!ReadFile(file.get(), data.data(), DWORD(data.size()), &count, nullptr)) { ok = false; break; }
        if (!count) break;
        total += count;
        if (total > 8 * 1024 * 1024 || BCryptHashData(hash, data.data(), count, 0) != 0) { ok = false; break; }
    }
    std::array<unsigned char, 32> digest{};
    ok &= BCryptFinishHash(hash, digest.data(), DWORD(digest.size()), 0) == 0;
    BCryptDestroyHash(hash); BCryptCloseAlgorithmProvider(algorithm, 0);
    constexpr unsigned char expected[]{0xe5,0xda,0x84,0x47,0xdc,0x2c,0x32,0x0e,0xdc,0x0f,0xc5,0x2f,0xa0,0x18,0x85,0xc1,0x03,0xde,0x8c,0x11,0x84,0x81,0xf6,0x83,0x64,0x3c,0xac,0xc3,0x22,0x0d,0xaf,0xce};
    check(ok && std::equal(digest.begin(), digest.end(), std::begin(expected)), "driver_hash_mismatch");
    auto module = LoadLibraryExW(path.c_str(), nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32 | LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR);
    check(module != nullptr, "driver_load_failed"); return module;
}
template<class T> static T *api(HMODULE module, const char *name) {
    auto result = reinterpret_cast<T *>(GetProcAddress(module, name)); check(result != nullptr, "driver_api_missing"); return result;
}
static Handle connectPipe(const std::wstring &name, DWORD parent) {
    check(WaitNamedPipeW(name.c_str(), 5000), "pipe_missing");
    Handle pipe(CreateFileW(name.c_str(), GENERIC_READ | GENERIC_WRITE, 0, nullptr, OPEN_EXISTING,
        FILE_FLAG_OVERLAPPED | SECURITY_SQOS_PRESENT | SECURITY_IDENTIFICATION, nullptr));
    check(pipe.valid(), "pipe_open_failed"); DWORD pid = 0, mode = PIPE_READMODE_MESSAGE;
    check(GetNamedPipeServerProcessId(pipe.get(), &pid) && pid == parent, "pipe_peer_mismatch");
    check(SetNamedPipeHandleState(pipe.get(), &mode, nullptr, nullptr), "pipe_mode_failed");
    return pipe;
}
struct Network {
    NET_LUID luid{};
    std::vector<MIB_IPFORWARD_ROW2> routes;
    ~Network() { for (auto i = routes.rbegin(); i != routes.rend(); ++i) DeleteIpForwardEntry2(&*i); }
    void configure(const Plan &plan) {
        MIB_IPINTERFACE_ROW interface{}; InitializeIpInterfaceEntry(&interface);
        interface.Family = AF_INET; interface.InterfaceLuid = luid;
        auto error = GetIpInterfaceEntry(&interface); if (error) throw WinError("interface_read_failed", error);
        interface.NlMtu = plan.mtu; interface.UseAutomaticMetric = FALSE; interface.Metric = 50;
        interface.SitePrefixLength = 0;
        error = SetIpInterfaceEntry(&interface); if (error) throw WinError("interface_config_failed", error);
        MIB_UNICASTIPADDRESS_ROW address{}; InitializeUnicastIpAddressEntry(&address);
        address.InterfaceLuid = luid; address.Address.Ipv4.sin_family = AF_INET;
        address.Address.Ipv4.sin_addr.S_un.S_addr = htonl(plan.address);
        address.OnLinkPrefixLength = static_cast<UINT8>(plan.prefix);
        address.PrefixOrigin = IpPrefixOriginManual; address.SuffixOrigin = IpSuffixOriginManual;
        address.DadState = IpDadStatePreferred;
        error = CreateUnicastIpAddressEntry(&address); if (error) throw WinError("address_config_failed", error);
        for (uint32_t i = 0; i < plan.routeCount; ++i) {
            MIB_IPFORWARD_ROW2 route{}; InitializeIpForwardEntry(&route);
            route.InterfaceLuid = luid; route.DestinationPrefix.Prefix.Ipv4.sin_family = AF_INET;
            route.DestinationPrefix.Prefix.Ipv4.sin_addr.S_un.S_addr = htonl(plan.routes[i].network);
            route.DestinationPrefix.PrefixLength = static_cast<UINT8>(plan.routes[i].prefix);
            route.NextHop.Ipv4.sin_family = AF_INET; route.Metric = 5; route.Protocol = MIB_IPPROTO_NETMGMT;
            error = CreateIpForwardEntry2(&route); if (error) throw WinError("route_config_failed", error);
            routes.push_back(route);
        }
        if (plan.dnsCount) {
            std::wstring servers;
            for (uint32_t i = 0; i < plan.dnsCount; ++i) {
                IN_ADDR ip{}; ip.S_un.S_addr = htonl(plan.dns[i]); wchar_t text[INET_ADDRSTRLEN];
                check(InetNtopW(AF_INET, &ip, text, DWORD(std::size(text))) != nullptr, "dns_address_failed");
                if (i) servers += L","; servers += text;
            }
            GUID guid{}; error = ConvertInterfaceLuidToGuid(&luid, &guid); if (error) throw WinError("interface_guid_failed", error);
            DNS_INTERFACE_SETTINGS dns{}; dns.Version = DNS_INTERFACE_SETTINGS_VERSION1;
            dns.Flags = DNS_SETTING_NAMESERVER | DNS_SETTING_REGISTRATION_ENABLED;
            dns.NameServer = servers.data();
            error = SetInterfaceDnsSettings(guid, &dns); if (error) throw WinError("dns_config_failed", error);
        }
    }
};
static void rejectConflicts(const Plan &plan) {
    PMIB_IPFORWARD_TABLE2 table = nullptr;
    auto error = GetIpForwardTable2(AF_INET, &table); if (error) throw WinError("route_read_failed", error);
    bool conflict = false;
    for (ULONG i = 0; i < table->NumEntries; ++i) {
        const auto &row = table->Table[i];
        if (!row.DestinationPrefix.PrefixLength) continue;
        Route existing{ntohl(row.DestinationPrefix.Prefix.Ipv4.sin_addr.S_un.S_addr), row.DestinationPrefix.PrefixLength};
        for (uint32_t j = 0; j < plan.routeCount; ++j)
            conflict |= contains(existing, plan.routes[j].network) || contains(plan.routes[j], existing.network);
    }
    FreeMibTable(table);
    if (conflict) throw WinError("route_conflict", ERROR_OBJECT_ALREADY_EXISTS);
}
struct Threads {
    HANDLE stop;
    std::thread outbound, inbound;
    ~Threads() {
        SetEvent(stop);
        if (outbound.joinable()) outbound.join();
        if (inbound.joinable()) inbound.join();
    }
};

int wmain(int argc, wchar_t **argv) {
    Handle control, data, stop(CreateEventW(nullptr, TRUE, FALSE, nullptr));
    Reply reply;
    try {
        check(argc == 5 && std::wstring(argv[1]) == L"--parent" && std::wstring(argv[3]) == L"--session", "invalid_arguments");
        wchar_t *end = nullptr; const DWORD pid = wcstoul(argv[2], &end, 10);
        check(pid && end && !*end, "invalid_parent"); const std::wstring id(argv[4]);
        check(id.size() == 36 && std::all_of(id.begin(), id.end(), [](wchar_t c) { return (c >= L'0' && c <= L'9') || (c >= L'a' && c <= L'f') || c == L'-'; }), "invalid_session");
        Handle parent(OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid));
        check(parent.valid() && _wcsicmp(executablePath(parent.get()).c_str(), sibling(L"geekoctunnel-core.exe").c_str()) == 0, "parent_identity_failed");
        check(sid(parent.get()) == sid(GetCurrentProcess()), "parent_user_mismatch");
        const auto prefix = L"\\\\.\\pipe\\GeekOCTunnel-" + id;
        control = connectPipe(prefix + L"-control", pid); data = connectPipe(prefix + L"-data", pid);
        Plan plan{};
        check(transfer(control.get(), false, &plan, sizeof(plan), stop.get()) && valid(plan), "invalid_network_plan");
        NetworkSetupLock setupLock;
        rejectConflicts(plan);
        Handle driverFile; auto module = verifiedDriver(driverFile);
        const auto close = api<WINTUN_CLOSE_ADAPTER_FUNC>(module, "WintunCloseAdapter");
        const auto endSession = api<WINTUN_END_SESSION_FUNC>(module, "WintunEndSession");
        const auto receive = api<WINTUN_RECEIVE_PACKET_FUNC>(module, "WintunReceivePacket");
        const auto release = api<WINTUN_RELEASE_RECEIVE_PACKET_FUNC>(module, "WintunReleaseReceivePacket");
        const auto allocate = api<WINTUN_ALLOCATE_SEND_PACKET_FUNC>(module, "WintunAllocateSendPacket");
        const auto sendPacket = api<WINTUN_SEND_PACKET_FUNC>(module, "WintunSendPacket");
        {
            const auto name = L"GeekOC-" + id;
            auto adapter = std::unique_ptr<_WINTUN_ADAPTER, decltype(close)>(api<WINTUN_CREATE_ADAPTER_FUNC>(module, "WintunCreateAdapter")(name.c_str(), L"GeekOCTunnel", nullptr), close);
            check(adapter != nullptr, "adapter_create_failed");
            Network network; api<WINTUN_GET_ADAPTER_LUID_FUNC>(module, "WintunGetAdapterLUID")(adapter.get(), &network.luid);
            reply.luid = network.luid.Value;
            network.configure(plan);
            auto session = std::unique_ptr<_TUN_SESSION, decltype(endSession)>(api<WINTUN_START_SESSION_FUNC>(module, "WintunStartSession")(adapter.get(), WINTUN_MIN_RING_CAPACITY), endSession);
            check(session != nullptr, "wintun_session_failed");
            setupLock.unlock();
            const auto readEvent = api<WINTUN_GET_READ_WAIT_EVENT_FUNC>(module, "WintunGetReadWaitEvent")(session.get());
            reply.phase = 1; reply.error = 0; reply.luid = network.luid.Value;
            check(transfer(control.get(), true, &reply, sizeof(reply), stop.get()), "ready_reply_failed");
            Threads threads{stop.get(), {}, {}};
            threads.outbound = std::thread([&] {
                while (WaitForSingleObject(stop.get(), 0) == WAIT_TIMEOUT) {
                    DWORD size = 0; auto *packet = receive(session.get(), &size);
                    if (packet) {
                        const bool ok = !ipv4Packet(packet, size) || transfer(data.get(), true, packet, size, stop.get());
                        release(session.get(), packet); if (!ok) break;
                    } else if (GetLastError() == ERROR_NO_MORE_ITEMS) {
                        HANDLE waits[]{stop.get(), readEvent}; if (WaitForMultipleObjects(2, waits, FALSE, INFINITE) != WAIT_OBJECT_0 + 1) break;
                    } else break;
                }
                SetEvent(stop.get());
            });
            threads.inbound = std::thread([&] {
                std::array<unsigned char, 65535> buffer{};
                while (WaitForSingleObject(stop.get(), 0) == WAIT_TIMEOUT) {
                    Handle event(CreateEventW(nullptr, TRUE, FALSE, nullptr)); OVERLAPPED op{}; op.hEvent = event.get(); DWORD size = 0;
                    bool ok = ReadFile(data.get(), buffer.data(), DWORD(buffer.size()), &size, &op);
                    if (!ok && GetLastError() == ERROR_IO_PENDING) {
                        HANDLE waits[]{stop.get(), event.get()};
                        if (WaitForMultipleObjects(2, waits, FALSE, INFINITE) != WAIT_OBJECT_0 + 1) {
                            CancelIoEx(data.get(), &op); GetOverlappedResult(data.get(), &op, &size, TRUE); break;
                        }
                        ok = GetOverlappedResult(data.get(), &op, &size, FALSE);
                    }
                    if (!ok || !size) break;
                    if (!ipv4Packet(buffer.data(), size)) continue;
                    auto *packet = allocate(session.get(), size);
                    if (!packet) { if (GetLastError() == ERROR_BUFFER_OVERFLOW) continue; break; }
                    std::copy_n(buffer.data(), size, packet); sendPacket(session.get(), packet);
                }
                SetEvent(stop.get());
            });
            Handle stopReadEvent(CreateEventW(nullptr, TRUE, FALSE, nullptr)); OVERLAPPED stopRead{};
            stopRead.hEvent = stopReadEvent.get(); DWORD bytes = 0; unsigned char stopByte = 0;
            const bool readNow = ReadFile(control.get(), &stopByte, 1, &bytes, &stopRead);
            if (!readNow && GetLastError() == ERROR_IO_PENDING) {
                HANDLE waits[]{parent.get(), stop.get(), stopReadEvent.get()};
                WaitForMultipleObjects(3, waits, FALSE, INFINITE);
                CancelIoEx(control.get(), &stopRead); GetOverlappedResult(control.get(), &stopRead, &bytes, TRUE);
            }
            SetEvent(stop.get());
            // Unwind session, routes and the newly created adapter before confirming shutdown.
        }
        FreeLibrary(module);
        reply.phase = 2; reply.error = 0;
    } catch (const WinError &error) { reply.error = error.code ? error.code : ERROR_INVALID_DATA; }
      catch (...) { reply.error = ERROR_INTERNAL_ERROR; }
    data.reset();
    const auto cleanup = reply.luid ? waitForInterfaceRemoval(NET_LUID{reply.luid}) : NO_ERROR;
    if (cleanup) reply.error = cleanup;
    if (control.valid()) {
        ResetEvent(stop.get()); transfer(control.get(), true, &reply, sizeof(reply), stop.get(), 2000);
    }
    return cleanup ? 2 : 0; // Setup errors are in Reply; exit status confirms network cleanup.
}
