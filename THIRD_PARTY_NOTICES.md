# Third-party notices

GeekOCTunnel uses the official OpenConnect Java/JNI binding and bundles `libopenconnect.so`. Third-party copyright notices and license terms remain in force.

| Component | Version | Recorded license |
| --- | --- | --- |
| OpenConnect | 9.12, commit `f17fe20d337b400b476a73326de642a9f63b59c8` | LGPL-2.1-only |
| libxml2 | 2.13.4 | MIT |
| GMP | 6.3.0 | LGPL-3.0-or-later OR GPL-2.0-or-later |
| Nettle | 3.10 | LGPL-3.0-or-later OR GPL-2.0-or-later |
| GnuTLS | 3.7.11 | LGPL-2.1-or-later |
| stoken | 0.93 | LGPL-2.1-or-later |
| LZ4 | 1.10.0 | BSD-2-Clause |

The dependency libraries above are statically linked into the shared OpenConnect JNI library. License texts are in [`native/artifacts/licenses`](native/artifacts/licenses); artifact hashes and build provenance are in [`build-manifest.json`](native/artifacts/build-manifest.json).

## Source and rebuilding

Each GitHub Release also provides `GeekOCTunnel-v<version>-native-sources.zip`, containing the pinned OpenConnect source and all six native dependency source archives, verified against the upstream Makefile checksums. License texts remain inside the source archives. The native source bundle is unchanged in v0.1.22 and v0.1.23 because the bundled OpenConnect library and its dependencies have not changed.

The pinned OpenConnect source is available from [the upstream repository](https://gitlab.com/openconnect/openconnect/-/tree/f17fe20d337b400b476a73326de642a9f63b59c8). Its `android/Makefile` and `android/fetch.sh` specify the native dependency versions, source download locations and build process. The repository scripts fetch that revision, verify the NDK checksum and build the library with 16 KB page alignment.

The Android application source and build scripts are available in this repository. Developers can replace the JNI library in `app/src/main/jniLibs`, rebuild the APK and sign/install their own build. A different signing key may require uninstalling the existing application first.

AndroidX, Kotlin and kotlinx.coroutines dependencies are declared in [`app/build.gradle.kts`](app/build.gradle.kts) and retain their respective upstream licenses. This notice does not relicense any third-party software.

## Desktop development preview

The desktop interface uses Tauri 2 (MIT OR Apache-2.0) and Lucide icons (ISC), with
exact versions in the npm/Cargo lockfiles. Vite, Vitest and Happy DOM are build/test
dependencies; they are not shipped as application runtimes. The original launcher
artwork is reused from this repository's Android vector resource. These notices
do not assign a new license to this repository's original code.

Desktop activation uses `tauri-plugin-single-instance` 2.5.5 (Apache-2.0 OR MIT).
Linux tray-host detection uses `zbus` 5.19.0 (MIT), already shared with the plugin's
D-Bus dependency. Exact transitive versions remain in `Cargo.lock`.

The C++ configuration bridge and session core dynamically link Qt Core and Network. The Linux helper uses these system libraries; the Windows helper uses system APIs and Wintun. The optional
legacy P0 window also uses Gui/Widgets; Qt Test is used only by tests.
Windows uses the system WebView2 runtime; Linux requires WebKitGTK and GTK.
Runtime installation and distribution terms must be checked for public installers.
The Windows development SDK is Qt 6.8.3; Linux checks use each
tested distribution's Qt packages (minimum 6.4). These modules offer LGPL-3.0,
GPL and commercial licensing options subject to their actual distribution terms.
Their notices and corresponding sources accompany the desktop release, together
with rebuild/relink instructions. The preview is distributed as unsigned archives.

The probe compiles against the public OpenConnect header and loads a separately
installed OpenConnect library. Session development bundles include a separately
built OpenConnect library with the patches under `desktop/dependencies`; Android's
native artifacts are unchanged. Windows bundles also need Wintun and OpenConnect's
dependency DLLs. Corresponding sources, build provenance and the original upstream
driver/license notices must accompany any future public desktop redistribution.
Linux uses distribution-provided Qt, GTK, WebKitGTK, Polkit and resolver packages.
The development artifacts do not bundle OpenConnect-GUI or TAP.
The [OpenConnect-GUI project](https://gitlab.com/openconnect/openconnect-gui), licensed
under GPLv2, was consulted as a behavior/architecture reference; its implementation
was not copied. Its licenses do not relicense this repository's original code.

### Desktop release materials

The desktop preview Release includes a `third-party-sources.zip` asset and a
`LICENSES/` directory in each platform bundle. The source manifest under
`desktop/dependencies` records the exact corresponding Fedora source RPMs for the
shipped Windows binaries, verified by matching the DLL hashes. Those RPMs contain
the upstream sources, distribution patches and build specifications.

| Windows runtime | Version | License family |
| --- | --- | --- |
| Qt Core / Network | 6.8.3 | LGPL-3.0, with included third-party notices |
| OpenConnect | 9.12 plus desktop patches | LGPL-2.1 |
| GnuTLS | 3.8.11 | LGPL-2.1-or-later |
| GMP | 6.3.0 | LGPL-3.0-or-later OR GPL-2.0-or-later |
| Nettle / Hogweed | 3.7.2 | LGPL-3.0-or-later OR GPL-2.0-or-later |
| libtasn1 | 4.21.0 | LGPL-2.1-or-later |
| libxml2 | 2.12.9 | MIT |
| LZ4 | 1.9.4 | BSD-2-Clause |
| zlib | 1.3.2 | Zlib |
| win-iconv | 0.0.8 | BSD-style, see source license |
| winpthreads | MinGW-w64 12.0.0 | MIT / BSD-style, see COPYING |
| libgcc | 14.2.1 | GPL-3.0 with GCC Runtime Library Exception |
| Wintun | 0.14.1 | Original prebuilt redistribution license, included unchanged |

The Qt source archive includes bundled third-party sources and licenses. Rust
crate and Web UI notices are also included. Linux uses distribution-provided
runtime dependencies, while the patched OpenConnect library is bundled.

For exercising rights under the LGPL, users may rebuild or relink the desktop
application with modified LGPL libraries and reverse-engineer the combined work
to debug those modifications. The build guide explains runtime replacement and
regeneration of the Windows integrity manifest. This permission does not assign
a general new license to the project's original code.
