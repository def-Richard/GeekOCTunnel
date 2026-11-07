#!/usr/bin/env bash
set -euo pipefail
[[ $(id -u) != 0 ]] || { echo 'Build and run the desktop as an ordinary user.' >&2; exit 1; }
: "${OPENCONNECT_RUNTIME:?Set OPENCONNECT_RUNTIME to the patched libopenconnect.so.5 file}"
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cmake -S "$repo/desktop" -B "$repo/build/desktop-linux" -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build "$repo/build/desktop-linux" --parallel 4
ctest --test-dir "$repo/build/desktop-linux" --output-on-failure
cd "$repo/desktop/frontend"
npm ci
npm test
npm run prepare:native -- "$repo/build/desktop-linux" "$OPENCONNECT_RUNTIME"
npm run tauri -- build --no-bundle -- --locked
cd src-tauri
cargo test --release --locked --features tauri/custom-protocol
