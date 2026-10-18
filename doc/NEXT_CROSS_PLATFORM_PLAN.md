# GeekOCTunnel 下一代跨平台实施计划

状态：待按阶段实施；本次仅提交计划，不迁移代码。  
目标分支：`next`；审阅基线：`main@74e8bc22091fbe17b550fd7ac02667ab5617379d`。  
约定：下文“现状”来自该基线；“目标”与目录均是实施提案，不表示功能已经完成。

## 1. 已确定的方向

- Android 保留 Kotlin + Jetpack Compose、现有 `VpnService` 生命周期和发布渠道。
- Windows、Linux、macOS 共用一套精简 C++ + Qt Widgets 桌面界面及业务代码。
- VPN 协议继续使用 OpenConnect；统一的是配置、状态、认证、错误语义与测试契约。
- 优先降低发行包体积、安装占用、启动成本和常驻内存；桌面包不捆绑浏览器、WebEngine、Electron 或 JVM；Android 沿用系统运行时。
- 不为追求源码统一而重写 Android UI，也不要求 Android 改用桌面 C++ 业务层。
- 先完成桌面单隧道，再实现与现有 Android 约束一致的多隧道；不可把单隧道可用称为完整迁移完成。
- 浏览器型认证如确实需要，调用系统浏览器；其回调安全和服务端兼容性必须单独验证。

## 2. 基线与复用边界

- `OpenConnectEngine.kt` 已定义 `OpenConnectSession`、`VpnPlatform`、`TunnelFileDescriptor` 等边界，可借鉴其职责拆分。
- 这些接口属于 Kotlin/Android 现有实现，不是已经可供桌面链接的跨语言 ABI。
- `MultiTunnelRoutes.kt` 与 `Ipv4PacketTranslator.kt` 的纯 Kotlin 算法和测试可作为行为基准；C++ 需独立移植并验证。
- `AndroidMultiTunnel.kt` 依赖 Android `Os`、`poll`、`socketpair` 与 `VpnService`，不能直接作为三桌面平台后端。
- `NativeOpenConnectSession.kt` 包含 JNI、证书信任、User-Agent、reported OS 等适配；平台身份值不能未经验证全部照搬。
- 现有多隧道允许各服务器分配相同 VPN 客户端 IPv4 地址，但目标 IPv4 网段必须不重叠。
- 现有限制包含重叠网段、多条默认路由和多隧道 IPv6；桌面第一版显式保留限制并返回稳定错误。
- 多隧道中某会话断开时保留其路由、丢弃相关流量；不得回落到其他隧道或物理网络。
- DNS 延续“首个完成配置的服务器”的既有策略；不是 split DNS，不可在 UI 或文档中暗示已有按域名分流。
- 上述行为须在阶段 0 用现有测试和可复现实验固化，尤其是断线、重连、路由排序和清理边界。

## 3. 范围及完成定义

首版桌面范围：配置管理、连接/断开、认证交互、证书提示、状态与流量、脱敏诊断、托盘操作及系统网络恢复。

- 第一里程碑：三平台单隧道连通，系统网络配置及退出恢复通过测试。
- 第二里程碑：多隧道路由、地址转换、断线隔离和 DNS 策略与 Android 已确认基线一致。
- Android 只新增必要的契约适配、导入导出和一致性测试；不顺带改动签名、应用 ID 或现有发布行为。
- 暂不包含移动端 UI 重构、通用插件系统、内置浏览器、多隧道 IPv6、重叠子网路由和 split DNS。
- 原有服务端协议/认证支持范围先做清单；未经真实端点验证，不承诺 OpenConnect 所有协议均可用。
- 每个平台独立标记支持状态；某平台阻塞时不得用其他平台通过的结果代替其验收。

## 4. 桌面架构与安全边界

建议分为三个职责层：Qt Widgets GUI、非特权 Core 工作进程、最小权限 Network Helper。

- GUI：配置编辑、认证提示、状态展示、托盘；不持有管理员权限，不直接修改系统路由。
- Core：连接编排、OpenConnect 会话、重连、认证协调、路由计划、数据包转发与统计；不依赖 Qt Widgets/Gui，不初始化图形引擎。
- Helper：创建/配置隧道设备、安装/撤销路由、配置/恢复 DNS，按平台以服务或受控提权方式运行。
- GUI 关闭、托盘退出和 Core 退出是三个不同动作；定义是否保持连接，不能由窗口析构隐式决定。
- 第一版允许 GUI 与 Core 共用基础库，但保留独立进程边界；需以测量结果约束额外进程开销。
- OpenConnect 的接入优先使用锁定版本的库 API；原型必须证明会话取消、回调线程、数据 I/O 和生命周期可控。
- 不能假定 Linux 文件描述符、macOS 设备句柄和 Windows HANDLE/Wintun 会话互相等价。
- 平台抽象使用能力接口与所有权明确的资源对象，避免把 Android 的整数 fd 契约原样复制到 Windows。
- 如库 API 不支持所需自定义数据路径，先形成原型结论与最小变更提案，再决定适配层；不得默默增加常驻代理。
- Helper 不接收任意命令、脚本路径或 shell 片段；只接受经过校验的声明式网络操作。

### 4.1 本地 IPC

- GUI/Core 与 Core/Helper 分别定义有版本的请求、响应和事件；包含 request ID、session ID、generation 和长度上限。
- Unix socket/Windows named pipe 限定到授权用户和服务账户；验证对端身份，禁止默认开放本地 TCP 控制端口。
- IPC 按连接握手协商版本与能力，拒绝不兼容版本；每条网络请求校验 CIDR、接口所有权、地址族和资源额度。
- 提权由操作系统授权流程完成；Helper 不接受 GUI 自报“已授权”作为信任依据。
- 认证数据只在必需的受保护通道和短生命周期内存中传递；禁入日志、命令行参数、环境变量和故障包。
- 对超时、重复请求、断连和事件乱序设定幂等行为；旧会话回复不能改变新会话状态。

### 4.2 网络变更事务与清理

- 连接前生成计划并记录本次拥有的接口、路由和 DNS 修改；应用成功后才发布 `connected`。
- 中途失败按逆序回滚；只撤销自己创建或仍属于本次会话的配置，不覆盖用户及其他 VPN 后来的修改。
- Core 崩溃、Helper 重启、系统休眠/唤醒和网络切换均需可恢复；使用可识别的资源所有权和恢复记录。
- 单个多隧道成员断开时保留其受保护路由并丢弃流量；整个组显式停止后清理组资源。
- Helper 丢失时如何维持丢弃语义必须验证；无法保证时阻止发布，不能把 UI 的“断开”当作防泄漏证据。
- 这里的目标是既定路由范围的断线隔离，不宣称具备覆盖所有系统流量的全局 kill switch。

## 5. 三平台网络后端验证

### Linux

- 原型验证 `/dev/net/tun`、路由变更、接口生命周期及最小权限 Helper 的安装/授权方案。
- 明确支持的发行版、桌面会话和 DNS 管理器；为 systemd-resolved 等环境提供经过测试的适配路径。
- 不直接覆盖 `/etc/resolv.conf` 作为通用方案；不与 NetworkManager 或其他 VPN 无条件抢占配置。
- 验证非 root GUI、发行包卸载清理、权限被拒、已有默认路由与并行 VPN 情况。

### macOS

- 验证 utun 与锁定 OpenConnect 构建的实际集成路径、路由/DNS 系统接口和受支持的 Helper 安装方式。
- 明确 Developer ID、签名、公证及所需 entitlement；不能预设开发机上能运行就能对外分发。
- 如实际方案需要 Network Extension 或额外授权，先记录分发限制与成本，经决策后再实现。
- 测试休眠恢复、Wi-Fi 切换、DNS 恢复和用户拒绝 Helper 授权；Intel/Apple Silicon 各自验证。

### Windows

- 首先确认目标 OpenConnect 版本的驱动、构建工具链和数据路径；Wintun/TAP 仅为待验证候选。
- 分别验证虚拟网卡创建、驱动签名/部署、HANDLE 所有权、异步 I/O、取消和服务停止。
- 不把 POSIX `socketpair` 或 fd 注入方案视作 Windows 已解决；先做最小收发和取消原型。
- 使用受 ACL 保护的服务/管道和系统网络 API；验证 UAC、普通用户、多用户及服务重启。
- 验证 IPv4/IPv6 路由、接口优先级、DNS 撤销及驱动卸载；确认签名驱动的再分发许可和升级策略。

## 6. 单隧道与多隧道数据路径

### 阶段 A：单隧道

- 一个 OpenConnect 会话对应一个明确归属的平台隧道端点；先跑通认证、数据收发、取消和网络恢复。
- 服务端下发地址、路由和 DNS 先解析为规范化计划，再交给平台后端执行。
- 验证 MTU、默认路由、服务器出口绕行、防止 VPN 控制连接被自身路由捕获。
- 单隧道 IPv6 按既有行为与各平台能力单独测试；不得据此推断多隧道 IPv6 已实现。

### 阶段 B：多隧道

- 以 Android 既有路由选择与 IPv4 地址转换语义为基准，在 C++ 中实现等价算法。
- 推荐一个受管理的系统入口聚合多个会话；最终数据注入方式由三平台原型结果确定。
- 先离线校验网段重叠、默认路由和地址族约束，再创建资源；冲突错误需指出相关配置和网段。
- 明确双向源/目标地址改写及校验和处理，覆盖 TCP、UDP、ICMP、分片、截断和非法数据包。
- 不把全部边界包强行称作现有支持；以基线测试确认每类包的转发或拒绝行为，再迁移。
- 会话代次、队列和路由归属绑定；重连后不误投旧包，断线队列有界且不无限缓存。
- DNS 保持“首个完成配置的服务器”策略，并测试完成顺序变化；如需改为按用户排序选择，必须另列行为变更，不作为等价迁移悄然引入。会话失败时不私自改变 DNS 策略。
- 与 Android 相同的限制须同时出现在导入校验、连接校验、错误契约和用户提示中。

## 7. 跨平台共享契约

契约以文档、JSON Schema 和测试夹具共享；Android 与 C++ 分别实现解析、迁移和行为适配。

### 7.1 配置与密钥

- 配置根对象包含 `schemaVersion`、稳定 profile ID、配置列表和连接组；字段、默认值、排序语义须明确。
- profile 覆盖服务端、协议、用户名提示、认证选项、路由策略、MTU 和必要的平台覆盖项。
- User-Agent/reported OS 等平台相关值显式记录默认规则；不要为了统一字符串破坏服务端兼容性。
- 明确定义未知字段、未知枚举、缺失字段和更高版本行为；不静默丢字段后覆盖原文件。
- 迁移是可重复的版本步骤：校验、备份、转换、校验、原子替换；失败保留原文件。
- JSON 不存明文密码、cookie、token、私钥、私钥口令或系统密钥；只存本机 credential reference。
- Android 延续现有安全存储；桌面分别验证 Windows Credential Manager、macOS Keychain、Linux Secret Service。
- Linux 无安全存储时允许会话内输入，并明确“未保存”；禁止悄悄退回明文文件。
- 可移植导出移除 credential reference、运行态和设备绑定路径；导入后重新选择本机证书/密钥并登录。
- 配置可移植不等于密钥可移植；用户名、服务器和路由仍可能敏感，导出前展示包含内容。
- 证书指纹与信任决定不得由导入文件静默变成可信；导入后按明确策略复核。

### 7.2 状态、认证与错误

- 状态机至少定义 `idle → preparing → authenticating → connecting → connected`，以及 `reconnecting`、`disconnecting`、`failed`。
- 每个会话具有 ID、generation、事件序号、时间戳与断线原因；组状态由成员状态和路由可用性推导。
- 认证 challenge 含 challenge ID、会话代次、字段类型、是否秘密、可取消性和期限；回复只消费一次。
- 首版认证以已确认的用户名/密码、SSL Group 和现有表单行为为范围；OTP、复杂动态表单、客户端证书及浏览器 SSO 均须先确认现有支持和服务端测试证据，再决定是否纳入，不能当作基线已有能力；未知类型明确拒绝。
- 证书异常提示包含端点、失败原因和可核验信息；不能提供默认跳过全部 TLS 校验的快捷路径。
- 系统浏览器认证如启用，必须校验回调关联、state/适用的 PKCE，并防止其他本地进程劫持结果。
- 错误对象包含稳定 `code`、category、retryable、userAction、脱敏 detail、平台原因码与关联 ID。
- 至少区分认证拒绝、用户取消、证书失败、网络超时、路由冲突、平台权限、驱动缺失和 IPC 不兼容。
- UI 文案可以本地化，日志与测试使用稳定错误码；自动重试只针对允许重试的错误并有退避上限。

## 8. 建议目录（保留现有 Android 工程）

```text
app/                              # 原 Android Kotlin + Compose 工程
native/                           # 保留现有 Android 原生依赖及锁定信息
desktop/
  CMakeLists.txt
  ui/                             # Qt Widgets、托盘、认证视图
  core/                           # 会话编排、配置适配、OpenConnect 封装
  routing/                        # C++ IPv4 算法与路由计划
  ipc/                            # 类型、编解码、权限及连接管理
  platform/{linux,macos,windows}/  # 平台网络、凭据、系统集成
  helper/                         # 特权服务入口与有限操作执行器
  tests/                          # 单测、IPC 与平台集成测试
contracts/
  config/                         # 版本化 JSON Schema、迁移规范
  events/                         # 状态、认证、错误与 IPC 约定
  fixtures/                       # Kotlin/C++ 共用 golden 输入输出
packaging/{linux,macos,windows}/
doc/NEXT_CROSS_PLATFORM_PLAN.md
```

新增目录按阶段引入；本次提交只添加计划文件。不要预先制造未使用的空框架或改动 Android 构建入口。

## 9. 测试、体积与内存预算

- 阶段 0 记录现有 Android 测试、调试构建及可行的发布构建基线；签名密钥不复制到新工具链。
- 从现有算法提取去敏 golden fixtures：路由排序/冲突、同客户端 IP、双向转换、校验和及断线行为。
- Kotlin 与 C++ 对相同输入产生相同输出或相同拒绝码；禁止各维护一套互不校对的“预期值”。
- 离线包测试之外，使用授权测试 VPN 做端到端验证：重连、取消、凭据错误、证书错误、路由/DNS 泄漏和恢复。
- 用故障注入覆盖进程被杀、Helper 失联、权限拒绝、半完成网络配置、休眠与物理网络切换。
- 建立每平台/架构的最小 Qt Widgets + OpenConnect 原型；阶段 0 实测后在预算文件中填写目标，禁止编造 MB 数字。
- 测量压缩安装包、安装后大小、冷/热启动、空闲、单隧道、多隧道稳定运行及连接峰值。分别测 UI 打开、仅隐藏到托盘、UI 真正退出后 Core/Helper 保持连接，禁止把隐藏窗口等同释放 UI 内存。
- 内存统计必须包含 GUI、Core、Helper 全进程树；另记驱动/系统服务影响与统计口径。
- Linux 记录 RSS/PSS，Windows 记录私有工作集/提交量，macOS 记录适用的 footprint；不同口径不可直接排名。
- 固定 OS、CPU 架构、Release 构建、依赖版本、测试流量和采样时段，报告多次测量的中位值与峰值。
- 在阶段 0 批准绝对预算与相对回归阈值，后续 CI 超标须解释或阻止发布；不能靠隐藏 Helper 内存达标。
- 最小依赖原则：Qt Core/Gui/Widgets 为基础，其他模块逐项证明必要；不包含 WebEngine/QML/开发工具和无用插件。

## 10. 构建、发行与许可证

- CMake 管理桌面工程；依赖固定版本、来源和校验值，记录 OpenConnect、TLS 库、Qt、驱动及运行库。
- 优先复用 `native/source-lock.json` 已记录的版本依据，但桌面目标和配置须独立验证，不擅改 Android 锁定结果。
- 每 OS/架构独立产物和测试矩阵；阶段 0 决定首发架构，未完成测试的架构不标记为支持。
- Windows 安装器处理服务、驱动与运行库；macOS 独立架构包优先，是否提供 universal 包由体积测量决定。
- Linux 明确发行版/ABI 基线与依赖策略，比较系统动态库和自包含包的体积、兼容性及维护成本。
- Release 启用经过验证的优化/裁剪；调试符号拆分存档，保持 build ID 可追溯，保留必要崩溃诊断能力。
- 发行前生成依赖清单/SBOM、许可文本和来源说明；验证签名、公证、安装、升级、卸载与回滚。
- 对选定 Qt 模块、OpenConnect、TLS 库和驱动逐项核验许可证、链接方式与再分发义务。
- Qt 动态链接不是自动满足所有许可义务的保证；裁剪、静态链接、LTO 和驱动打包均需单独检查。
- 官方资料作为核验入口，最终以实际锁定版本及随包许可证为准；本计划不作法律合规保证。

## 11. Codex 分阶段任务与验收门槛

每一阶段使用独立小提交/PR，附变更范围、命令、测试结果、实测数据和已知限制；不跨过失败门槛继续扩大迁移。

1. **P0：固化基线与可行性。** 清点 Android 行为/认证/测试；建立契约草案和共享夹具；完成三平台设备、库 I/O、权限及 DNS 最小原型；填入资源预算和支持矩阵。
   - 验收：Windows 句柄/驱动、macOS 分发/权限、Linux DNS 路径都有实验结果；未解决项有明确阻塞及下一步，不能仅写“支持”。
2. **P1：契约与桌面骨架。** 引入 CMake、最小 Widgets UI、版本化配置、状态/错误模型、模拟会话和受限 IPC。
   - 验收：三平台构建；配置迁移/拒绝策略与 Kotlin golden 测试通过；GUI 无特权；无明文秘密持久化。
3. **P2：单隧道端到端。** 接入真实 OpenConnect、认证、Helper、平台路由/DNS 与系统凭据存储。
   - 验收：各支持平台连接、取消、失败回滚、崩溃恢复、退出清理及资源预算通过；如需浏览器认证单列测试。
4. **P3：多隧道行为对齐。** 移植路由/IPv4 转换，接入多会话聚合及断线隔离。
   - 验收：同客户端 IP + 不重叠网段连通；重叠/多默认/多 IPv6 被拒；断线保路由且不串流；DNS 行为一致。
5. **P4：Android 契约适配。** 在保留 Kotlin/Compose 的前提下实现必要导入导出、状态映射与跨语言回归。
   - 验收：旧配置可继续使用；现有 Android 功能/测试、构建、签名和升级路径无回归；禁止因桌面缺项删减 Android 能力。
6. **P5：发行候选。** 完成安装器、签名/公证、许可材料、脱敏诊断、平台测试矩阵和测量报告。
   - 验收：干净机器安装/卸载/升级/回滚验证通过；无无用浏览器依赖；所有声称支持的功能有对应测试证据。

## 12. 回滚与主要风险

- `main` 继续维护稳定 Android；`next` 按阶段推进，未经验收不替换稳定发布。
- 配置升级先备份；旧版本无法理解新 schema 时明确拒绝或恢复备份，不静默降级丢失配置。
- 桌面网络操作可逆且可诊断；升级失败保留可恢复的前版产物和配置，先清理本版拥有的系统资源。
- 高风险：Windows 数据路径/驱动、macOS 分发权限、平台 DNS 差异、多隧道丢包与泄漏、认证兼容性、许可和体积预算。
- 风险收敛顺序：平台原型 → 离线算法等价 → 单隧道 → 多隧道故障测试 → 安装发行；不要先大规模搬 UI 再验证底层。
- 开始实现前仍需决定：首发 OS/架构最低版本、真实测试服务器、允许的认证集合、发行渠道及阶段 0 实测后的资源预算。

## 13. 源码与核验入口

源码链接固定到审阅基线；外部文档是核验入口，不代表已经完成依赖升级或平台兼容测试。

- [OpenConnectEngine.kt：现有会话与平台边界](https://github.com/def-Richard/GeekOCTunnel/blob/74e8bc22091fbe17b550fd7ac02667ab5617379d/app/src/main/java/com/richard/tunnelkeeper/vpn/OpenConnectEngine.kt)
- [NativeOpenConnectSession.kt：JNI 与认证/平台行为](https://github.com/def-Richard/GeekOCTunnel/blob/74e8bc22091fbe17b550fd7ac02667ab5617379d/app/src/main/java/com/richard/tunnelkeeper/vpn/NativeOpenConnectSession.kt)
- [AndroidMultiTunnel.kt：Android 多隧道实现](https://github.com/def-Richard/GeekOCTunnel/blob/74e8bc22091fbe17b550fd7ac02667ab5617379d/app/src/main/java/com/richard/tunnelkeeper/vpn/AndroidMultiTunnel.kt)
- [MultiTunnelRoutes.kt](https://github.com/def-Richard/GeekOCTunnel/blob/74e8bc22091fbe17b550fd7ac02667ab5617379d/app/src/main/java/com/richard/tunnelkeeper/vpn/MultiTunnelRoutes.kt) / [Ipv4PacketTranslator.kt](https://github.com/def-Richard/GeekOCTunnel/blob/74e8bc22091fbe17b550fd7ac02667ab5617379d/app/src/main/java/com/richard/tunnelkeeper/vpn/Ipv4PacketTranslator.kt)：算法基线
- [SecureCredentialStore.kt](https://github.com/def-Richard/GeekOCTunnel/blob/74e8bc22091fbe17b550fd7ac02667ab5617379d/app/src/main/java/com/richard/tunnelkeeper/data/SecureCredentialStore.kt) / [ConnectionProfile.kt](https://github.com/def-Richard/GeekOCTunnel/blob/74e8bc22091fbe17b550fd7ac02667ab5617379d/app/src/main/java/com/richard/tunnelkeeper/model/ConnectionProfile.kt)：配置与凭据
- [native/source-lock.json](https://github.com/def-Richard/GeekOCTunnel/blob/74e8bc22091fbe17b550fd7ac02667ab5617379d/native/source-lock.json)：原生依赖锁定
- [OpenConnect 官方说明](https://www.infradead.org/openconnect/) / [网络配置与 vpnc-script 边界](https://www.infradead.org/openconnect/vpnc-script.html)：协议库与系统网络配置职责
- [Qt 许可说明](https://doc.qt.io/qt-6/licensing.html) / [Wintun 官方集成与再分发说明](https://www.wintun.net/)：实际选型和锁定版本需进一步核验
