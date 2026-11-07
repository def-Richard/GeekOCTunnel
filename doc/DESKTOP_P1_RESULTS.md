# Desktop single-tunnel validation

This is the earlier single-tunnel baseline. For subsequent Windows multi-session,
reconnect, diagnostics and tray changes, see [the newer report](DESKTOP_WINDOWS_DIAGNOSTICS.md).

Recorded 2026-11-06. This report covers a development preview, not a declaration of
production readiness. It supersedes the connection-status statements in the
[2026-10-26 P0 report](DESKTOP_P0_RESULTS.md). No desktop GitHub release was published.

## Implemented behavior

The Tauri UI now starts a real C++ session core. It supports authentication forms,
certificate fingerprint review, cancellation, disconnect and actual byte counters.
Session IDs and sequence numbers prevent stale events from changing the current UI.
Configuration editing is disabled during a session. Window close requests cleanup
and exits only after confirmation; unconfirmed cleanup is reported explicitly.

Windows uses Wintun behind private message pipes. The helper verifies its parent,
user SID and signed driver's pinned hash. The GUI and protocol core stay unelevated.
Linux uses a Polkit-authorized helper, peer-checked Unix sockets and a nonpersistent
TUN descriptor. It rejects writable installations and configures only its own link.
The Linux core remains an ordinary user; credentials never enter the helper protocol.

The shared plan accepts IPv4 split routes and routed DNS servers. Default routes,
unsupported address families, split exclusions and overlapping existing routes are
refused before configuring the network.

## Verified results

| Check | Windows x64 | Debian 12 container | Ubuntu 24.04 container |
| --- | --- | --- | --- |
| Configuration, bridge, network-plan CTest | 3/3 pass | 3/3 pass | 3/3 pass |
| Tauri Release and Rust native-transport tests | Pass; 2/2 | Pass; 2/2 | Uses the Debian-built portable executable |
| Real ocserv authentication and tunneled HTTP | Pass | Pass | Pass |
| DNS query through the tunnel | System resolver and direct query pass | systemd-resolved query passes | systemd-resolved query passes |
| Cancel authentication, reject certificate, bad password | Pass | Pass | Pass |
| Reject overlap with an existing route | Plan tests | Live conflict test passes | Live conflict test passes |
| Normal disconnect and Core crash cleanup | Pass | Pass | Pass |
| Helper crash triggers stop; OS resources disappear | Not forcibly killed | Pass; UI reports unconfirmed cleanup | Pass; UI reports unconfirmed cleanup |
| Credential storage and second-session reuse | Windows Credential Manager pass | No unlocked Secret Service fixture | No unlocked Secret Service fixture |
| Actual GUI connection entry and window-close cancellation | Observed in WebView2 | Ordinary-user Xvfb launch/render passes | Ordinary-user Xvfb launch/render passes |

Eight Web UI tests pass, covering HTML injection, revision conflicts, corrupt
configuration, browser-preview isolation, missing native components, authentication,
stale sessions, statistics and certificate review defaults.

The Windows temporary-adapter probe now confirms removal against the interface
table, rather than treating an arbitrary API error as proof of deletion. Linux
testing identified the caller-owned injected TUN descriptor and a duplicate-stop
socket shutdown race; both were corrected and the disconnect scenarios rerun.
Each Linux distribution passed seven live scenarios. Root-owned installation was
also exercised in both containers. Render inspection identified missing Chinese
fonts in minimal images; installation now requires them and the final screens were
checked with the CJK fonts and accessibility service present. The sidebar remains
visible while the main content scrolls.

The lab uses disposable synthetic credentials, a temporary CA and documentation-only
addresses. No company gateway credentials or real tunnel details are in this report.
Before/after checks compare pre-existing routes and DNS settings and assert that
no project interface remains. The server host's networking and unrelated containers
are outside the test namespace. Linux compilation and application cores run under
non-root UIDs; only container setup, system daemons and network helpers run as root.

## Reproducibility and limits

OpenConnect is pinned to `f17fe20d337b400b476a73326de642a9f63b59c8` and built with
the TLS cancellation patch. Windows additionally uses the message-pipe patch.
The application does not invoke a gateway-supplied network script.
Windows SDK 10.0.26100, MSVC 14.41 and Qt 6.8.3 are used locally; the Linux containers
use distribution Qt 6.4.2. Rust 1.99.1 and npm/Cargo lockfiles identify the GUI toolchain.

Raw scenario output, compiler logs, privacy-scan results and local package checksums
are retained in ignored build output. The Linux network harness is available at
`desktop/tests/linux_session_lab.py` and refuses to run outside an explicitly opted-in
disposable container. Both Linux distributions share the test host's kernel.

Still unverified: a real Linux desktop login/Polkit agent, Wayland, network switching,
long-duration sessions, locked/unlocked Linux keyrings, multiple users, installer
upgrades/uninstall, representative company gateways, signed distribution and macOS.
SSO, client certificates, full tunnel, IPv6, automatic reconnection, tray mode and
multiple simultaneous connections are not implemented. Do not use these results to
claim those capabilities or production support.
