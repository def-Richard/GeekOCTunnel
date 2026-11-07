#!/usr/bin/env bash
set -euo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
export LC_ALL=C
check_only=false
if [[ ${1:-} == --help ]]; then
    echo 'Install: sudo bash Install-Linux.sh [bundle-directory]'
    echo 'Check without changes: bash Install-Linux.sh --check [bundle-directory]'
    exit 0
fi
if [[ ${1:-} == --check ]]; then check_only=true; shift; fi
[[ $# -le 1 ]] || { echo 'Too many arguments. Use --help.' >&2; exit 1; }
if ! $check_only; then
    [[ $(id -u) == 0 ]] || { echo '请用 sudo 安装，安装后以普通用户启动 / Install with sudo; launch without sudo.' >&2; exit 1; }
fi
bundle=$(realpath -- "${1:-$(dirname -- "${BASH_SOURCE[0]}")}")
policy=io.github.def-richard.geekoctunnel.connect.policy
binaries=(geekoctunnel native/geekoctunnel-core native/geekoctunnel-helper native/geekoctunnel-bridge native/libopenconnect.so.5)
for file in "${binaries[@]}" app-icon.svg "$policy"; do
    [[ -f "$bundle/$file" && ! -L "$bundle/$file" ]] || { echo "Missing bundle file: $file" >&2; exit 1; }
done
[[ $(uname -m) == x86_64 ]] || { echo 'This bundle requires Linux x86_64.' >&2; exit 1; }
. /etc/os-release
packages=(libxml2 libsecret-tools gnome-keyring libwebkit2gtk-4.1-0 libayatana-appindicator3-1
          liblz4-1 zlib1g iproute2 pkexec polkitd fonts-noto-cjk fontconfig at-spi2-core dbus-user-session)
case "${ID:-}:${VERSION_ID:-}" in
    debian:12) packages+=(libqt6core6 libqt6network6 libgtk-3-0 libgnutls30) ;;
    ubuntu:24.04) packages+=(libqt6core6t64 libqt6network6t64 libgtk-3-0t64 libgnutls30t64) ;;
    *) echo '此预览包目前只验证 Debian 12 / Ubuntu 24.04 x64；其他系统请使用匹配的构建。' >&2
       echo "Unsupported binary target: ${ID:-unknown} ${VERSION_ID:-unknown}. Do not substitute a different libxml2 ABI." >&2; exit 1 ;;
esac
missing=()
for package in "${packages[@]}"; do
    if [[ $(dpkg-query -W -f='${db:Status-Status}' "$package" 2>/dev/null || true) != installed ]]; then missing+=("$package"); fi
done
if ((${#missing[@]})); then
    printf '需要安装的运行依赖 / Missing runtime packages: %s\n' "${missing[*]}"
    if $check_only; then exit 1; fi
    apt-get -o APT::Update::Error-Mode=any update
    apt-get install -y --no-install-recommends --no-remove --no-upgrade "${missing[@]}"
fi
for file in "${binaries[@]}"; do
    if ! dependencies=$(ldd "$bundle/$file" 2>&1) || [[ "$dependencies" == *'not found'* ]]; then
        printf '运行库检查失败 / Unresolved runtime dependencies: %s\n%s\n' "$file" "$dependencies" >&2
        exit 1
    fi
done
for command in pkexec ip secret-tool fc-list; do command -v "$command" >/dev/null; done
[[ -n $(fc-list :lang=zh) ]] || { echo 'No Chinese font found after installing fonts-noto-cjk.' >&2; exit 1; }
dns_ready=false
if command -v resolvectl >/dev/null && timeout 5 resolvectl dns >/dev/null 2>&1; then dns_ready=true; fi
if ! $dns_ready; then
    echo '注意：systemd-resolved 尚未就绪，服务端下发 DNS 时将无法完成配置。' >&2
    echo 'DNS requires an active systemd-resolved service. This installer does not install/start it or change your DNS manager.' >&2
fi
echo '运行库检查通过 / Runtime libraries and secret-tool are ready.'
echo '记住密码还需要普通用户会话中的钥匙串已解锁；安装不会读取或保存密码。'
echo 'Use an unlocked desktop keyring and a desktop Polkit authentication agent. Log out/in if newly installed.'
if $check_only; then $dns_ready; exit; fi
prefix=/opt/geekoctunnel
[[ ! -L "$prefix" && ! -L "$prefix/native" ]] || { echo 'Refusing a symlink installation directory.' >&2; exit 1; }
for process in /proc/[0-9]*/exe; do
    executable=$(readlink "$process" 2>/dev/null || true)
    if [[ "$executable" == "$prefix/"* ]]; then
        echo '请先退出 GeekOCTunnel 并断开全部连接，再安装更新 / Close GeekOCTunnel before updating.' >&2
        exit 1
    fi
done
for file in geekoctunnel native/geekoctunnel-core native/geekoctunnel-helper native/geekoctunnel-bridge native/libopenconnect.so.5 app-icon.svg; do
    [[ ! -L "$prefix/$file" ]] || { echo "Refusing symlink destination: $file" >&2; exit 1; }
done
install -d -o root -g root -m 0755 "$prefix/native"
for file in "${binaries[@]}"; do
    install -o root -g root -m 0755 "$bundle/$file" "$prefix/$file"
done
install -o root -g root -m 0644 "$bundle/app-icon.svg" "$prefix/app-icon.svg"
install -d -m 0755 /usr/share/applications
install -d -m 0755 /usr/share/polkit-1/actions
install -o root -g root -m 0644 "$bundle/$policy" "/usr/share/polkit-1/actions/$policy"
cat > /usr/share/applications/io.github.def-richard.geekoctunnel.desktop <<'EOF'
[Desktop Entry]
Type=Application
Name=GeekOCTunnel
Comment=OpenConnect VPN client
Comment[zh_CN]=连接 OpenConnect VPN，管理分流网络与连接诊断
Exec=/opt/geekoctunnel/geekoctunnel
Icon=/opt/geekoctunnel/app-icon.svg
Terminal=false
Categories=Network;
StartupNotify=true
StartupWMClass=geekoctunnel
EOF
chmod 0644 /usr/share/applications/io.github.def-richard.geekoctunnel.desktop
echo 'Installed. Launch GeekOCTunnel from your desktop as a normal user; Polkit authorizes each connection.'
echo '安装完成：从应用菜单打开，或执行 /opt/geekoctunnel/geekoctunnel（不要加 sudo）。'
