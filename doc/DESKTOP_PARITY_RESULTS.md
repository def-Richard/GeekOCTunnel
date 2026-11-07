# Desktop settings and platform parity

Desktop preview validation, 2026-11-07. Published under the
`desktop-v0.1.0-preview.1` tag. Android runtime/signing are unchanged; a shared
address-contract test is included.

## Behavior

- Settings at the bottom left control close/minimize behavior, automatic reconnect,
  optional WebView release after 5–3600 seconds, and certificate verification.
  Certificate verification stays enabled by default. UDP/DTLS is per profile.
- The official Tauri single-instance plugin brings the original window forward.
  It also recreates a released WebView. Linux holds a fallback file lock when
  D-Bus cannot provide activation. Old previews must be exited once before upgrade.
- Windows and Linux use the same frontend. Linux checks for a real desktop tray
  host; without one it disables tray/lightweight controls and preserves taskbar
  minimization. A named Polkit action explains the helper authorization request.
- Linux helper shutdown no longer races with transport-loss detection and cancels
  a legitimate reconnect during normal cleanup.

## Observed checks

| Check | Result |
| --- | --- |
| Frontend tests | 21/21 |
| Windows native / Rust tests | 4/4 native; 6/6 Rust |
| Linux native / Rust tests | 3/3 native; 5/5 Rust |
| Windows real window | Three concurrent secondary starts exit; original window survives; second launch restores released WebView |
| Debian 12 / Ubuntu 24.04 network containers | 15 scenarios pass on each; Core runs as ordinary builder UID |
| Debian / Ubuntu Xvfb interaction | Settings open, Chinese text renders, absent tray disables unsafe hiding, ordinary minimize restores on second launch, live tray detection works |
| Debian / Ubuntu Xvfb lifecycle | Concurrent secondary starts, actual WebView destruction and process-triggered recreation pass |

The 15 network scenarios cover connection with HTTP/system DNS, authentication
cancellation, wrong password, certificate rejection, route conflict, Core crash,
Helper crash, parallel tunnels, a conflicting third tunnel preserving both data
paths, disconnecting one while preserving the other, reconnect with reused group
and credentials, stopping during backoff, DTLS data, UDP fallback to TCP, and
disabled automatic reconnect. All sessions leave no temporary TUN and restore
the original route/DNS state. A killed Helper correctly reports unconfirmed
cleanup even where the kernel has already removed its nonpersistent interface.

Windows isolated measurements without a VPN: visible window plus WebView processes
used 187.5 MiB private memory across seven processes; after actual release, the
remaining process used 6.1 MiB private memory (29.7 MiB working set). These are
observations, not a ceiling, and exclude connected Core/Helper processes.

Screenshots and raw safe event records are retained in ignored build output.
Synthetic fixtures use disposable credentials and documentation-only networks.
Host networking and existing business containers are not used or changed.

## Profile editing and remembered Group follow-up

The global edit lock was narrowed to the selected profile's active session.
Four frontend regressions cover preparing, connected, reconnecting and disconnecting:
editing another server leaves the active profile unchanged, while bulk import
remains blocked. The frontend suite now has 25 passing cases.

Successful login now persists the authentication Group alongside the stable
username/password fields in the system credential store; other selection fields
and one-time challenges are not added to persistent storage. A saved group that
is no longer offered prompts for a fresh choice instead of submitting an invalid
value. Existing entries without Group need one fresh remembered login.

The Windows regression reproduced the old failure, then verified the repaired
core against a two-group ocserv fixture: save once, end the process, start two
new processes, and authenticate without any new form. The fixture deliberately
advertises a default route, so the client stops before elevation or creating an
adapter. Only synthetic credentials were used and the test credential was deleted.
This checks authentication/persistence, not a new full-tunnel implementation or
acceptance against a user's actual gateway. Linux shares this core change;
interactive Linux keyring persistence remains unverified.

## Native tray status follow-up

The tray now uses five small original icon variants: idle, connected, busy,
partial success, and attention required. Number badges count only established
tunnels, from two through eight. Windows hover text lists profile names and
states within the 127 UTF-16 unit limit; longer summaries point to the menu.
The menu carries the detailed status list on both platforms. Linux's tray API
does not support custom hover text, so the menu is the supported equivalent.

Updates run from native session/configuration events. Statistics do not redraw
the tray; repeated unchanged presentations are skipped. There is no animation,
polling thread or additional process. Profile names are stripped of controls and
direction overrides, menu mnemonics are escaped, and endpoints/credentials are
not included. Starting a new connection clears prior errors; a manual stop with
confirmed cleanup becomes idle, while unconfirmed cleanup remains visible.

Windows passed nine Rust tests, four native tests and the 25 frontend tests.
Linux passed eight Rust tests and three native tests. The real-window test uses
synthetic status events with a separate app identity: partial connection status,
two connected statuses after destroying the WebView, and profile rename/removal
all reached the native tray successfully. Windows and Debian/Ubuntu Xvfb passed this
test, alongside existing single-instance/window-recreation checks.

The Windows observation was 191.8 MiB private memory with the visible WebView and
6.6 MiB after release (one process, 39.2 MiB working set). This includes synthetic
tray state, excludes real VPN Core/Helper processes, and is not a memory ceiling.
The artwork was inspected at 16/24/32 px on light/dark backgrounds. Mouse-hover
rendering on every Windows shell/DPI setup has not been manually certified.

## Remaining acceptance

The release installer was exercised on clean Debian 12 and Ubuntu 24.04 containers:
it installed missing libxml2/secret-tool and other runtime packages, loaded every
bundled shared-library dependency, enforced root ownership, handled reinstallation,
and refused symlink destinations or updates while an installed executable was active.
It reports an unavailable systemd-resolved backend without altering host DNS policy.
The Rust session reader now resets cleanup confirmation on each new reconnect
generation, so cleanup from an earlier attempt cannot certify a later crashed attempt.
The corresponding regression passes (Windows Rust total: ten; Linux: nine).

Xvfb/container checks do not certify a real Linux login session, Wayland, keyring
unlock or interactive Polkit authentication. Long-duration roaming and signed
installers remain unverified. Each reconnect may request elevation again.

macOS can share the frontend and core, but still needs its own tunnel, DNS,
privileged-helper and credential integration, followed by Apple signing/notarization
and real-device VPN validation. No macOS executable or successful Mac test is claimed.
