#pragma once
#include <winsock2.h>
#include <windows.h>
#include <iphlpapi.h>

namespace geekoc::vpn {
// Query the table: an invalid single-row query is not proof that an adapter disappeared.
inline DWORD waitForInterfaceRemoval(NET_LUID luid) {
    for (int attempt = 0; attempt < 100; ++attempt) {
        PMIB_IF_TABLE2 table = nullptr;
        const auto error = GetIfTable2(&table);
        if (error) return error;
        bool found = false;
        for (ULONG i = 0; i < table->NumEntries; ++i)
            found |= table->Table[i].InterfaceLuid.Value == luid.Value;
        FreeMibTable(table);
        if (!found) return NO_ERROR;
        Sleep(50);
    }
    return ERROR_TIMEOUT;
}
}
