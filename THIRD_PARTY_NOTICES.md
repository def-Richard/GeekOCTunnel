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

The GitHub Release also provides `GeekOCTunnel-v0.1.5-native-sources.zip`, containing the pinned OpenConnect source and all six native dependency source archives, verified against the upstream Makefile checksums. License texts remain inside the source archives.

The pinned OpenConnect source is available from [the upstream repository](https://gitlab.com/openconnect/openconnect/-/tree/f17fe20d337b400b476a73326de642a9f63b59c8). Its `android/Makefile` and `android/fetch.sh` specify the native dependency versions, source download locations and build process. The repository scripts fetch that revision, verify the NDK checksum and build the library with 16 KB page alignment.

The Android application source and build scripts are available in this repository. Developers can replace the JNI library in `app/src/main/jniLibs`, rebuild the APK and sign/install their own build. A different signing key may require uninstalling the existing application first.

AndroidX, Kotlin and kotlinx.coroutines dependencies are declared in [`app/build.gradle.kts`](app/build.gradle.kts) and retain their respective upstream licenses. This notice does not relicense any third-party software.
