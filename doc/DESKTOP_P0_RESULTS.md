# Desktop development verification

Historical report. See [the 2026-11-06 single-tunnel results](DESKTOP_P1_RESULTS.md)
for the current implementation and validation status.

Recorded 2026-10-26. This is a development report, not a declaration of production
VPN support. Android remains the released application. No desktop release has been
published, and the desktop cannot establish a real VPN yet.

## UI decision and current behavior

The user selected **Tauri + Web UI** after reviewing the original Widgets preview.
The new window exposes server selection, add/edit/delete, import/export and a
visible Connect action. Connect reaches the native bridge, checks the selected
profile and explicitly returns `vpn_backend_unavailable`. No fake connected state,
traffic counters, route changes or password storage are provided.

The Web UI is bundled locally. Its only Tauri command invokes a fixed C++ executable
over child-process pipes. Requests have version/request IDs, byte limits and a
deadline. Revision comparison and `QLockFile` protect atomic configuration writes.
This short-lived configuration bridge is not the future authenticated Core/Helper
session protocol and must not be promoted into a privileged service.

## Validation matrix

| Check | Windows x64 | Ubuntu 24.04 container | Debian 12 container |
| --- | --- | --- | --- |
| Shared configuration contracts | Pass | Pass | Pass |
| Original Widgets smoke | Pass, also visually inspected | Offscreen pass | Offscreen pass |
| New C++ bridge semantics | Pass | Pass | Pass |
| Tauri Release executable | Build passed | Not run | Blocked before compilation by registry access |
| Rust to C++ real stdio exchange | Pass, temporary configuration | Not run | Not run |
| Patched OpenConnect stalled-TLS cancellation | Pass | Pass | Pass |
| Temporary tunnel device | Ordinary-user Wintun creation denied | Isolated TUN lifecycle pass | Not run |
| Real VPN authentication/data/routes/DNS | Not implemented | Not implemented | Not implemented |

Six frontend tests pass in a DOM environment: hostile profile names render only as
text, save updates after native success, conflicting writes retain the editor,
Connect reports the missing backend, corrupt configuration prevents empty-list
writes, and ordinary browser previews cannot invoke native commands.

The Web UI was visually inspected in a browser. Further desktop UI automation was
stopped after a physical Escape interruption. The new Tauri/WebView2 window and its
complete CRUD/import/export flow have not been manually accepted. DOM tests do not
replace WebView accessibility or native download-dialog tests.

Linux tests use ordinary container users on an Ubuntu host; Debian shares that host
kernel. They do not certify a full Linux desktop, Wayland/X11, NetworkManager or
resolved integration. The full Tauri container build was blocked by Docker Hub
reachability and the available mirror's certificate trust. No host trust, routing
or DNS settings were changed to bypass those errors.

## OpenConnect cancellation experiment

Source: OpenConnect 9.12 at `f17fe20d337b400b476a73326de642a9f63b59c8`, matching
`native/source-lock.json`. A separate source export receives the
[desktop TLS patch](../desktop/dependencies/openconnect-nonblocking-tls.patch).
Android's source checkout and native artifacts were not patched.

The probe connects only to a loopback TLS listener that stops after receiving
ClientHello. Original tested Windows, Ubuntu and Debian libraries failed to return
within two seconds after `OC_CMD_CANCEL`. Adding `GNUTLS_NONBLOCK` lets the existing
handshake loop observe cancellation. All three patched builds returned
`OC_FORM_RESULT_CANCELLED` (1), below one millisecond at the timer's resolution.
Callbacks ran on the worker thread. This is not a successful VPN login or a latency
distribution. Archive builds report `v9.12-unknown`; that string is not provenance.

Source archive SHA-256:
`475B478DE755791FB6A1DCC30CEF3A3EF3BDAF57E8BCEDBF5835FCA19A3CC985`.
Patch SHA-256:
`E4581F1EFA675B58D6D919BEB65C4AB5164523066D3DF49AC9A26FDB7A50915B`.
Linux experiment builds use `/bin/false` as the default network script.

## Device findings

- Windows rejects a generic pipe HANDLE as a TAP handle. This expected rejection
  does not prove a working Wintun/OpenConnect data path.
- The signed Wintun DLL's required exports load. Its running-driver query returned
  zero; no running driver was established by this check. Ordinary-user temporary
  adapter creation returns Windows error 5 (access denied). Elevated adapter
  creation, packet forwarding and service-stop tests remain unperformed.
- Linux accepts the tested datagram descriptor. In an isolated container with
  `/dev/net/tun` and `CAP_NET_ADMIN`, a temporary TUN was created, attached and
  removed. A normal user with the device exposed is denied creation.
- Freeing OpenConnect before entering the mainloop does not close the supplied
  Linux TUN descriptor. The caller must close it; the probe verifies cleanup.
- Host VPNs, routes and DNS were not changed. Privileged operations were confined
  to an isolated Linux container network namespace.

## Tools and remaining gates

Windows: MSVC 14.41, Windows SDK 10.0.26100, Qt 6.8.3, Rust 1.99.1,
Tauri Rust 2.12.5, Tauri CLI/API 2.12.4. npm/Cargo lockfiles record dependency
resolution. Debian uses Qt 6.4.2. The patched Windows OpenConnect library was built
with the upstream cross-toolchain image digest
`sha256:4b6ea3e2114e2afded0dfb6ed3f877e2b0fe509675d1e3a4d753acf1a8927180`.
Android's earlier JVM baseline was 74 tests with no failures/errors.

Remaining implementation: authentication, secured Core/Helper IPC, system credential
storage, Windows data path, route/DNS transactions, failure rollback, tray and
multi-tunnel behavior. Remaining acceptance: real gateway traffic, rejected
credentials/certificates, cancellation throughout the lifecycle, restoration after
failure, parallel VPNs, install/upgrade/uninstall and license/source bundles.
macOS has neither a build nor device acceptance result.

Resource budgets must be remeasured for Tauri, including WebView processes, Core
and Helper. The old Widgets process snapshot is not used as a Tauri memory claim.
The local executable does not imply signed-installer or supported-platform status.
