# GeekOCTunnel

**轻量、无广告的 Android OpenConnect VPN 客户端，专注 ocserv 与 Cisco AnyConnect 兼容网关。**

在手机上连接自建或组织提供的 VPN，访问受保护的内网资源。保留常用服务器，一键连接，并通过清晰的连接日志查看网关、地址、DNS 和路由。

[下载最新 APK](https://github.com/def-Richard/GeekOCTunnel/releases/latest) · [使用与构建](#从源码构建) · [反馈问题](https://github.com/def-Richard/GeekOCTunnel/issues) · [隐私说明](PRIVACY.md)

> 本项目是客户端，不提供 VPN 服务器、账号或订阅服务，也不是 Cisco 官方产品。你需要自己的兼容网关和登录凭据。当前发布为 **debug 签名的早期测试版本**，不代表生产级安全认证。

## 能做什么

| 功能 | 说明 |
| --- | --- |
| 多服务器并行 | 选择连接组合，同时连接多个网关，按服务器下发的 IPv4 目标网段分流 |
| 网关认证 | 从服务器读取 SSL Group，使用用户名和密码登录；切换 Group 时刷新认证表单 |
| 记住登录信息 | 可选保存账号、密码和 Group，使用 Android Keystore 支持的 AES-GCM 加密 |
| 快捷连接 | Android 快速设置磁贴可连接、断开；需要授权或输入时打开应用 |
| 全隧道与分流 | 按网关下发的路由、DNS、MTU 建立 Android VPN |
| 连接诊断 | 在应用内查看连接状态、VPN 地址、包含/排除路由与 IPv6 策略 |
| 已连接配置汇总 | 状态下方显示全部成功连接的配置名，没有成功连接时留空；较长名称可横向滑动查看 |

适合已经部署 **ocserv**，或使用兼容 **AnyConnect** 网关、希望获得简洁连接体验的用户。当前只面向这一协议族；不支持 WireGuard、OpenVPN，也不承诺支持浏览器 SSO、复杂 MFA 或所有企业认证流程。

## 安装与开始使用

**设备要求：Android 8.0（API 26）及以上，ARM64（`arm64-v8a`）。**

1. 从 [Releases](https://github.com/def-Richard/GeekOCTunnel/releases) 下载 `GeekOCTunnel-v…-debug.apk`，按 Android 提示允许安装。
2. 打开应用，新增服务器，例如 `https://vpn.example.com:443`。
3. 点击连接，授权 Android VPN；选择服务器提供的 Group，输入用户名和密码。
4. 按需勾选记住登录信息。连接后可通过应用或快速设置磁贴断开。

### 同时连接多个服务器

添加各服务器配置后，点击「并行连接」，勾选需要的服务器，再点击「连接所选」。应用分别完成账号和 SSL Group 认证，收齐配置后只建立一个 Android VPN 接口。单个配置仍可通过主开关直接连接。

- 连接后，在配置下拉菜单中查看各服务器状态、单独断开或重连；「全部断开」结束整个组合。
- 快速设置磁贴显示汇总状态，点击可全部断开；再次连接会恢复最近保存的组合，需要输入凭据时打开应用。
- 多连接支持互不重叠的 IPv4 目标网段，转发 TCP、UDP 和 ICMP。不同网关分配相同的 VPN 客户端地址也可以使用。
- 重叠网段、多个默认路由、IPv6 多连接会被明确拒绝；单服务器保留全隧道和 IPv6 支持。
- 断开的站点保留路由并丢弃对应流量，避免误送到其他服务器或公网；其余站点保持连接。重连时如路由改变或 MTU 低于共享接口，需重新连接整个组合。
- Android 重建 VPN 接口会中断已有 TCP 连接，因此运行期间只允许重连当前组合内的站点；添加新的站点需先全部断开，再选择组合。
- 若某站点在首次取得路由前失败或被取消，其他站点可正常连接；重试这个尚未安装路由的站点时，需要全部断开并重新连接组合。
- DNS 沿用首个完成配置的服务器；未下发 DNS 时使用系统默认。当前没有按域名选择不同服务器的 DNS 分流。

Android 同一时间通常只允许一个 VPN 服务运行，连接本应用可能替换其他 VPN。若更新提示签名不一致，需要使用相同签名的安装包；卸载旧版会删除本地配置和保存的凭据。

### 路由与证书

- 默认校验服务器证书。每个配置可单独开启“忽略证书错误”，默认关闭；开启会跳过证书链、主机名、有效期等验证，仅应用于你明确了解风险的环境。
- 分流时仅安装网关提供的 VPN 路由；DNS 使用网关配置，DNS 请求仍按路由选择网络。
- IPv4 分流且服务器不提供 IPv6 配置时，IPv6 可走原网络；IPv4 全隧道时阻断 IPv6，避免绕过 VPN。
- 日志包含网络拓扑信息。反馈问题前请删除真实服务器地址、内网地址、用户名及其他敏感内容。

## 从源码构建

应用使用 Kotlin、Jetpack Compose、Android `VpnService` 和 OpenConnect 官方 Java/JNI 绑定。仓库已经包含原生库，普通 APK 构建不需要重新编译 OpenConnect。

### 环境

| 工具 | 版本 |
| --- | --- |
| PowerShell | 7+ |
| JDK | 17 |
| Gradle | 8.9（仓库未附带 Gradle Wrapper） |
| Android SDK | Platform 35，安装对应 Build Tools |

发布脚本要求以下工具目录布局；目录位置由你自行选择：

```text
<toolchain-root>/
├── jdk-17/bin/java.exe
├── gradle-8.9/lib/gradle-launcher-8.9.jar
└── android-sdk/
    ├── platforms/android-35/
    └── build-tools/<version>/
```

```powershell
# 先将 ANDROID_TOOLCHAIN_ROOT 环境变量设为你的工具目录。
# 基准必须是已实际分发、可信且单独保留的旧 APK，不要用重新构建的 APK 代替。
& .\scripts\Publish-Apk.ps1 -ToolchainRoot $env:ANDROID_TOOLCHAIN_ROOT -PreviousApk 'D:\apk-baseline\previous-debug.apk'
```

每个交付 APK 均通过此脚本生成：运行单元测试、构建、核对包名和版本、校验原生库及 16 KB 对齐、计算 SHA-256，归档成功后才更新 `version.properties`。每次成功会递增 patch 版本和 versionCode，产物保存在 `releases/apk/`，默认保留最近 10 个版本。该目录不提交到 Git，安装包通过 GitHub Release 分发。

发布前还会使用 Android Build Tools 的 `apksigner` 验证新旧 APK，要求两者包名为 `com.richard.tunnelkeeper`、各有且仅有一个相同的 SHA-256 签名证书，并且新包的 versionCode 更大。缺少基准、工具、有效签名或证书不一致时，脚本会停止，不归档新 APK、不更新版本、不清理旧归档。基准不要放在 `app/build/`（构建会覆盖）或 `releases/apk/`（旧归档会轮换清理），应单独保留。

这项检查继续使用现有 debug 签名，不生成或更换密钥，也不支持签名轮换。新电脑自动生成的 debug 密钥可能与旧包不同；遇到不一致时应找回原构建环境的签名密钥，不要通过卸载旧应用解决，否则会丢失配置和保存的凭据。没有可信旧 APK 时应先找回实际分发的安装包，不能把新构建的包当作基准绕过检查。

可运行 `pwsh -NoProfile -File .\scripts\Test-ApkUpgradeGuard.ps1` 检查发布门禁逻辑（无需 Android SDK，使用模拟工具输出）。它不替代真实 APK 签名校验或实机覆盖升级测试。相同证书、包名及递增版本也不保证数据迁移、VPN 或快速设置行为正确；分发前仍需在保留数据的旧版安装上实测。ARM64 与 x86_64 可使用同一个可信基准检查签名连续性，但不能据此认定跨架构安装兼容。

### 重建原生库

需要 Debian WSL、原生构建依赖及下载访问权限。OpenConnect 固定在 9.12 对应提交 `f17fe20d337b400b476a73326de642a9f63b59c8`，NDK 为 r28c；详细来源和校验值见 [source-lock.json](native/source-lock.json)。

```powershell
& .\scripts\install-openconnect-wsl-prerequisites.ps1
& .\scripts\Sync-OpenConnectNativeSources.ps1
& .\scripts\Build-OpenConnectAndroidNative.ps1
```

同步和构建脚本默认读取 `HTTPS_PROXY`，也可显式传入 `-ProxyUrl`。若同步时自定义 `-CacheRoot`，构建时使用相同目录。依赖安装脚本可单独传入 `-ProxyUrl`，在默认 WSL 发行版执行，请先确保其为你的构建环境。

默认 APK 仅包含 ARM64。模拟器验证可执行发布脚本的 `-Abi x86_64 -BuildDeviceTests`，产物文件名包含 `x86_64`，独立递增版本，默认不会打进手机 APK。构建清单和现有 APK 的原生库均记录了 16 KB 页对齐信息。

## 项目结构

```text
app/src/main/           Android 界面、配置、凭据存储与 VPN 生命周期
app/src/test/           认证、路由、凭据及快捷磁贴等单元测试
app/src/androidTest/    两个真实网关的认证、分流和独立断线验证（读取应用加密保存的凭据）
app/src/main/jniLibs/   OpenConnect 原生库
native/                固定源码信息、构建清单和第三方许可证
scripts/               APK 发布、原生库构建和校验脚本
```

## 项目状态与反馈

当前版本为早期测试版，采用 debug 签名；尚未建立正式 release 签名发行流程。构建或单元测试通过不等于你的网关、手机或快速设置界面已经过实机验证。

提交 Issue 时请注明应用版本、Android 版本、手机架构、网关类型、复现步骤及脱敏日志。不要上传账号密码、Cookie、私钥、内部域名或未打码截图。安全问题请参阅 [SECURITY.md](SECURITY.md)。

## 第三方软件与授权

感谢 [OpenConnect](https://www.infradead.org/openconnect/) 及其依赖项目。第三方软件继续遵循各自许可证，组件清单、许可证和源码获取方式见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

本仓库目前未为项目原创代码指定额外的开源许可证；公开源码不等于授予任意再分发或商业使用许可。第三方代码的既有许可不受此说明影响。
