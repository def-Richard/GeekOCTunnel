#!/usr/bin/env bash
set -euo pipefail
platform=${1:?Usage: bash Build-OpenConnect.sh linux-or-windows /path/to/extracted-openconnect /path/to/patches}
source=$(realpath -- "${2:?Missing extracted source directory}")
patches=$(realpath -- "${3:?Missing patch directory}")
[[ "$platform" == linux || "$platform" == windows ]] || { echo 'Choose linux or windows.' >&2; exit 1; }
cd "$source"
patch -p1 --forward < "$patches/openconnect-nonblocking-tls.patch"
if [[ "$platform" == windows ]]; then patch -p1 --forward < "$patches/openconnect-windows-message-pipe.patch"; fi
printf '9.12\n' > .tarball-version
./autogen.sh
options=(--disable-nls --without-libproxy --without-stoken --without-libpcsclite --without-libpskc --disable-static)
if [[ "$platform" == windows ]]; then
    mingw64-configure --with-vpnc-script=vpnc-script.js --disable-nsis-installer "${options[@]}"
else
    ./configure --with-vpnc-script=/bin/false "${options[@]}"
fi
make -j2 libopenconnect.la
