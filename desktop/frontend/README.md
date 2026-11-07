# Tauri desktop build guide

The local Web UI provides profiles, login forms, certificate review, connection
controls and real traffic counters. Rust launches fixed C++ executables and forwards
bounded messages; no shell or arbitrary filesystem operation is exposed to the UI.

`desktop_request` manages configuration. `vpn_start` creates one session; the Rust
backend retains bounded events and emits updates to the main window. `vpn_snapshot`
restores state after WebView recreation. Up to eight different profiles may be active.
`vpn_send` accepts only replies, statistics requests and stop commands for the selected
session. Window close offers tray mode or an exit that waits for every session's cleanup.

## Windows

Use PowerShell 7 in an x64 Visual Studio 2022 developer shell with CMake, Ninja,
Node.js 24+, Rust MSVC, Qt 6.4+ Core/Network/Test, WebView2 and the MSVC runtime.

Set `QT_ROOT` to the Qt SDK, `OPENCONNECT_HEADERS` to the pinned source headers,
`OPENCONNECT_RUNTIME` to the patched DLL directory, and `WINTUN_DLL` to the signed
upstream Wintun DLL. From the repository root:

```powershell
& ./desktop/Build-Windows.ps1
```

Resource preparation verifies the driver hash expected by the helper:
`e5da8447dc2c320edc0fc52fa01885c103de8c118481f683643cacc3220dafce`.
Replacing it requires review and revalidation, not disabling the check. Include
OpenConnect's dependency DLLs in the runtime directory.

The script runs C++ and UI tests, builds Tauri Release, and checks native process
communication. The Windows build compresses and embeds `resources/native/`; distribute
only the resulting executable, with notices and checksums as appropriate. At launch,
the runtime is streamed to a content-addressed application cache and SHA-256 checked.
`CARGO_TARGET_DIR` may override Cargo's default output location.

## Ubuntu / Debian

For a downloaded release bundle on Debian 12 or Ubuntu 24.04 x64, extract it and
run `sudo bash ./Install-Linux.sh` from its directory. Runtime packages, including
libxml2, libsecret-tools and GNOME Keyring, are installed through apt when missing.
No development SDK is needed for the binary bundle. `bash ./Install-Linux.sh --check`
checks without changing the system. Close the app and its connections before upgrading.
The installer checks shared libraries and installs root-owned executables under
`/opt/geekoctunnel`; launch them as the ordinary user. A newly installed keyring may
require logout/login and unlocking. DNS backend readiness is reported separately;
the installer never replaces the system resolver or enables systemd-resolved.
If DNS setup is needed but the backend is unavailable, configure systemd-resolved
according to your distribution's network policy before connecting. The `--check`
command returns nonzero until runtime packages and the DNS backend are ready.

Build and run as an ordinary user. Install dependencies on a dedicated development
system, choosing the packages appropriate to its distribution:

```bash
sudo apt-get install build-essential cmake ninja-build qt6-base-dev libopenconnect-dev \
  pkg-config libgtk-3-dev libwebkit2gtk-4.1-dev libssl-dev librsvg2-dev \
  iproute2 pkexec libsecret-tools fonts-noto-cjk at-spi2-core libayatana-appindicator3-dev
```

Install Node.js 24+ and a current Rust toolchain. Runtime networking needs an active
systemd-resolved service and desktop Polkit authentication agent. The installer does
not change the host DNS manager or automatically start services.

Build the pinned OpenConnect source with the nonblocking TLS patch and
`--with-vpnc-script=/bin/false`. Then:

```bash
export OPENCONNECT_RUNTIME=/path/to/patched/libopenconnect.so.5
bash desktop/Build-Linux.sh
```

Prepare a bundle containing the resulting `geekoctunnel`, its `native/` directory,
`frontend/public/app-icon.svg`, `desktop/platform/linux/io.github.def-richard.geekoctunnel.connect.policy`
and `desktop/Install-Linux.sh`. Place the icon, policy and installer at the bundle root. Install with
`sudo bash Install-Linux.sh /path/to/bundle`, then launch
`/opt/geekoctunnel/geekoctunnel` as the ordinary user. The helper deliberately refuses
a user-writable development checkout.

The installer registers a named Polkit action for its fixed helper path. Each new
helper still requires administrator authentication according to system policy.
Tray mode needs a StatusNotifier/AppIndicator desktop host (for example XFCE's
tray or GNOME's AppIndicator extension). Without it, the settings page explains
the limitation and preserves normal taskbar minimization.

## Frontend development

```powershell
npm ci
if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed' }
npm test
if ($LASTEXITCODE -ne 0) { throw 'UI tests failed' }
npm run dev
```

An ordinary browser is a layout preview and cannot invoke native operations.
`npm run tauri -- dev` uses the native boundary after preparing its resources.
Lockfiles pin dependencies. Tests cover hostile names, saves/conflicts, missing
components, login forms, stale-session events, certificate decisions, isolated
parallel sessions, network details, log bounds/privacy, hidden-window polling and
close preferences. Visible statistics refresh every two seconds for the selected
server only. No statistics timer runs while hidden or without an active session.

The Settings dialog includes an opt-in lightweight mode. After the chosen delay,
the frontend acknowledges release only when no edit/login dialog is open; Rust then
destroys the main window while keeping sessions and the tray alive. Tray activation
builds a fresh WebView. A native test explicitly exercises destruction and recreation:
`cargo test --release --features tauri/custom-protocol real_webview_is_released_and_recreated -- --ignored --nocapture`.
This test opens a separate verification window and does not connect to a real gateway.
It also launches concurrent secondary processes and checks activation after release.
On Linux it needs a session D-Bus, a display and a working tray host. The official
single-instance plugin forwards activation; a held file lock also prevents a second
instance when the session bus is unavailable.

`src-tauri/src/tray_status.rs` renders five native tray states and connected-count
badges, driven by session events rather than WebView timers. Windows tooltips are
limited to 127 UTF-16 code units with overflow directed to the tray menu. Linux
uses that menu because custom tooltips are unsupported by the tray backend.
Regenerate the small icon atlas on Windows with `scripts/New-TrayIcons.ps1`;
it uses System.Drawing and the existing terminal-mark artwork, without a runtime
graphics dependency. The real-window test injects synthetic session events,
verifies partial success, then verifies status updates after WebView destruction.

`desktop/tests/linux_session_lab.py` requires a disposable Docker container with
`GEEKOC_ISOLATED_TEST=1`, native binaries, a patched library and an ocserv fixture.
It runs Core under a non-root UID and creates a scoped Polkit rule inside that
container. Never use host networking or mount host D-Bus/system configuration.

`desktop/tests/windows_credential_lab.mjs` checks opt-in credential storage against
a synthetic two-group ocserv fixture. Set `GEEKOC_ISOLATED_TEST=1`, `GEEKOC_CORE`,
`LAB_SERVER` (IPv4 loopback), `LAB_CREDENTIAL_FILE` and `LAB_CA_FILE`. The fixture
must advertise a default route so Core stops before elevation or network setup.
The test saves the second group and login, then starts two fresh Core processes
and requires no authentication prompt. It deletes only its fixed synthetic
credential target afterward. `GEEKOC_LAUNCHER` may point to a Rust std::process
launcher when a test runner's Windows pipe handles differ from Tauri's.
