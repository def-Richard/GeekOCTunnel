import { afterEach, beforeEach, expect, test, vi } from 'vitest';

const native = vi.hoisted(() => ({ invoke: vi.fn(), isTauri: vi.fn(() => true), Channel: class { onmessage = () => {}; } }));
vi.mock('@tauri-apps/api/core', () => native);
const events = vi.hoisted(() => ({ handlers:new Map(), listen:vi.fn(async(name,handler)=>{events.handlers.set(name,handler);return ()=>events.handlers.delete(name);}) }));
vi.mock('@tauri-apps/api/event',()=>({listen:events.listen}));
const example = { id: 'example', name: 'Example', server: 'vpn.example.com', ignoreCertificateErrors: false };
const snapshot = (profiles = [example]) => ({ ok: true, schemaVersion: 1, document: { schemaVersion: 1, profiles }, revision: 'revision-one', capabilities: { vpn: false } });
const el = id => document.getElementById(id);
const eventually = fn => vi.waitFor(fn);
let module;
async function boot() { module=await import('../src/main.js'); }
function sessionChannel(args) {
  return {...args,events:{onmessage:event=>events.handlers.get('vpn-event')({payload:{profileId:args.profileId,event}})}};
}

beforeEach(() => {
  vi.resetModules();
  native.isTauri.mockReturnValue(true);
  native.invoke.mockReset().mockImplementation(async(command)=> command==='desktop_preferences' ? {closeBehavior:'ask',trayAvailable:true} : command==='vpn_snapshot' ? [] : snapshot());
  events.handlers.clear(); module=null;
  document.body.innerHTML = '<div id="app"></div>';
  HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  HTMLDialogElement.prototype.close = function (value = '') { this.returnValue = value; this.open = false; this.dispatchEvent(new Event('close')); };
});
afterEach(()=>{module?.dispose();vi.useRealTimers();});
async function start() { await boot(); await eventually(() => expect(el('storage-status').textContent).toBe('配置已保存在本机')); }
test('server names render as text, never HTML', async () => {
  native.invoke.mockResolvedValue(snapshot([{ ...example, name: '<img src=x onerror=alert(1)>' }]));
  await start();
  expect(el('selected-name').textContent).toContain('<img');
  expect(el('servers').querySelector('img')).toBeNull();
  expect(el('selected-name').querySelector('img')).toBeNull();
});
test('saving uses the previous revision and updates only after native success', async () => {
  await start();
  el('edit').click();
  el('profile-name').value = 'Updated';
  native.invoke.mockResolvedValue(snapshot([{ ...example, name: 'Updated' }]));
  el('profile-form').dispatchEvent(new Event('submit', { cancelable: true }));
  await eventually(() => expect(el('editor').open).toBe(false));
  expect(native.invoke.mock.lastCall[1].request).toMatchObject({ operation: 'replace', revision: 'revision-one' });
  expect(el('selected-name').textContent).toBe('Updated');
});
test('a conflicting write keeps the editor open and preserves the displayed profile', async () => {
  await start();
  el('edit').click();
  el('profile-name').value = 'Unsaved';
  native.invoke.mockResolvedValue({ ok: false, error: 'config_conflict' });
  el('profile-form').dispatchEvent(new Event('submit', { cancelable: true }));
  await eventually(() => expect(el('form-error').textContent).toContain('其他窗口'));
  expect(el('editor').open).toBe(true);
  expect(el('profile-name').value).toBe('Unsaved');
  expect(el('selected-name').textContent).toBe('Example');
});
test('missing session component never claims a connected tunnel', async () => {
  await start();
  native.invoke.mockRejectedValue('vpn_backend_unavailable');
  el('connect').click();
  await eventually(() => expect(el('error-text').textContent).toContain('暂时无法连接'));
  expect(native.invoke.mock.lastCall[0]).toBe('vpn_start');
  expect(native.invoke.mock.lastCall[1]).toMatchObject({ profileId: 'example' });
  await eventually(() => expect(el('state').textContent).toBe('未连接'));
});

test('native events drive login, connection and cleanup; stale sessions cannot change the UI', async () => {
  await start();
  native.invoke.mockResolvedValue(undefined);
  el('connect').click();
  await eventually(() => expect(native.invoke.mock.lastCall[0]).toBe('vpn_start'));
  const { sessionId, events } = sessionChannel(native.invoke.mock.lastCall[1]);
  const emit = (event, sequence) => events.onmessage({ sessionId, sequence, ...event });
  emit({ event:'state', state:'connected', sessionId:'stale' }, 1);
  expect(el('state').textContent).not.toBe('已连接');
  emit({ event:'authentication', challengeId:'challenge-one', fields:[{ name:'password', label:'<img src=x>', secret:true, type:'text' }] }, 1);
  expect(el('authentication').open).toBe(true);
  expect(el('auth-fields').querySelector('img')).toBeNull();
  expect(el('auth-0').type).toBe('password');
  el('auth-0').value = 'temporary-fixture';
  el('auth-form').dispatchEvent(new Event('submit', { cancelable:true }));
  await eventually(() => expect(native.invoke.mock.lastCall[0]).toBe('vpn_send'));
  expect(el('auth-fields').children).toHaveLength(0);
  expect(el('authentication').open).toBe(false);
  emit({ event:'state', state:'connected' }, 2);
  expect(el('state').textContent).toBe('已连接');
  expect(el('edit').disabled).toBe(true);
  emit({ event:'statistics', received:2048, sent:512 }, 3);
  expect(el('received').textContent).toBe('2.0 KiB');
  el('connect').click();
  await eventually(() => expect(el('state').textContent).toContain('正在断开'));
  expect(native.invoke.mock.lastCall[1].request.action).toBe('stop');
  emit({ event:'ended', cleanupConfirmed:true }, 4);
  expect(el('state').textContent).toBe('未连接');
  expect(el('edit').disabled).toBe(false);
});

test('certificate exception defaults to rejection and is scoped to its challenge', async () => {
  await start(); native.invoke.mockResolvedValue(undefined); el('connect').click();
  await eventually(() => expect(native.invoke.mock.lastCall[0]).toBe('vpn_start'));
  const { sessionId, events } = sessionChannel(native.invoke.mock.lastCall[1]);
  events.onmessage({sessionId, sequence:1, event:'certificate', challengeId:'cert-one', fingerprint:'sha256:fixture'});
  expect(document.activeElement.id).toBe('cert-reject');
  expect(el('fingerprint').textContent).toBe('sha256:fixture');
  el('cert-accept').click();
  await eventually(() => expect(native.invoke.mock.lastCall[0]).toBe('vpn_send'));
  expect(native.invoke.mock.lastCall[1].request).toMatchObject({ action:'reply', challengeId:'cert-one', fingerprint:'sha256:fixture', accept:true });
  events.onmessage({sessionId, event:'ended', cleanupConfirmed:true});
});
test('unreadable configuration does not enable destructive empty-list writes', async () => {
  native.invoke.mockResolvedValue({ ok: false, error: 'invalid_json' });
  await boot();
  await eventually(() => expect(el('error-text').textContent).toContain('原文件已保留'));
  expect(el('add').disabled).toBe(true);
  expect(el('import').disabled).toBe(true);
});
test('browser preview cannot invoke native code', async () => {
  native.isTauri.mockReturnValue(false);
  await boot();
  expect(native.invoke).not.toHaveBeenCalled();
  expect(el('connect').disabled).toBe(true);
  expect(el('storage-status').textContent).toContain('网页预览');
});

async function beginConnection() {
  el('connect').click();
  await eventually(() => expect(native.invoke.mock.lastCall[0]).toBe('vpn_start'));
  const {sessionId,events:channel}=sessionChannel(native.invoke.mock.lastCall[1]);
  let sequence=0;
  return {sessionId,emit:event=>channel.onmessage({sessionId,generation:1,sequence:++sequence,...event})};
}
test('network details remain a labelled snapshot after disconnect and reset on a new connection',async()=>{
  await start(); const session=await beginConnection();
  session.emit({event:'network',details:{address:'192.0.2.10',gateway:'192.0.2.1',mtu:1400,transport:'TLS / TCP',dns:['192.0.2.53'],routes:[{destination:'192.0.2.0/24',source:'server'},{destination:'192.0.2.53/32',source:'dns'}]}});
  session.emit({event:'state',state:'connected'});
  expect(el('tunnel-address').textContent).toBe('192.0.2.10');
  expect(el('tunnel-dns').textContent).toContain('192.0.2.53');
  expect(el('tunnel-routes').textContent).toContain('DNS 主机路由');
  expect(el('network-state').textContent).toBe('已应用');
  session.emit({event:'ended',cleanupConfirmed:true});
  expect(el('network-state').textContent).toContain('当前未生效');
  const next=await beginConnection();
  expect(el('tunnel-address').textContent).toBe('—');
  next.emit({event:'network',details:{address:'192.0.2.11',mtu:1400,routes:[],dns:[]}});
  expect(el('tunnel-dns').textContent).toContain('服务器未下发 DNS');
});
test('parallel sessions keep statistics and queued authentication replies separate',async()=>{
  native.invoke.mockImplementation(async command=>command==='desktop_preferences' ? {trayAvailable:true} : snapshot([example,{...example,id:'second',name:'Second'}]));
  await start(); const a=await beginConnection();
  el('servers').children[1].click(); const b=await beginConnection();
  for(const session of [a,b]) session.emit({event:'authentication',challengeId:session.sessionId,fields:[{name:'password',type:'text',secret:true}]});
  expect(el('auth-title').textContent).toContain('Example');
  el('auth-0').value='never-log-this-password';
  el('auth-form').dispatchEvent(new Event('submit',{cancelable:true}));
  await eventually(()=>expect(el('auth-title').textContent).toContain('Second'));
  expect(native.invoke.mock.calls.find(([command,args])=>command==='vpn_send' && args.request.action==='reply')[1].request.sessionId).toBe(a.sessionId);
  a.emit({event:'state',state:'connected'}); b.emit({event:'state',state:'connected'});
  a.emit({event:'statistics',received:1024,sent:0}); b.emit({event:'statistics',received:4096,sent:0});
  expect(el('received').textContent).toBe('4.0 KiB');
  expect(el('active-count').textContent).toBe('已连接 2 台');
  el('servers').children[0].click(); expect(el('received').textContent).toBe('1.0 KiB');
  expect(el('session-log').textContent).not.toContain('never-log-this-password');
  a.emit({event:'ended',cleanupConfirmed:true}); expect(el('active-count').textContent).toBe('已连接 1 台');
});

test.each(['preparing','connected','reconnecting','disconnecting'])('only the active profile is locked while %s; other profiles remain editable',async state=>{
  const second={...example,id:'second',name:'Second',server:'second.example.com'};
  native.invoke.mockImplementation(async command=>command==='desktop_preferences' ? {trayAvailable:true} : command==='vpn_snapshot' ? [] : snapshot([example,second]));
  await start(); const a=await beginConnection();a.emit({event:'state',state});
  expect(el('edit').disabled).toBe(true);expect(el('remove').disabled).toBe(true);
  expect(el('add').disabled).toBe(false);expect(el('import').disabled).toBe(true);
  el('servers').children[1].click();
  expect(el('edit').disabled).toBe(false);expect(el('remove').disabled).toBe(false);
  el('edit').click();el('profile-name').value='Updated second';
  native.invoke.mockImplementation(async(command,args)=>command==='desktop_request' ? snapshot(args.request.document.profiles) : undefined);
  el('profile-form').dispatchEvent(new Event('submit',{cancelable:true}));
  await eventually(()=>expect(el('editor').open).toBe(false));
  const replacement=native.invoke.mock.calls.findLast(([command,args])=>command==='desktop_request' && args.request.operation==='replace')[1].request;
  expect(replacement.document.profiles[0]).toEqual(example);
  expect(replacement.document.profiles[1]).toMatchObject({id:'second',name:'Updated second'});
  el('servers').children[0].click();expect(el('edit').disabled).toBe(true);
  a.emit({event:'ended',cleanupConfirmed:true});expect(el('edit').disabled).toBe(false);
});
test('logs are bounded, redact raw data and reject stale generations',async()=>{
  await start(); const session=await beginConnection();
  session.emit({event:'state',state:'connected',generation:2});
  session.emit({event:'state',state:'idle',generation:1});
  expect(el('state').textContent).toBe('已连接');
  for(let i=0;i<205;i++) session.emit({event:'log',generation:2,code:'tcp_connected',message:'secret-response-cookie'});
  session.emit({event:'log',generation:2,code:'unknown-secret-code'});
  expect(el('session-log').children).toHaveLength(200);
  expect(el('session-log').textContent).not.toMatch(/secret-response-cookie|unknown-secret-code/);
  el('clear-log').click(); expect(el('session-log').children).toHaveLength(0);
});
test('hidden windows stop polling and repainting; manual stop wins over late reconnect events',async()=>{
  await start(); const session=await beginConnection();
  vi.useFakeTimers(); session.emit({event:'state',state:'connected'});
  await vi.advanceTimersByTimeAsync(2000);
  const polls=()=>native.invoke.mock.calls.filter(([name,args])=>name==='vpn_send' && args.request.action==='stats').length;
  expect(polls()).toBe(1);
  events.handlers.get('desktop-visibility')({payload:false});
  session.emit({event:'state',state:'reconnecting',retryInSeconds:30,attempt:1});
  expect(el('state').textContent).toBe('已连接');
  await vi.advanceTimersByTimeAsync(10000); expect(polls()).toBe(1);
  events.handlers.get('desktop-visibility')({payload:true});
  expect(el('state').textContent).toContain('等待重连');
  el('connect').click(); session.emit({event:'state',state:'connected',generation:2});
  expect(el('state').textContent).toBe('正在断开');
  session.emit({event:'ended',cleanupConfirmed:true});
  await vi.advanceTimersByTimeAsync(10000); expect(polls()).toBe(1);
});
test('closing offers tray or exit and persists only the chosen preference',async()=>{
  await start();
  events.handlers.get('desktop-close-requested')({});
  expect(el('close-choice').open).toBe(true);
  el('remember-close').checked=true; el('close-tray').click();
  await eventually(()=>expect(el('close-choice').open).toBe(false));
  expect(native.invoke).toHaveBeenCalledWith('desktop_close_action',{action:'tray',remember:true});
  expect(el('close-behavior').value).toBe('tray');
  el('close-behavior').value='ask'; el('close-behavior').dispatchEvent(new Event('change'));
  expect(native.invoke).toHaveBeenCalledWith('desktop_set_close_behavior',{behavior:'ask'});
});
test('settings validate the delay and save global preferences separately from server profiles',async()=>{
  await start();el('settings').click();
  await eventually(()=>expect(el('settings-panel').open).toBe(true));
  expect(el('settings-ignore-certificate').checked).toBe(false);
  el('settings-delay').value='0';el('settings-form').dispatchEvent(new Event('submit',{cancelable:true}));
  await eventually(()=>expect(el('settings-error').textContent).toContain('5–3600'));
  expect(native.invoke.mock.calls.some(([cmd])=>cmd==='desktop_save_preferences')).toBe(false);
  el('settings-delay').value='60';el('settings-lightweight').checked=true;el('settings-ignore-certificate').checked=true;
  native.invoke.mockImplementation(async (cmd,args)=>cmd==='desktop_save_preferences' ? args.preferences : undefined);
  el('settings-form').dispatchEvent(new Event('submit',{cancelable:true}));
  await eventually(()=>expect(el('settings-panel').open).toBe(false));
  expect(native.invoke.mock.lastCall).toEqual(['desktop_save_preferences',{preferences:{schemaVersion:1,closeBehavior:'ask',minimizeToTray:true,lightweightMode:true,lightweightDelaySeconds:60,autoReconnect:true,ignoreCertificateErrors:true}}]);
  expect(el('certificate-warning').hidden).toBe(false);
});
test('desktops without a tray keep unavailable background controls disabled and offer a recoverable close',async()=>{
  native.invoke.mockImplementation(async cmd=>cmd==='desktop_preferences' ? {closeBehavior:'ask',trayAvailable:false} : cmd==='vpn_snapshot' ? [] : snapshot());
  await start();el('settings').click();await eventually(()=>expect(el('settings-panel').open).toBe(true));
  expect(el('tray-status').hidden).toBe(false);
  expect(el('settings-lightweight').disabled).toBe(true);
  expect(el('settings-minimize').disabled).toBe(true);
  el('settings-cancel').click();events.handlers.get('desktop-close-requested')({});
  expect(el('close-tray').disabled).toBe(true);expect(document.activeElement.id).toBe('close-cancel');
});
test('legacy profiles retain TCP while new profiles can enable UDP explicitly',async()=>{
  await start();el('edit').click();expect(el('profile-udp').checked).toBe(false);
  el('profile-udp').checked=true;el('profile-form').dispatchEvent(new Event('submit',{cancelable:true}));
  await eventually(()=>expect(el('editor').open).toBe(false));
  expect(native.invoke.mock.calls.find(([cmd,args])=>cmd==='desktop_request' && args.request.operation==='replace')[1].request.document.profiles[0].enableUdp).toBe(true);
  el('add').click();expect(el('profile-udp').checked).toBe(true);
});
test('recreated window restores connected state, details, transport and recent logs without reconnecting',async()=>{
  const timestamp=new Date(Date.now()-15000).toISOString();
  native.invoke.mockImplementation(async cmd=>cmd==='desktop_preferences' ? {trayAvailable:true} : cmd==='vpn_snapshot' ? [{profileId:'example',sessionId:'kept',events:[
    {sessionId:'kept',generation:1,sequence:1,event:'network',details:{address:'192.0.2.9',routes:[],dns:[],mtu:1400}},
    {sessionId:'kept',generation:1,sequence:2,event:'state',state:'connected',timestamp},
    {sessionId:'kept',generation:1,sequence:3,event:'transport',transport:'DTLS / UDP'},
    {sessionId:'kept',generation:1,sequence:4,event:'statistics',received:2048,sent:1024},
  ]}] : snapshot());
  await start();await eventually(()=>expect(el('state').textContent).toBe('已连接'));
  expect(el('tunnel-address').textContent).toBe('192.0.2.9');expect(el('tunnel-transport').textContent).toBe('DTLS / UDP');
  expect(el('received').textContent).toBe('2.0 KiB');expect(el('duration').textContent).toBe('00:00:15');
  expect(native.invoke.mock.calls.some(([cmd])=>cmd==='vpn_start')).toBe(false);
});
test('lightweight release waits for open edit or login dialogs',async()=>{
  await start();el('edit').click();
  events.handlers.get('desktop-prepare-release')({payload:7});
  expect(native.invoke.mock.calls.some(([cmd])=>cmd==='desktop_release_ready')).toBe(false);
  el('cancel-edit').click();events.handlers.get('desktop-prepare-release')({payload:8});
  expect(native.invoke).toHaveBeenCalledWith('desktop_release_ready',{epoch:8});
});
test('changing global certificate policy does not relabel an existing insecure connection as verified',async()=>{
  await start();const s=await beginConnection();
  s.emit({event:'network',details:{address:'192.0.2.9',dns:[],routes:[],certificateVerification:'disabled'}});
  s.emit({event:'state',state:'connected'});
  events.handlers.get('desktop-preferences-changed')({payload:{trayAvailable:true,ignoreCertificateErrors:false}});
  expect(el('certificate-policy').textContent).toBe('本次连接已忽略校验');
});
test('restoring necessary state does not resurrect cleared log entries',async()=>{
  await start();const s=await beginConnection();
  s.emit({event:'state',state:'connected'});el('clear-log').click();
  s.emit({event:'network',details:{address:'192.0.2.9',dns:[],routes:[]},replayOnly:true});
  s.emit({event:'state',state:'connected',replayOnly:true});
  expect(el('session-log').children).toHaveLength(0);expect(el('tunnel-address').textContent).toBe('192.0.2.9');
});
test('restored certificate challenge waits for its server profile to load',async()=>{
  let resolveProfiles;
  native.invoke.mockImplementation(async cmd=>cmd==='desktop_preferences' ? {trayAvailable:true} : cmd==='vpn_snapshot' ? [{profileId:'example',sessionId:'kept',events:[
    {sessionId:'kept',generation:1,sequence:1,event:'certificate',challengeId:'pending-cert',fingerprint:'sha256:fixture'},
  ]}] : new Promise(resolve=>{resolveProfiles=resolve;}));
  await boot();await eventually(()=>expect(events.handlers.has('vpn-event')).toBe(true));
  expect(el('certificate').open).toBe(false);
  resolveProfiles(snapshot());
  await eventually(()=>expect(el('certificate').open).toBe(true));
  expect(el('certificate-server').textContent).toContain('vpn.example.com');
});
