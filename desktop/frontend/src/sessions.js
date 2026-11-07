import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';

const $ = id => document.getElementById(id);
const connections = new Map();
let ui, timer = null, appVisible = true, dialogSession = null, logSequence = 0, renderedLogProfile = null;
let lastRenderedLog = 0, trayAvailable = false, restoring = false, ready = Promise.resolve();
const visible = () => appVisible && !document.hidden;
export const stateNames = { idle:'未连接', preparing:'正在连接', authenticating:'等待登录', connecting:'建立隧道', permission:'等待授权', connected:'已连接', reconnecting:'等待重连', disconnecting:'正在断开', failed:'清理待确认' };
const logMessages = {
  connect_requested:'开始连接。', user_disconnect:'用户请求断开，自动重连已停止。',
  tcp_connected:'已建立到网关的 TCP 连接。', tls_negotiating:'正在进行 TLS 握手和证书校验。',
  tls_connected:'TLS 握手成功。', authenticated:'服务器认证成功。', credentials_reused:'正在复用本次会话或系统凭据库中的登录信息。',
  authentication_waiting:'等待输入登录信息。', authentication_submitted:'登录信息已提交，等待服务器验证。',
  authentication_rejected:'服务器拒绝认证：请检查用户名、密码及账号策略；不会自动重试此认证错误。',
  certificate_accepted_once:'已仅为本次证书核对允许继续。', certificate_rejected:'证书未获允许，连接已停止。',
  credentials_saved:'登录信息已保存到系统凭据库。',
  preparing:'正在解析并连接网关。', connecting:'正在协商隧道和网络配置。',
  permission:'等待系统授权并应用隧道配置。', connected:'连接成功，隧道配置已应用。',
  disconnecting:'正在关闭隧道并清理本次网络配置。', disconnected:'连接已结束，网络清理已确认。',
  certificate_verification_disabled:'全局设置已强行忽略服务器证书校验。',
  dtls_negotiating:'正在尝试建立 DTLS / UDP 数据通道；TCP 通道继续可用。',
  dtls_connected:'DTLS / UDP 数据通道已建立。', dtls_fallback:'DTLS 暂不可用，数据继续通过 TLS / TCP 传输。',
  udp_disabled:'此连接已禁用 UDP，使用纯 TLS / TCP。',
};
export function connection(profileId) {
  if (!connections.has(profileId)) connections.set(profileId, {
    profileId, id:'', state:'idle', generation:0, sequence:0, network:null, networkGeneration:0,
    logs:[], error:null, challenge:null, remember:false, connectedAt:0, duration:0,
    received:null, sent:null, retryAt:0, stopping:false,
  });
  return connections.get(profileId);
}
export const running = () => [...connections.values()].filter(c => c.id);
export function summary() {
  const active = running();
  const connected = active.filter(c => c.state === 'connected').length;
  const reconnecting = active.filter(c => c.state === 'reconnecting').length;
  return `已连接 ${connected} 台${reconnecting ? ` · 重连中 ${reconnecting} 台` : ''}`;
}
function appendLog(c, text, level = 'info', timestamp) {
  const parsed = typeof timestamp === 'string' && timestamp.length < 64 ? new Date(timestamp) : new Date();
  const time = Number.isNaN(parsed.getTime()) ? new Date() : parsed;
  c.logs.push({id:++logSequence,time:time.toLocaleTimeString('zh-CN',{hour12:false}),text,level});
  if (c.logs.length > 200) c.logs.shift();
  if (!restoring && visible() && ui.selected()?.id === c.profileId) renderLogs(c);
}
function logCode(c, code, level = 'info', event = {}) {
  if(event.replayOnly) return;
  const message = Object.hasOwn(logMessages, code) ? logMessages[code] : ui.explain(code);
  const suffix = Number.isSafeInteger(event.platformError) && event.platformError > 0 ? `（系统错误 ${event.platformError}）` : '';
  appendLog(c, message + suffix, level, event.timestamp);
}
function fail(c, code, event = {}) { c.error = code; logCode(c, code, 'error', event); }
function renderLogs(c) {
  const pane = $('session-log');
  const bottom = pane.scrollHeight - pane.scrollTop - pane.clientHeight < 40;
  if (renderedLogProfile !== c.profileId || (c.logs.length && c.logs[0].id > lastRenderedLog + 1)) {
    pane.replaceChildren(); lastRenderedLog = 0; renderedLogProfile = c.profileId;
  }
  for (const entry of c.logs) if (entry.id > lastRenderedLog) {
    const row = document.createElement('div'); row.className = 'log-row'; row.dataset.level = entry.level;
    const time = document.createElement('time'); time.textContent = entry.time;
    const level = document.createElement('span'); level.className = 'log-level'; level.textContent = ({info:'信息',success:'成功',warning:'注意',error:'错误'})[entry.level] || '信息';
    const text = document.createElement('span'); text.textContent = entry.text;
    row.append(time,level,text); pane.append(row); lastRenderedLog = entry.id;
  }
  while (pane.children.length > c.logs.length) pane.firstElementChild.remove();
  $('log-empty').hidden = c.logs.length !== 0;
  $('log-count').textContent = `${c.logs.length} / 200`;
  if (bottom) pane.scrollTop = pane.scrollHeight;
}
function bytes(value) {
  if (!Number.isFinite(value) || value < 0) return '—';
  const units=['B','KiB','MiB','GiB']; let unit=0;
  while(value >= 1024 && unit < 3) { value /= 1024; unit++; }
  return `${value.toFixed(unit ? 1 : 0)} ${units[unit]}`;
}
function duration(c) {
  const seconds = Math.floor((c.connectedAt ? Date.now()-c.connectedAt : c.duration)/1000);
  return seconds ? `${Math.floor(seconds/3600).toString().padStart(2,'0')}:${Math.floor(seconds%3600/60).toString().padStart(2,'0')}:${(seconds%60).toString().padStart(2,'0')}` : c.connectedAt ? '00:00:00' : '—';
}
function refreshMetrics(c) {
  $('duration').textContent=duration(c); $('received').textContent=bytes(c.received); $('sent').textContent=bytes(c.sent);
  $('state').textContent = c.state === 'reconnecting' ? `等待重连 · ${Math.max(0,Math.ceil((c.retryAt-Date.now())/1000))} 秒` : stateNames[c.state] || '正在连接';
  $('state').dataset.state=c.state;
}
function listValues(id, values, empty) {
  const element=$(id); element.replaceChildren();
  if (!values.length) { element.textContent=empty; return; }
  for(const value of values) { const line=document.createElement('div'); line.textContent=value; element.append(line); }
}
export function renderConnection() {
  const c=connection(ui.selected()?.id || '');
  refreshMetrics(c); renderLogs(c);
  $('active-count').textContent=summary(); $('disconnect-all').disabled=running().length===0;
  const network=c.network;
  $('certificate-policy').textContent=network?.certificateVerification==='disabled' ? '本次连接已忽略校验'
    : network?.certificateVerification==='strict' ? '本次连接严格校验'
    : ui.certificateMode?.() ? '全局忽略校验 · 新连接生效' : '严格校验';
  $('network-state').textContent = !network ? '尚未获取' : c.state==='connected' ? '已应用'
    : c.networkGeneration===c.generation && ['connecting','permission'].includes(c.state) ? '已获取 · 等待应用' : '上次连接快照 · 当前未生效';
  $('tunnel-address').textContent=network?.address || '—';
  $('tunnel-gateway').textContent=network?.gateway || '—';
  $('tunnel-mtu').textContent=network ? String(network.mtu) : '—';
  $('tunnel-transport').textContent=network?.transport || '—';
  listValues('tunnel-dns',network?.dns || [],network ? '服务器未下发 DNS，保留系统 DNS。' : '连接后显示是否下发 DNS。');
  const routes=network?.routes || [];
  $('route-count').textContent=String(routes.length);
  listValues('tunnel-routes',routes.map(route=>`${route.destination}${route.source==='dns' ? '（DNS 主机路由）' : ''}`),network ? '未下发路由' : '连接后显示路由网段。');
  ui.setError(c.error);
  showChallenge();
}
async function send(c,action,extra={}) {
  if (!c.id) return;
  await invoke('vpn_send',{request:{sessionId:c.id,action,...extra}});
}
function refreshTimer() {
  clearInterval(timer); timer=null;
  if (!visible() || !running().some(c=>['connected','reconnecting'].includes(c.state))) return;
  timer=setInterval(()=>{
    const c=connection(ui.selected()?.id || ''); refreshMetrics(c);
    if(c.id && c.state==='connected') send(c,'stats').catch(()=>{});
  },2000);
}
function dismissChallenge(c) {
  c.challenge=null;
  if (dialogSession !== c) return;
  dialogSession=null;
  for(const id of ['authentication','certificate']) if($(id).open) $(id).close();
  $('auth-fields').replaceChildren(); $('remember').checked=false;
}
function showChallenge() {
  if (!visible() || dialogSession || (ui.ready && !ui.ready())) return;
  const selected=connection(ui.selected()?.id || '');
  const c=selected.challenge ? selected : [...connections.values()].find(c=>c.id && c.challenge);
  if (!c) return;
  dialogSession=c; const event=c.challenge;
  if(event.event==='certificate') {
    $('certificate-server').textContent=`服务器：https://${ui.profile?.(c.profileId)?.server || c.server || ''}`;
    $('certificate-reason').textContent=ui.explain(event.reason || 'certificate_invalid');
    $('fingerprint').textContent=event.fingerprint;
    $('certificate').showModal(); $('cert-reject').focus();
  } else {
    $('auth-title').textContent=`登录 · ${ui.profile?.(c.profileId)?.name || c.name || '服务器'}`;
    $('auth-message').textContent=event.rejected ? ui.explain('authentication_rejected') : (event.message || '请输入管理员提供的登录信息。');
    $('auth-fields').replaceChildren();
    for(const [index,field] of event.fields.entries()) {
      const label=document.createElement('label'); label.htmlFor=`auth-${index}`; label.textContent=field.label || field.name;
      const input=document.createElement(field.type==='select' ? 'select' : 'input');
      input.id=label.htmlFor; input.name=field.name; input.required=true;
      if(field.type==='select') for(const choice of field.choices) {
        const option=document.createElement('option'); option.value=choice.value; option.textContent=choice.label || choice.value; input.append(option);
      } else { input.type=field.secret ? 'password' : 'text'; input.maxLength=1024; input.autocomplete='off'; input.spellcheck=false; }
      $('auth-fields').append(label,input);
    }
    $('remember').checked=c.remember; $('authentication').showModal(); $('auth-fields').querySelector('input,select')?.focus();
  }
}
function redraw() { if(restoring) return; if(visible()) { ui.repaint(); showChallenge(); } refreshTimer(); }
function eventFor(c, expectedId, event) {
  if(c.id!==expectedId || event.sessionId!==c.id) return;
  if(event.event!=='ended') {
    const generation=event.generation ?? 1;
    if(!Number.isSafeInteger(event.sequence) || event.sequence<=c.sequence || generation<c.generation) return;
    c.sequence=event.sequence; c.generation=generation;
  }
  if(c.stopping && (['authentication','certificate','permission'].includes(event.event)
    || event.event==='state' && !['disconnecting','idle','failed'].includes(event.state))) return;
  if(event.event==='state') {
    const previous=c.state; c.state=event.state;
    if(c.connectedAt && c.state!=='connected') { c.duration=Date.now()-c.connectedAt; c.connectedAt=0; }
    if(c.state==='connected') {
      c.error=null; c.connectedAt=Date.parse(event.timestamp)||Date.now(); c.duration=0; c.received=0; c.sent=0; dismissChallenge(c);
      logCode(c,'connected','success',event);
    } else if(c.state==='reconnecting') {
      c.retryAt=(Date.parse(event.timestamp)||Date.now())+Math.max(0,Number(event.retryInSeconds)||0)*1000;
      if(!event.replayOnly) appendLog(c,`网络暂时不可用，${Math.max(0,Number(event.retryInSeconds)||0)} 秒后自动重连（第 ${Number(event.attempt)||1} 次）。`,'warning',event.timestamp);
    } else if(c.state==='idle') logCode(c,'disconnected','info',event);
    else if(previous!==c.state && Object.hasOwn(logMessages,c.state)) logCode(c,c.state,'info',event);
    if(['disconnecting','idle','failed'].includes(c.state)) dismissChallenge(c);
  } else if(event.event==='network') {
    c.network=event.details; c.networkGeneration=c.generation;
    const routes=event.details?.routes?.length || 0, dns=event.details?.dns?.length || 0;
    if(!event.replayOnly) appendLog(c,`已取得隧道配置：${routes} 条路由，${dns ? `${dns} 个 DNS 服务器` : '服务器未下发 DNS'}。`,'info',event.timestamp);
  } else if(event.event==='transport') {
    if(c.network) c.network.transport=event.transport;
  } else if(event.event==='log') {
    const level=['info','success','warning','error'].includes(event.level) ? event.level : 'info';
    logCode(c,event.code,level,event);
  } else if(event.event==='authentication' || event.event==='certificate') {
    dismissChallenge(c); c.challenge=event;
    logCode(c,event.event==='certificate' ? event.reason || 'certificate_invalid' : event.rejected ? 'authentication_rejected' : 'authentication_waiting',event.event==='certificate' || event.rejected ? 'warning':'info',event);
  } else if(event.event==='permission') { c.state='permission'; logCode(c,'permission','info',event); }
  else if(event.event==='statistics') {
    c.received=event.received; c.sent=event.sent;
    if(visible() && ui.selected()?.id===c.profileId) refreshMetrics(c);
    return;
  } else if(event.event==='error') fail(c,event.code,event);
  else if(event.event==='credential') logCode(c,event.saved ? 'credentials_saved':'credential_save_failed',event.saved ? 'success':'warning',event);
  else if(event.event==='ended') {
    dismissChallenge(c);
    if(c.connectedAt) { c.duration=Date.now()-c.connectedAt; c.connectedAt=0; }
    c.id=''; c.stopping=false; c.state=event.cleanupConfirmed ? 'idle':'failed';
    if(event.error) fail(c,event.error,event);
  }
  redraw();
}
export async function connectSelected() {
  const profile=ui.selected(); if(!profile) return;
  const c=connection(profile.id);
  if(c.id) { await disconnect(c); return; }
  await ready;
  if(c.id) return;
  if(running().length>=8) { fail(c,'session_limit'); redraw(); return; }
  Object.assign(c,{id:crypto.randomUUID(),name:profile.name,server:profile.server,state:'preparing',generation:1,sequence:0,network:null,error:null,
    remember:false,received:null,sent:null,connectedAt:0,duration:0,stopping:false});
  logCode(c,'connect_requested'); redraw();
  const id=c.id;
  try { await invoke('vpn_start',{sessionId:id,profileId:profile.id}); }
  catch(error) { if(c.id===id) { c.id=''; c.state='idle'; fail(c,error); redraw(); } }
}
async function disconnect(c, reason='user_disconnect') {
  if(!c?.id || c.stopping) return;
  c.stopping=true; logCode(c,reason); dismissChallenge(c); c.state='disconnecting'; redraw();
  try { await send(c,'stop'); } catch(error) { fail(c,error); redraw(); }
}
async function closeAction(action) {
  const remember=$('remember-close').checked;
  try {
    await invoke('desktop_close_action',{action,remember});
    if(remember) $('close-behavior').value=action;
    $('close-choice').close();
    if(action==='exit') for(const c of running()) { c.stopping=true; c.state='disconnecting'; logCode(c,'user_disconnect'); }
    redraw();
  } catch(error) { ui.setError(error); }
}
export function setupSessions(callbacks,native) {
  ui=callbacks;
  $('clear-log').onclick=()=>{ const c=connection(ui.selected()?.id || ''); c.logs=[]; $('session-log').replaceChildren(); lastRenderedLog=0; renderLogs(c); if(native) invoke('vpn_clear_log',{profileId:c.profileId}).catch(ui.setError); };
  $('disconnect-all').onclick=()=>Promise.all(running().map(c=>disconnect(c)));
  $('auth-form').onsubmit=async event=>{
    event.preventDefault(); const c=dialogSession; if(!c?.challenge) return;
    const values=Object.create(null);
    for(const input of $('auth-fields').querySelectorAll('input,select')) values[input.name]=input.value;
    c.remember=$('remember').checked;
    const reply={challengeId:c.challenge.challengeId,values,remember:c.remember}; dismissChallenge(c);
    logCode(c,'authentication_submitted');
    try { await send(c,'reply',reply); } catch(error) { fail(c,error); await disconnect(c); }
    finally { for(const key of Object.keys(values)) values[key]=''; showChallenge(); }
  };
  $('auth-cancel').onclick=()=>disconnect(dialogSession);
  $('cert-reject').onclick=()=>disconnect(dialogSession,'certificate_rejected');
  $('cert-accept').onclick=async()=>{
    const c=dialogSession; if(!c?.challenge) return;
    const reply={challengeId:c.challenge.challengeId,fingerprint:c.challenge.fingerprint,accept:true}; dismissChallenge(c);
    try { await send(c,'reply',reply); } catch(error) { fail(c,error); await disconnect(c); }
    showChallenge();
  };
  for(const id of ['authentication','certificate']) $(id).addEventListener('cancel',event=>{event.preventDefault();disconnect(dialogSession);});
  $('close-tray').onclick=()=>closeAction('tray'); $('close-exit').onclick=()=>closeAction('exit'); $('close-cancel').onclick=()=>$('close-choice').close();
  $('close-behavior').disabled=!native;
  $('close-behavior').onchange=()=>invoke('desktop_set_close_behavior',{behavior:$('close-behavior').value}).catch(ui.setError);
  const visibility=()=>{ if(visible()) redraw(); else refreshTimer(); };
  document.addEventListener('visibilitychange',visibility);
  const listeners=[]; let disposed=false;
  if(native) {
    const attach=async(name,handler)=>{ const unlisten=await listen(name,handler); if(disposed) unlisten(); else listeners.push(unlisten); };
    attach('desktop-visibility',event=>{appVisible=event.payload;visibility();}).catch(ui.setError);
    attach('desktop-close-requested',()=>{
      $('close-tray').disabled=!trayAvailable; $('remember-close').checked=false;
      if(!$('close-choice').open) $('close-choice').showModal(); $('close-tray').disabled ? $('close-cancel').focus() : $('close-tray').focus();
    }).catch(ui.setError);
    attach('desktop-prepare-release',event=>{
      if(!document.querySelector('dialog[open]')) invoke('desktop_release_ready',{epoch:event.payload}).catch(ui.setError);
    }).catch(ui.setError);
    const preferences=value=>{
      trayAvailable=!!value.trayAvailable; $('close-behavior').value=value.closeBehavior || 'ask';
      ui.preferences?.(value);
    };
    attach('desktop-preferences-changed',event=>preferences(event.payload)).catch(ui.setError);
    invoke('desktop_preferences').then(preferences).catch(ui.setError);
    const pending=[]; let loaded=false;
    const receive=({profileId,event})=>{const c=connection(profileId);eventFor(c,c.id,event);};
    ready=(async()=>{
      await attach('vpn-event',event=>loaded ? receive(event.payload) : pending.push(event.payload));
      const snapshots=await invoke('vpn_snapshot');
      if(disposed) return;
      restoring=true;
      for(const snapshot of Array.isArray(snapshots) ? snapshots : []) {
        const c=connection(snapshot.profileId);c.id=snapshot.sessionId;
        for(const event of snapshot.events) eventFor(c,snapshot.sessionId,event);
      }
      for(const event of pending) receive(event);
      restoring=false;loaded=true;redraw();
    })().catch(error=>{restoring=false;loaded=true;ui.setError(error);});
  }
  return ()=>{disposed=true;clearInterval(timer);document.removeEventListener('visibilitychange',visibility);for(const unlisten of listeners) unlisten();};
}
