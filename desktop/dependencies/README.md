# OpenConnect desktop runtime

The base source is **9.12, commit `f17fe20d337b400b476a73326de642a9f63b59c8`**,
the same revision recorded in `native/source-lock.json`. Desktop patches are kept
here and applied to a separate source tree. They do not change the Android source
checkout, binaries or dependency lock.

`openconnect-nonblocking-tls.patch` adds `GNUTLS_NONBLOCK` when creating the HTTPS
session. A nonblocking socket alone did not make the tested GnuTLS handshake
return to OpenConnect's cancellation loop: the probe sent a ClientHello to a local
listener that never replies, wrote `OC_CMD_CANCEL`, and timed out after two seconds.
This occurred with the installed Windows 9.12 library and Ubuntu/Debian packages.

The patch allows the existing `cstp_handshake` select loop to monitor the command fd
while waiting for TLS. It does not weaken certificate verification or replace the
protocol. See [GnuTLS transport documentation](https://www.gnutls.org/manual/html_node/Setting-up-the-transport-layer.html)
and [measured results](../../doc/DESKTOP_P0_RESULTS.md). The AnyConnect cookie API
returns **1** on command cancellation; tests must not mistake that for success or
require only negative errors.

To reproduce with a disposable Linux build environment containing autoconf,
automake, libtool, gettext, a compiler, pkg-config, GnuTLS/XML/zlib development
headers, and a separate checkout of the pinned revision:

```bash
git apply --check /path/to/desktop/dependencies/openconnect-nonblocking-tls.patch
git apply /path/to/desktop/dependencies/openconnect-nonblocking-tls.patch
./autogen.sh
./configure --with-vpnc-script=/bin/false --disable-nls --without-libproxy \
  --without-stoken --without-libpcsclite --without-libpskc --disable-static
make -j2 libopenconnect.la
/path/to/geekoctunnel-probe --library "$PWD/.libs/libopenconnect.so.5"
```

`/bin/false` prevents the Linux library from running a default network script.
GeekOCTunnel supplies its own validated tunnel descriptor and privilege-separated
network helper. The Windows build additionally applies
`openconnect-windows-message-pipe.patch` for that descriptor transport.

The desktop release's third-party source archive contains the pinned upstream
OpenConnect tar, these patches, Fedora source RPMs matched against the distributed
Windows DLL hashes, Qt 6.8.3 source and Wintun redistribution materials.
`source-manifest.json` records source hashes and provenance. Extract the OpenConnect
tar and run `Build-OpenConnect.sh linux /path/to/openconnect /path/to/patches`.
Use `windows` inside the pinned MinGW image recorded in the manifest for Windows.
The script expects a fresh, unpatched source directory. Runtime output is under `.libs/`.

The application build accepts the resulting runtime through `OPENCONNECT_RUNTIME`;
see [the desktop build guide](../frontend/README.md). Modifying runtime libraries
requires rebuilding the Windows executable so its integrity manifest matches.
