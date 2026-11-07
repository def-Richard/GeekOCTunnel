# Windows desktop diagnostics and background behavior

Recorded 2026-11-06. This is a local Windows x64 development preview, not a public
desktop release. The earlier [cross-platform baseline](DESKTOP_P1_RESULTS.md)
remains the evidence for Linux; its tests do not certify the new Linux behavior.

The subsequent settings and transport preview also fixes UAC launch from redirected
application-data paths. The old code gave ShellExecute a process-local alias that
the elevation service could not resolve. Resolving both executable identities to
their physical files fixed the reported “path does not exist” error; the cached
runtime passed the complete eight-scenario network suite.

Global settings now include close/minimize behavior, automatic reconnect, optional
WebView release after 5–3600 seconds, and an explicit certificate bypass (off by
default). Policy changes apply to new sessions. Each connection reports the policy
it actually started with, even if the global setting later changes. Per-profile
UDP support defaults off for existing profiles; new profiles offer it on by default.

Selecting another SSL Group now carries the submitted stable credentials through
the group's form refresh once. Rejected credentials clear that carry-over. Successful
sessions also retain their selected group in memory for reconnects. Group choices
and submitted credentials are not added to the diagnostic replay buffer.

Additional isolated tests passed: group selection with one username/password entry,
group-aware reconnect without another form, real DTLS/UDP tunneled HTTP, unavailable
UDP falling back to TCP, explicit certificate bypass, disabled automatic reconnect,
and rejection without automatic credential resubmission. The UDP fixture relays
datagrams over a scoped SSH stream and exposes no UDP port on the server host.

The updated test suites pass 20 frontend cases, four CTest suites and six Rust tests.
The separate ignored real-WebView lifecycle test was explicitly run and passed.
It observed seven processes and 205.5 MiB private memory with the WebView present;
after release, one process and 6.3 MiB private memory remained (38.6 MiB working set).
The window was then successfully recreated. These measurements exclude VPN Core/Helper
memory and use an isolated WebView user-data directory. Backend replay is tested
separately; this lifecycle run did not establish a live tunnel through the GUI.

The lifecycle design was informed by [Clash Verge Rev's lightweight implementation](https://github.com/clash-verge-rev/clash-verge-rev/blob/7fd537af08070defb0ee4236d2fe114422993b0f/src-tauri/src/core/lightweight.rs):
destroy the hidden window after a cancellable delay and rebuild it when needed.
This project's implementation retains its own bounded native-session replay and
waits for the frontend to confirm that no edit/login dialog would be lost.

## Delivered behavior

- Each server shows its assigned IPv4 address, gateway IP, MTU, transport, routed
  networks, and whether DNS servers were supplied. Disconnected details are marked
  as an inactive snapshot and cleared when starting a new logical connection.
- Each profile retains at most 200 diagnostic events in memory. A fixed message
  catalogue describes authentication, certificate, network and reconnect events.
  Raw OpenConnect messages, HTTP headers, cookies and passwords are never logged.
- Transient transport failures retry after 2, 4, 8, 15, 30 and then 60 seconds.
  A successful connection resets the delay. Reconnect requires confirmed cleanup
  of the preceding interface and revalidates the new server network configuration.
- Refused authentication, certificate problems, route conflicts, unconfirmed
  cleanup and manual disconnect do not automatically retry. A rejected login may
  offer a fresh form for the user to correct. Known gateway refusal messages are
  recognized even when the gateway omits the standard form error field.
- Up to eight different profiles may connect independently. Setup is serialized
  across helpers to prevent concurrent route conflict checks from racing.
- Closing offers “minimize to tray” or “disconnect all and exit”, optionally saved.
  A tray menu provides show, reset-close-preference and disconnect-and-exit actions.
  Hidden windows stop frontend statistics polling and DOM redraws; optional
  lightweight mode destroys the WebView after its delay. Visible statistics poll only the selected
  server every two seconds. The native session bridge waits for events without a
  periodic wakeup when no stop deadline is active.

## Packaging and process lifetime

Windows distributes one executable with a compressed, embedded native runtime.
It streams files to a content-addressed per-user cache, verifies SHA-256 hashes,
reuses valid files, and refuses corrupted files. It does not need a sibling
`native/` folder. Old cache revisions are retained so an update cannot remove
files used by another running version.

One file does not mean one process: WebView2 manages its browser processes. A
configuration bridge runs briefly for reads/writes. Each active VPN has an ordinary
Core and an elevated Helper; both exit after disconnect. Keeping elevation outside
the GUI avoids giving the Web UI administrator privileges. There is no permanent
service. The helper handles only a fixed validated network plan, never credentials.

## Verification

| Check | Result |
| --- | --- |
| Native CMake/CTest | 4/4 pass, including route validation, diagnostics, retry classification and physical executable paths |
| Frontend tests | 20/20 pass, including settings, UDP choices, restored state, certificate policy, log bounds and release guards |
| Rust release tests | 6/6 pass, plus the explicitly run real-window destruction/recreation test |
| Two simultaneous isolated VPNs | Both assigned addresses, routes and DNS match the negotiated plans; HTTP and direct DNS pass through both |
| Conflicting third tunnel | Rejected; both existing data paths remain operational |
| Disconnect one tunnel | The other tunnel continues passing HTTP and DNS |
| Restart server | New connection generation succeeds, credentials are reused in memory, HTTP and DNS recover |
| Stop during reconnect backoff | Exits with confirmed network cleanup |
| Incorrect password | Recognized refusal; no automatic reconnect |
| Reject certificate / cancel login | No automatic reconnect; cleanup confirmed |
| Single-executable launch | Configuration loads from embedded runtime without a sibling native folder |
| Actual close dialog / tray hide | Observed in the Windows WebView2 app |

The live fixture uses two disposable ocserv containers, temporary credentials and
documentation-only networks. It connects through local SSH forwarding; no host
networking or existing business containers are modified. All test sessions,
adapters, containers and forwarding processes were cleaned up. Raw safe event
records and build logs remain in ignored build output, not in the source tree.

## Limits

Earlier resource measurements without destroying the WebView include the main executable
and all six associated WebView2 processes, with no VPN connected:

| State | Private memory | Sum of working sets | CPU time over approximately 15.5 seconds |
| --- | --- | --- | --- |
| Visible, settled | 184.3 MiB | 423.9 MiB | 0.0000 seconds |
| Hidden to tray, low-memory hint | 184.9 MiB | 376.2 MiB | 0.0469 seconds |

Working-set sums count shared pages more than once; private memory is a separate
measure, not resident physical memory. These are short local observations, not a
memory ceiling. WebView2 retains a substantial baseline even with a small static
frontend (approximately 38.5 kB JavaScript and 12.1 kB CSS before compression).
No idle Core or Helper was present. Multiple connected tunnels add their own
protocol/helper memory, which was not included in this measurement. The native
tray menu's mouse activation and saved preference across an OS restart have not
been manually exercised; their command paths and UI preference handling are tested.

Only IPv4 split routes are accepted; no default route is installed. Overlapping
networks are rejected rather than translated. DNS is per interface, without
domain-based routing between conflicting namespaces. Unknown authentication
errors stop retries conservatively; the client cannot diagnose every vendor's
password policy precisely. Browser SSO, client certificates and IPv6 are not supported.

Each reconnect creates a new authorized helper, so Windows UAC or Linux Polkit may
require consent again according to local policy. No claim of unattended privilege
renewal is made. Updated Linux results are recorded in [the parity report](DESKTOP_PARITY_RESULTS.md).
macOS, long-duration network switching and signed installers remain unverified. Public distribution also requires complete
corresponding-source/license bundles and upgrade/uninstall acceptance.
