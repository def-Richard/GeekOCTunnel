import { invoke, isTauri } from '@tauri-apps/api/core';
import { connection, running, stateNames, connectSelected, renderConnection, setupSessions } from './sessions';
import { createIcons, Shield, Server, Plus, Download, Upload, LockKeyhole, ChevronRight, Power, Pencil, Trash2, ArrowDown, ArrowUp, Clock3, Info, RefreshCw, Settings2 } from 'lucide';
import './style.css';

document.querySelector('#app').innerHTML = `
<div class="shell">
  <aside class="sidebar" aria-label="服务器管理">
    <div class="brand"><img class="brand-mark" src="/app-icon.svg" alt=""><div><strong>GeekOCTunnel</strong><small>OPENCONNECT CLIENT</small></div></div>
    <section aria-labelledby="servers-label">
      <div class="section-line"><h2 class="nav-label" id="servers-label">服务器 <span id="count">0</span></h2><button id="add" class="ghost" title="添加服务器" aria-label="添加服务器"><i data-lucide="plus"></i></button></div>
      <div id="servers" class="server-list"></div>
      <p id="empty-list" class="sidebar-empty">还没有服务器。<br>添加地址，整理你的连接。</p>
    </section>
    <div class="sidebar-bottom">
      <button id="settings" class="ghost"><i data-lucide="settings-2"></i>设置</button>
      <button id="import" class="ghost"><i data-lucide="download"></i>导入配置</button>
      <button id="export" class="ghost"><i data-lucide="upload"></i>导出配置</button>
      <div class="local-note"><i data-lucide="lock-keyhole"></i>配置仅保存在本机</div>
    </div>
  </aside>
  <main>
    <header class="topline"><div class="breadcrumb">工作空间 <i data-lucide="chevron-right"></i><span id="active-count">已连接 0 台</span></div><button id="disconnect-all" class="ghost" disabled>断开全部</button></header>
    <div id="error" class="banner" role="alert" hidden><span id="error-text"></span><button id="reload" class="ghost" aria-label="重新加载配置" title="重新加载配置"><i data-lucide="refresh-cw"></i></button></div>
    <div id="certificate-warning" class="banner" role="status" hidden>全局证书校验已关闭：无法验证服务器身份。此设置对新连接生效。</div>
    <div class="page-heading"><h1>连接，从这里开始。</h1><p>管理你的服务器，访问需要的网络。</p></div>
    <section class="connection" aria-label="当前连接">
      <div class="connection-head"><span class="state" id="state" role="status">未连接</span><small>OPENCONNECT</small></div>
      <div class="connect-area">
        <div class="power-emblem" aria-hidden="true"><i data-lucide="power"></i></div>
        <div class="connect-copy"><h2 id="selected-name">添加第一个服务器</h2><p id="selected-address" class="address">准备好服务器地址，即可保存配置。</p>
          <div class="connect-actions"><button id="connect" class="primary" disabled><i data-lucide="power"></i><span>连接</span></button><button id="edit" class="ghost"><i data-lucide="plus"></i><span>添加服务器</span></button></div>
        </div>
      </div>
      <div class="connection-notice"><i data-lucide="info"></i><span>IPv4 分流 · 可同时连接多个服务器；路由冲突时会拒绝新连接。</span></div>
      <div class="metrics" aria-label="连接统计">
        <div class="metric"><div class="metric-label"><i data-lucide="clock-3"></i>连接时长</div><div class="metric-value" id="duration">—</div></div>
        <div class="metric"><div class="metric-label"><i data-lucide="arrow-down"></i>已接收</div><div class="metric-value" id="received">—</div></div>
        <div class="metric"><div class="metric-label"><i data-lucide="arrow-up"></i>已发送</div><div class="metric-value" id="sent">—</div></div>
      </div>
    </section>
    <section class="network-panel" aria-labelledby="network-title">
      <div class="detail-heading"><h2 id="network-title">连接信息</h2><span id="network-state" class="muted">尚未获取</span></div>
      <dl class="network-grid"><dt>隧道 IPv4</dt><dd id="tunnel-address">—</dd><dt>VPN 网关 IP</dt><dd id="tunnel-gateway">—</dd><dt>传输方式</dt><dd id="tunnel-transport">—</dd><dt>MTU</dt><dd id="tunnel-mtu">—</dd><dt>下发 DNS</dt><dd id="tunnel-dns">连接后显示是否下发 DNS。</dd></dl>
      <details class="route-details" open><summary>隧道路由 <span id="route-count">0</span></summary><div id="tunnel-routes" class="route-list">连接后显示路由网段。</div></details>
    </section>
    <section class="log-panel" aria-labelledby="log-title">
      <div class="detail-heading"><h2 id="log-title">连接日志 <span id="log-count" class="muted">0 / 200</span></h2><button id="clear-log" class="ghost">清空</button></div>
      <p class="log-help">每台服务器保留最近 200 条事件，极简模式下继续记录。不记录密码、令牌或 Cookie，不写入磁盘。</p>
      <p id="log-empty" class="muted">连接后，认证、证书、网络配置和重连事件会显示在这里。</p>
      <div id="session-log" class="session-log" role="log" aria-live="polite" aria-relevant="additions" aria-label="当前服务器连接日志"></div>
    </section>
    <section aria-labelledby="details-title">
      <div class="detail-heading"><h2 id="details-title">服务器详情</h2><button id="remove" class="ghost danger" disabled><i data-lucide="trash-2"></i>删除</button></div>
      <dl><dt>服务器地址</dt><dd id="detail-address">尚未选择</dd><dt>连接协议</dt><dd>AnyConnect / OpenConnect</dd><dt>证书验证</dt><dd id="certificate-policy">严格校验</dd></dl>
    </section>
    <details><summary>关于此开发版本</summary><div class="diagnostics"><p>连接使用 OpenConnect。密码通过本机私有进程管道传递；勾选记住后保存在系统凭据库，不进入配置导出或日志。</p><p>临时网络故障会退避重连；认证拒绝、证书问题、路由冲突及主动断开不会自动重试。最多同时连接 8 个服务器。</p><p>不支持全隧道、IPv6 和浏览器 SSO。隐藏到托盘后暂停界面定时刷新，连接仍保持运行。</p><p id="runtime-note"></p></div></details>
    <footer class="statusline"><span id="storage-status" role="status">正在读取本地配置…</span><label class="close-setting">关闭窗口时 <select id="close-behavior" aria-label="关闭窗口时"><option value="ask">询问</option><option value="tray">最小化到托盘</option><option value="exit">断开并退出</option></select></label></footer>
  </main>
</div>
<input type="file" id="import-file" accept="application/json,.json" hidden>
<dialog id="editor" aria-labelledby="editor-title"><form id="profile-form">
  <h2 id="editor-title">添加服务器</h2><p>使用管理员提供的 HTTPS 地址。</p>
  <label for="profile-name">名称</label><input id="profile-name" name="name" maxlength="128" placeholder="例如：工作网络" required autocomplete="off">
  <label for="profile-server">服务器地址</label><input id="profile-server" name="server" maxlength="2048" placeholder="https://vpn.example.com:443" required spellcheck="false" autocomplete="off">
  <label class="check-label"><input id="profile-udp" type="checkbox">允许 UDP / DTLS</label><p class="muted">需要服务器和网络支持。无法建立 DTLS 时自动使用 TCP。</p>
  <div id="form-error" class="field-error" role="alert" hidden></div>
  <div class="dialog-actions"><button type="button" id="cancel-edit">取消</button><button type="submit" id="save" class="primary">保存服务器</button></div>
</form></dialog>
<dialog id="notice" aria-labelledby="notice-title"><h2 id="notice-title"></h2><p id="notice-body"></p><div class="dialog-actions"><button id="notice-cancel">取消</button><button id="notice-ok" class="primary">知道了</button></div></dialog>
<dialog id="authentication" aria-labelledby="auth-title"><form id="auth-form"><h2 id="auth-title">登录服务器</h2><p id="auth-message"></p><div id="auth-fields"></div><label class="check-label"><input id="remember" type="checkbox">记住账号、密码和 Group（保存在系统凭据库）</label><div class="dialog-actions"><button type="button" id="auth-cancel">取消连接</button><button type="submit" id="auth-submit" class="primary">登录</button></div></form></dialog>
<dialog id="certificate" aria-labelledby="certificate-title"><h2 id="certificate-title">无法验证服务器证书</h2><p id="certificate-server"></p><p id="certificate-reason"></p><p>请向管理员核对下方指纹。仅当指纹完全一致时才继续，本次允许不会关闭后续证书校验。</p><code id="fingerprint"></code><div class="dialog-actions"><button id="cert-reject" class="primary">拒绝并断开</button><button id="cert-accept">仅本次允许</button></div></dialog>
<dialog id="close-choice" aria-labelledby="close-title"><h2 id="close-title">关闭 GeekOCTunnel</h2><p>最小化到托盘会保持连接；退出会先断开全部连接并清理网络配置。</p><label class="check-label"><input id="remember-close" type="checkbox">记住我的选择（可在设置中修改）</label><div class="dialog-actions close-actions"><button id="close-cancel">取消</button><button id="close-exit">断开并退出</button><button id="close-tray" class="primary">最小化到托盘</button></div></dialog>
<dialog id="settings-panel" aria-labelledby="settings-title"><form id="settings-form">
  <h2 id="settings-title">设置</h2><p>设置仅保存在本机，不随服务器配置导出。</p>
  <fieldset><legend>窗口与资源</legend>
    <p id="tray-status" class="muted" hidden>当前桌面没有可用的系统托盘，托盘和极简选项暂不生效。窗口会保留；再次启动程序也可唤起它。</p>
    <label for="settings-close">关闭窗口时</label><select id="settings-close"><option value="ask">每次询问</option><option value="tray">最小化到托盘</option><option value="exit">断开全部并退出</option></select>
    <label class="check-label"><input id="settings-minimize" type="checkbox">点击最小化按钮时收起到托盘</label>
    <label class="check-label"><input id="settings-lightweight" type="checkbox">极简模式：隐藏后释放 WebView</label>
    <label for="settings-delay">隐藏多少秒后释放（5–3600 秒）</label><input id="settings-delay" type="number" min="5" max="3600" step="1" required>
    <p class="muted">VPN 和诊断记录保留在后台，从托盘菜单重新打开，或再次启动程序。编辑或登录对话框未关闭时暂不释放。</p>
  </fieldset>
  <fieldset><legend>连接策略</legend>
    <label class="check-label"><input id="settings-reconnect" type="checkbox">临时网络故障时自动重连</label>
    <label class="check-label"><input id="settings-ignore-certificate" type="checkbox">强行忽略证书校验（不推荐）</label>
    <p class="muted">忽略校验后无法确认服务器身份，存在被冒充和窃取登录信息的风险。连接策略对下一次连接生效。</p>
  </fieldset>
  <div id="settings-error" class="field-error" role="alert" hidden></div>
  <div class="dialog-actions"><button type="button" id="settings-cancel">取消</button><button type="submit" id="settings-save" class="primary">保存设置</button></div>
</form></dialog>`;

const icons = { Shield, Server, Plus, Download, Upload, LockKeyhole, ChevronRight, Power, Pencil, Trash2, ArrowDown, ArrowUp, Clock3, Info, RefreshCw, Settings2 };
function paintIcons() { createIcons({ icons, attrs: { 'aria-hidden': 'true' } }); }
const $ = (id) => document.getElementById(id);
let profiles = [], revision = '', selectedId = '', editingId = null, busy = false, ready = false;
let globalError = null;
const native = isTauri();
const selected = () => profiles.find(p => p.id === selectedId);
const messages = {
  invalid_server: '请输入有效的 HTTPS 域名或 IP，可带端口；不能包含账号、路径或查询参数。',
  invalid_profile_name: '请填写有效的服务器名称（1–128 个字符）。',
  unsupported_schema: '配置版本不兼容，原文件已保留。',
  unknown_field: '配置含未支持的字段或敏感信息，未导入。',
  certificate_review_required: '配置要求忽略证书错误，已拒绝导入。',
  invalid_profile_id: '配置标识无效或重复。',
  config_conflict: '配置已被其他窗口修改。请重新加载后再操作。',
  config_locked: '配置正在被另一个客户端使用，请关闭旧版配置窗口后重新加载。',
  config_too_large: '配置文件超过 256 KiB 限制。',
  request_too_large: '请求过大，请减少配置数量。',
  too_many_profiles: '最多支持 128 个服务器配置。',
  invalid_json: '配置文件损坏或不是有效 JSON，原文件已保留。',
  native_bridge_missing: '本地组件未能启动，请检查安装是否完整。',
  native_bridge_timeout: '本地组件响应超时，请重试。',
  vpn_backend_unavailable: '本地连接组件不可用，暂时无法连接。请重新启动或重新下载完整程序。',
  browser_preview: '这是网页界面预览。请在桌面客户端中管理配置和检查连接。',
  permission_denied: '系统授权被取消，连接未建立。',
  authentication_failed: '登录未完成，请检查账号、密码或服务器认证方式。',
  authentication_rejected: '服务器拒绝认证：请检查用户名、密码及账号策略。已停止自动重连。',
  authentication_timeout: '等待登录输入超时，已停止连接。',
  certificate_expired: '服务器证书已过期。',
  certificate_not_yet_valid: '服务器证书尚未生效，请同时检查系统时间。',
  certificate_revoked: '服务器证书已被吊销。',
  certificate_hostname_mismatch: '服务器证书与连接的主机名不匹配。',
  certificate_untrusted_issuer: '服务器证书的签发者不受信任，可能是自签名证书或缺少证书链。',
  certificate_invalid: '服务器证书未通过验证，需要核对证书指纹。',
  certificate_rejected: '未允许服务器证书，连接已停止，不会自动重连。',
  certificate_timeout: '等待证书核对超时，连接已停止。',
  gateway_unreachable: '无法连接 VPN 网关，可能是网络中断或服务端暂时不可用。',
  dns_resolution_failed: '无法解析 VPN 网关的域名，请检查当前网络和 DNS。',
  tls_handshake_failed: 'TLS 握手未完成，可能是网络中断或网关暂时不可用。',
  invalid_gateway_response: '网关返回了无法识别的认证响应。',
  session_expired: '服务器会话已失效，需要重新建立连接。',
  session_limit: '最多同时连接 8 个服务器，请先断开不需要的连接。',
  network_setup_busy: '另一个连接正在配置网络，请稍后重试。',
  tray_unavailable: '当前桌面没有可用的系统托盘。可保留窗口或断开并退出。',
  preferences_write_failed: '关闭偏好未能保存，请检查本机文件权限。',
  invalid_preferences: '设置值无效，释放延迟应为 5–3600 之间的整数秒。',
  unsupported_authentication: '服务器要求的认证方式暂不支持。当前支持账号、密码和选项表单。',
  tunnel_negotiation_failed: '服务器未能建立 VPN 隧道，请检查网关配置。',
  route_conflict: '服务器下发的路由与当前网络重叠，已取消连接。请先检查其他 VPN 和本地网络。',
  full_tunnel_not_supported: '此预览暂不支持接管全部流量，请使用下发分流路由的服务器。',
  unsupported_network_plan: '服务器下发了暂不支持的网络配置（例如 IPv6 或排除路由）。',
  network_setup_failed: '临时网卡或路由、DNS 配置失败，正在清理本次连接。',
  connection_lost: 'VPN 连接已中断，请重新连接。',
  network_cleanup_unconfirmed: '未能确认临时网络配置已清理，请检查系统中的 GeekOC 网卡后再连接。',
  session_stop_timeout: '断开超时，已停止会话进程；请检查系统中的 GeekOC 网卡是否已移除。',
  session_busy: '已有连接正在运行或清理，请稍候。',
  session_not_running: '连接已结束。',
  helper_disconnected: '系统网络助手意外退出，连接已停止。',
  helper_launch_failed: '网络助手未能启动，请检查组件文件和权限后重试。',
  executable_path_failed: '连接组件路径无法解析，请重新启动客户端检查运行文件。',
  session_start_failed: '连接组件无法读取服务器配置或加载依赖，请检查安装是否完整。',
  credential_save_failed: '连接成功，但系统凭据库不可用；登录信息未保存。',
  unsafe_installation: '请使用安装脚本将 Linux 客户端安装到 root 所有的目录，再以普通用户启动。',
  parent_identity_failed: '网络助手无法核对会话核心身份，请检查组件版本和安装目录。',
  invalid_session_event: '本机连接组件返回异常数据，连接已停止。',
};
function explain(error) { return Object.hasOwn(messages,String(error)) ? messages[String(error)] : '操作未完成，请检查配置内容与本机文件权限。'; }
function displayError(error) { const value=error || globalError; $('error').hidden = !value; $('error-text').textContent = value ? explain(value) : ''; }
function setError(error) { globalError=error; displayError(error); }
async function request(operation, extra = {}) {
  if (!native) throw 'browser_preview';
  const result = await invoke('desktop_request', { request: { schemaVersion: 1, requestId: crypto.randomUUID(), operation, ...extra } });
  if (!result.ok) throw result.error;
  return result;
}
function render() {
  const profile = selected();
  const current = connection(profile?.id || '');
  const active = running().length > 0;
  $('servers').replaceChildren();
  for (const p of profiles) {
    const button = document.createElement('button');
    button.className = 'server-item';
    button.setAttribute('aria-pressed', String(p.id === selectedId));
    button.innerHTML = '<i data-lucide="server"></i><span><strong></strong><small></small><em class="server-state"></em></span>';
    button.querySelector('strong').textContent = p.name;
    button.querySelector('small').textContent = p.server;
    const session=connection(p.id); button.dataset.state=session.state;
    button.querySelector('em').textContent=session.id || session.state==='failed' ? stateNames[session.state] : '';
    button.disabled = busy;
    button.addEventListener('click', () => { selectedId = p.id; render(); });
    $('servers').append(button);
  }
  $('count').textContent = String(profiles.length);
  $('empty-list').hidden = profiles.length !== 0;
  $('selected-name').textContent = profile?.name || '添加第一个服务器';
  $('selected-address').textContent = profile ? `https://${profile.server}` : '准备好服务器地址，即可保存配置。';
  $('detail-address').textContent = profile ? `https://${profile.server}` : '尚未选择';
  $('connect').disabled = busy || !ready || !profile || ['disconnecting', 'failed'].includes(current.state);
  $('connect').querySelector('span').textContent = current.id ? (current.state === 'connected' ? '断开连接' : '取消连接') : '连接';
  $('remove').disabled = busy || !ready || !profile || !!current.id;
  $('export').disabled = busy || !ready || !profiles.length;
  $('add').disabled = busy || !ready;
  $('edit').disabled = busy || !ready || !!current.id;
  $('import').disabled = busy || !ready || active;
  $('reload').disabled = busy || active;
  $('edit').innerHTML = profile ? '<i data-lucide="pencil"></i><span>编辑配置</span>' : '<i data-lucide="plus"></i><span>添加服务器</span>';
  $('storage-status').textContent = !native ? '网页预览 · 不读写本机配置' : !ready ? '配置尚未载入' : busy ? '正在处理…' : '配置已保存在本机';
  paintIcons();
  renderConnection();
}
function acceptSnapshot(result) {
  profiles = result.document.profiles;
  revision = result.revision;
  if (!selected()) selectedId = profiles[0]?.id || '';
  ready = true;
}
async function load() {
  if (busy) return;
  busy = true; render();
  try { acceptSnapshot(await request('snapshot')); setError(null); }
  catch (error) { ready = false; setError(error); }
  finally { busy = false; render(); }
}
async function replace(document) {
  const result = await request('replace', { document, revision });
  acceptSnapshot(result);
  setError(null);
}
function showNotice(title, text, confirm = false) {
  $('notice-title').textContent = title;
  $('notice-body').textContent = text;
  $('notice-cancel').hidden = !confirm;
  $('notice-ok').textContent = confirm ? '继续' : '知道了';
  $('notice').returnValue = '';
  $('notice').showModal();
  // Destructive/replace confirmations start on Cancel; Enter must not silently replace data.
  (confirm ? $('notice-cancel') : $('notice-ok')).focus();
  return new Promise(resolve => $('notice').addEventListener('close', () => resolve($('notice').returnValue === 'ok'), { once: true }));
}
$('notice-cancel').onclick = () => $('notice').close('cancel');
$('notice-ok').onclick = () => $('notice').close('ok');
function edit(profile) {
  if (busy || !ready || (profile && connection(profile.id).id)) return;
  editingId = profile?.id || null;
  $('editor-title').textContent = profile ? '编辑服务器' : '添加服务器';
  $('profile-name').value = profile?.name || '';
  $('profile-server').value = profile ? `https://${profile.server}` : '';
  $('profile-udp').checked = profile ? !!profile.enableUdp : true;
  $('form-error').hidden = true;
  $('editor').showModal();
  $('profile-name').focus();
}
$('add').onclick = () => edit();
$('edit').onclick = () => edit(selected());
$('cancel-edit').onclick = () => $('editor').close();
$('editor').addEventListener('cancel', event => { if (busy) event.preventDefault(); });
$('profile-form').onsubmit = async event => {
  event.preventDefault();
  if (busy || (editingId && connection(editingId).id)) return;
  busy = true; $('save').disabled = true; $('cancel-edit').disabled = true; render();
  const id = editingId || crypto.randomUUID();
  const profile = { id, name: $('profile-name').value.trim(), server: $('profile-server').value.trim(), ignoreCertificateErrors: false, enableUdp:$('profile-udp').checked };
  const next = editingId ? profiles.map(p => p.id === editingId ? profile : p) : [...profiles, profile];
  try { await replace({ schemaVersion: 1, profiles: next }); selectedId = id; $('editor').close(); }
  catch (error) { $('form-error').hidden = false; $('form-error').textContent = explain(error); }
  finally { busy = false; $('save').disabled = false; $('cancel-edit').disabled = false; render(); }
};
$('remove').onclick = async () => {
  const profile = selected();
  if (busy || !profile || connection(profile.id).id || !await showNotice('删除服务器', `将删除“${profile.name}”的本地配置。此操作无法撤销。`, true)) return;
  if (connection(profile.id).id) return;
  busy = true; render();
  try { await replace({ schemaVersion: 1, profiles: profiles.filter(p => p.id !== profile.id) }); }
  catch (error) { setError(error); }
  finally { busy = false; render(); }
};
$('connect').onclick = () => { if (!busy) connectSelected(); };
$('import').onclick = () => { $('import-file').value = ''; $('import-file').click(); };
$('import-file').onchange = async () => {
  const file = $('import-file').files?.[0];
  if (!file || busy) return;
  busy = true; render();
  try {
    if (file.size > 256 * 1024) throw 'config_too_large';
    let document;
    try { document = JSON.parse(await file.text()); } catch { throw 'invalid_json'; }
    if (!await showNotice('导入并替换配置', '导入将替换当前服务器列表。配置不能包含密码、令牌、私钥或关闭证书校验的选项。继续前请确认已导出需要保留的配置。', true)) return;
    await replace(document);
  } catch (error) { setError(error); }
  finally { busy = false; render(); }
};
$('export').onclick = async () => {
  if (busy || !await showNotice('导出服务器配置', '导出文件包含服务器名称和地址，可能暴露组织网络信息。请仅分享给可信的人。', true)) return;
  const data = { schemaVersion: 1, profiles };
  const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2) + '\n'], { type: 'application/json' }));
  const link = document.createElement('a');
  link.href = url; link.download = 'geekoctunnel-profiles.json';
  document.body.append(link); link.click(); link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
};
$('reload').onclick = load;
$('runtime-note').textContent = native ? '运行环境：Tauri 桌面客户端。' : '运行环境：网页预览，不具备本地操作权限。';
let preferences={closeBehavior:'ask',minimizeToTray:true,lightweightMode:false,lightweightDelaySeconds:60,autoReconnect:true,ignoreCertificateErrors:false};
function receivePreferences(value) {
  preferences={...preferences,...value};
  $('tray-status').hidden=!!preferences.trayAvailable;
  for(const id of ['settings-minimize','settings-lightweight','settings-delay']) $(id).disabled=!preferences.trayAvailable;
  $('certificate-warning').hidden=!preferences.ignoreCertificateErrors;
  $('certificate-policy').textContent=preferences.ignoreCertificateErrors ? '全局忽略校验 · 新连接生效' : '严格校验';
  renderConnection();
}
$('settings').disabled=!native;
$('settings').onclick=async()=>{
  try {receivePreferences(await invoke('desktop_preferences'));} catch(error) {setError(error);return;}
  $('settings-close').value=preferences.closeBehavior;
  $('settings-minimize').checked=preferences.minimizeToTray;
  $('settings-lightweight').checked=preferences.lightweightMode;
  $('settings-delay').value=preferences.lightweightDelaySeconds;
  $('settings-reconnect').checked=preferences.autoReconnect;
  $('settings-ignore-certificate').checked=preferences.ignoreCertificateErrors;
  $('settings-error').hidden=true;$('settings-panel').showModal();
};
$('settings-cancel').onclick=()=>$('settings-panel').close();
$('settings-form').onsubmit=async event=>{
  event.preventDefault();$('settings-save').disabled=true;
  try {
    const value={schemaVersion:1,closeBehavior:$('settings-close').value,minimizeToTray:$('settings-minimize').checked,
      lightweightMode:$('settings-lightweight').checked,lightweightDelaySeconds:Number($('settings-delay').value),
      autoReconnect:$('settings-reconnect').checked,ignoreCertificateErrors:$('settings-ignore-certificate').checked};
    if(!Number.isSafeInteger(value.lightweightDelaySeconds)||value.lightweightDelaySeconds<5||value.lightweightDelaySeconds>3600) throw 'invalid_preferences';
    receivePreferences(await invoke('desktop_save_preferences',{preferences:value}));
    $('close-behavior').value=value.closeBehavior;$('settings-panel').close();
  } catch(error) {$('settings-error').textContent=explain(error);$('settings-error').hidden=false;}
  finally {$('settings-save').disabled=false;}
};
export const dispose = setupSessions({selected,ready:()=>ready,profile:id=>profiles.find(p=>p.id===id),repaint:render,explain,setError:displayError,preferences:receivePreferences,certificateMode:()=>preferences.ignoreCertificateErrors},native);
render();
if (native) load();
