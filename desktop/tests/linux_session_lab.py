import http.client, json, os, pathlib, pwd, queue, selectors, shutil, signal, socket, socketserver, subprocess, threading, time, uuid

# This harness starts D-Bus, resolved and a scoped Polkit rule only inside a disposable container.
assert pathlib.Path('/.dockerenv').exists() and os.environ.get('GEEKOC_ISOLATED_TEST') == '1'
assert os.geteuid() == 0
build=pathlib.Path(os.environ.get('GEEKOC_BUILD_DIR','/work/build'))
runtime=pathlib.Path(os.environ.get('OPENCONNECT_RUNTIME','/runtime/libopenconnect.so.5'))

root=pathlib.Path('/opt/geekoctunnel/native')
root.mkdir(parents=True,exist_ok=True)
for name in ('geekoctunnel-core','geekoctunnel-helper'):
    shutil.copy2(build/name,root/name)
shutil.copy2(runtime,root/'libopenconnect.so.5')
for item in root.iterdir(): item.chmod(0o755)
root.chmod(0o755)
credentials=json.loads(pathlib.Path('/fixture/credentials.json').read_text())
account=pwd.getpwnam('builder')
pathlib.Path('/run/dbus').mkdir(parents=True,exist_ok=True)
subprocess.run(['dbus-daemon','--system','--fork'],check=True)
rule=pathlib.Path('/etc/polkit-1/rules.d/49-geekoc-isolated.rules')
policy=pathlib.Path(__file__).resolve().parents[1]/'platform/linux/io.github.def-richard.geekoctunnel.connect.policy'
shutil.copy2(policy,pathlib.Path('/usr/share/polkit-1/actions')/policy.name)
rule.write_text('polkit.addRule(function(action, subject) { if (action.id === "io.github.def-richard.geekoctunnel.connect" && subject.user === "builder" && action.lookup("program") === "/opt/geekoctunnel/native/geekoctunnel-helper") return polkit.Result.YES; });\n')
polkit=subprocess.Popen(['/usr/lib/polkit-1/polkitd','--no-debug'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
resolver_log=pathlib.Path('/tmp/geekoc-resolved.log')
resolver_user=pwd.getpwnam('systemd-resolve')
resolver_run=pathlib.Path('/run/systemd/resolve')
assert not resolver_run.is_symlink()
resolver_run.mkdir(parents=True,exist_ok=True)
os.chown(resolver_run,resolver_user.pw_uid,resolver_user.pw_gid)
resolved=subprocess.Popen(['/lib/systemd/systemd-resolved'],stdout=subprocess.DEVNULL,stderr=resolver_log.open('w'))
for _ in range(50):
    if subprocess.run(['resolvectl','dns'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
    if resolved.poll() is not None:raise AssertionError('Isolated resolver startup failed: '+resolver_log.read_text())
    time.sleep(.2)

class Relay(socketserver.BaseRequestHandler):
    def handle(self):
        if self.server.paused:return
        with socket.create_connection((self.server.gateway,4443),timeout=10) as other:
            self.server.connections.extend([self.request,other])
            self.request.setblocking(False); other.setblocking(False)
            with selectors.DefaultSelector() as selector:
                selector.register(self.request,selectors.EVENT_READ,other); selector.register(other,selectors.EVENT_READ,self.request)
                while True:
                    for key,_ in selector.select(60):
                        data=key.fileobj.recv(65536)
                        if not data:return
                        key.data.setblocking(True)
                        try:key.data.sendall(data)
                        finally:key.data.setblocking(False)
class Proxy(socketserver.ThreadingTCPServer):
    allow_reuse_address=True
    daemon_threads=True
    def __init__(self,port,gateway):
        self.gateway=gateway;self.paused=False;self.connections=[]
        super().__init__(('127.0.0.1',port),Relay)
    def interrupt(self):
        self.paused=True
        for connection in self.connections:
            try:connection.shutdown(socket.SHUT_RDWR);connection.close()
            except OSError:pass
        self.connections=[]
    def handle_error(self, request, address):pass
proxy=Proxy(44443,os.environ['LAB_GATEWAY'])
threading.Thread(target=proxy.serve_forever,daemon=True).start()

def as_user():
    os.setgroups([]);os.setgid(account.pw_gid);os.setuid(account.pw_uid)
def state():
    routes=json.loads(subprocess.check_output(['ip','-j','-4','route','show','table','all']))
    routes=sorted((json.dumps({k:r[k] for k in ('dst','gateway','dev','table','metric','prefsrc') if k in r},sort_keys=True) for r in routes))
    dns=subprocess.check_output(['resolvectl','dns'],text=True)
    return routes,dns
def adapters():
    return [p.name for p in pathlib.Path('/sys/class/net').iterdir() if p.name.startswith('goc')]
time.sleep(1)
baseline=state()
results=[]
for mode in ('connect','cancel-auth','wrong-password','certificate-reject','route-conflict','crash','helper-crash'):
    if mode=='route-conflict':subprocess.run(['ip','route','add','192.0.2.1/32','dev','lo'],check=True)
    expected_state=state()
    session=str(uuid.uuid4())
    args=[str(root/'geekoctunnel-core'),'--test-server','127.0.0.1:44443']
    if mode!='certificate-reject':args+=['--ca-file','/fixture/ca.pem']
    env={'HOME':account.pw_dir,'PATH':'/usr/bin:/usr/sbin','LANG':'C.UTF-8'}
    core=subprocess.Popen(args,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,preexec_fn=as_user,env=env)
    assert pathlib.Path(f'/proc/{core.pid}').stat().st_uid==account.pw_uid
    assert os.readlink(f'/proc/{core.pid}/exe')==str(root/'geekoctunnel-core'), 'test_container_requires_SYS_PTRACE_for_peer_identity'
    events=[];connected=False;clean=False;auth=False
    def send(value):
        try:core.stdin.write(json.dumps({'sessionId':session,**value})+'\n');core.stdin.flush()
        except (BrokenPipeError,ValueError):pass
    send({'schemaVersion':1})
    deadline=time.monotonic()+90
    messages=queue.Queue()
    def read_events():
        for line in core.stdout:messages.put(line)
        messages.put(None)
    threading.Thread(target=read_events,daemon=True).start()
    try:
        while time.monotonic()<deadline:
            try:line=messages.get(timeout=1)
            except queue.Empty:
                if core.poll() is not None:break
                continue
            if not line:break
            event=json.loads(line)
            safe={k:event[k] for k in ('event','state','code','platformError','cleanupConfirmed') if k in event}
            events.append(safe);print(json.dumps({'mode':mode,**safe}),flush=True)
            if event.get('event')=='certificate':send({'action':'stop'})
            if event.get('event')=='authentication':
                auth=True
                if mode=='cancel-auth' or (mode=='wrong-password' and event.get('rejected')):send({'action':'stop'});continue
                values={f['name']:f['choices'][-1]['value'] if f['type']=='select' else (str(uuid.uuid4()) if mode=='wrong-password' else credentials['password']) if f['secret'] else credentials['username'] for f in event['fields']}
                send({'action':'reply','challengeId':event['challengeId'],'values':values,'remember':False})
            if event.get('state')=='connected':
                connected=True
                if mode=='crash':core.kill();continue
                if mode=='helper-crash':
                    for entry in pathlib.Path('/proc').iterdir():
                        if not entry.name.isdigit():continue
                        try:
                            if (entry/'exe').resolve()==root/'geekoctunnel-helper':os.kill(int(entry.name),signal.SIGKILL)
                        except OSError:pass
                    continue
                connection=http.client.HTTPConnection('192.0.2.1',8080,timeout=8)
                connection.request('GET','/')
                assert connection.getresponse().read()==b'GeekOCTunnel isolated Windows lab\n'
                dns=subprocess.check_output(['resolvectl','query','--cache=no','verification.example.test'],text=True,timeout=8)
                assert '192.0.2.1' in dns
                print(json.dumps({'mode':mode,'http':True,'systemDns':True}),flush=True)
                send({'action':'stats'});send({'action':'stop'})
            clean|=bool(event.get('cleanupConfirmed'))
    except BaseException:
        send({'action':'stop'})
        try:core.wait(timeout=12)
        except subprocess.TimeoutExpired:core.kill();core.wait()
        raise
    if core.poll() is None:
        send({'action':'stop'})
        try:core.wait(timeout=12)
        except subprocess.TimeoutExpired:core.kill();core.wait();raise AssertionError('session_timeout')
    for _ in range(50):
        if not adapters():break
        time.sleep(.1)
    assert not adapters(), 'temporary_tun_remains'
    assert state()==expected_state,'existing_route_or_dns_changed'
    if mode=='route-conflict':
        assert any(e.get('code')=='route_conflict' for e in events)
        subprocess.run(['ip','route','del','192.0.2.1/32','dev','lo'],check=True)
    assert state()==baseline,'test_route_cleanup_failed'
    errors=core.stderr.read()
    if errors:print(errors,flush=True)
    if mode=='connect':assert connected and clean
    elif mode in ('crash','helper-crash'):assert connected
    else:assert not connected and clean
    result={'mode':mode,'connected':connected,'clean':clean,'noRemainingTun':True,'networkRestored':True,'coreUid':account.pw_uid,'events':events}
    results.append(result)
    print(json.dumps({k:v for k,v in result.items() if k!='events'}),flush=True)
if os.environ.get('LAB_GATEWAY_B'):
    class Session:
        def __init__(self,second=False,udp=False,reconnect=True):
            self.id=str(uuid.uuid4());self.events=[];self.forms=0
            fixture=pathlib.Path('/fixture-b' if second else '/fixture')
            secret=json.loads((fixture/'credentials.json').read_text())
            self.core=subprocess.Popen([str(root/'geekoctunnel-core'),'--test-server',f'127.0.0.1:{44444 if second else 44443}','--ca-file',str(fixture/'ca.pem')],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,preexec_fn=as_user,env={'HOME':account.pw_dir,'PATH':'/usr/bin:/usr/sbin','LANG':'C.UTF-8'})
            assert pathlib.Path(f'/proc/{self.core.pid}').stat().st_uid==account.pw_uid
            def read():
                for line in self.core.stdout:
                    event=json.loads(line)
                    self.events.append(event)
                    if event.get('event')=='authentication':
                        self.forms+=1
                        if event.get('rejected'):self.send({'action':'stop'});continue
                        values={f['name']:f['choices'][-1]['value'] if f['type']=='select' else secret['password'] if f['secret'] else secret['username'] for f in event['fields']}
                        self.send({'action':'reply','challengeId':event['challengeId'],'values':values,'remember':False})
                    if event.get('event')=='certificate':self.send({'action':'stop'})
            self.reader=threading.Thread(target=read,daemon=True);self.reader.start()
            self.send({'schemaVersion':1,'testEnableUdp':udp,'autoReconnect':reconnect})
            live.append(self)
        def send(self,value):
            try:self.core.stdin.write(json.dumps({'sessionId':self.id,**value})+'\n');self.core.stdin.flush()
            except (BrokenPipeError,ValueError):pass
        def wait(self,predicate,timeout=45):
            until=time.monotonic()+timeout
            while time.monotonic()<until:
                if any(predicate(e) for e in self.events):return
                if self.core.poll() is not None:raise AssertionError('session ended before expected event')
                time.sleep(.05)
            raise AssertionError('session event timed out')
        def connected(self,generation=1):self.wait(lambda e:e.get('state')=='connected' and e.get('generation',0)>=generation)
        def stop(self):
            if self.core.poll() is None:self.send({'action':'stop'})
            self.core.wait(timeout=20)
            self.reader.join(timeout=2)
            assert any(e.get('cleanupConfirmed') for e in self.events),'cleanup not confirmed'
    def probe(address):
        connection=http.client.HTTPConnection(address,8080,timeout=8)
        connection.request('GET','/')
        assert connection.getresponse().read()==b'GeekOCTunnel isolated Windows lab\n'
        connection.close()
    def passed(name):
        result={'mode':name,'passed':True,'coreUid':account.pw_uid};results.append(result);print(json.dumps(result),flush=True)
    second_proxy=Proxy(44444,os.environ['LAB_GATEWAY_B'])
    threading.Thread(target=second_proxy.serve_forever,daemon=True).start()
    udp_socket=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);udp_socket.bind(('127.0.0.1',4443));udp_socket.setblocking(False)
    udp_enabled=threading.Event();udp_running=threading.Event();udp_running.set()
    udp_peers={}
    def relay_udp():
        with selectors.DefaultSelector() as selector:
            selector.register(udp_socket,selectors.EVENT_READ,None)
            while udp_running.is_set():
                for key,_ in selector.select(.2):
                    if key.fileobj is udp_socket:
                        packet,client=udp_socket.recvfrom(65535)
                        if not udp_enabled.is_set():continue
                        if client not in udp_peers:
                            peer=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);peer.connect((os.environ['LAB_GATEWAY'],4443));peer.setblocking(False)
                            udp_peers[client]=peer;selector.register(peer,selectors.EVENT_READ,client)
                        udp_peers[client].send(packet)
                    else:
                        packet=key.fileobj.recv(65535)
                        if udp_enabled.is_set():udp_socket.sendto(packet,key.data)
    udp_thread=threading.Thread(target=relay_udp,daemon=True);udp_thread.start()
    live=[]
    try:
        first=Session();second=Session(second=True);first.connected();second.connected()
        probe('192.0.2.1');probe('198.51.100.1');passed('parallel-tunnels')
        conflict=Session();conflict.wait(lambda e:e.get('code')=='route_conflict');conflict.stop()
        probe('192.0.2.1');probe('198.51.100.1');passed('live-conflict-preserves-tunnels')
        second.stop();probe('192.0.2.1');passed('disconnect-one-preserves-other')
        forms=first.forms;proxy.interrupt();threading.Timer(3,lambda:setattr(proxy,'paused',False)).start()
        first.wait(lambda e:e.get('state')=='reconnecting');first.connected(2);probe('192.0.2.1')
        assert first.forms==forms;passed('reconnect-reuses-group-and-credentials')
        sequence=first.events[-1]['sequence'];proxy.interrupt();first.wait(lambda e:e.get('state')=='reconnecting' and e.get('sequence',0)>sequence);first.stop();proxy.paused=False;passed('cancel-backoff')
        udp_enabled.set();dtls=Session(udp=True);dtls.connected();dtls.wait(lambda e:e.get('transport')=='DTLS / UDP');probe('192.0.2.1');dtls.stop();passed('dtls-data-path')
        udp_enabled.clear();fallback=Session(udp=True);fallback.connected();fallback.wait(lambda e:e.get('code')=='dtls_fallback',30);probe('192.0.2.1');fallback.stop();passed('udp-fallback-to-tcp')
        manual=Session(reconnect=False);manual.connected();proxy.interrupt();manual.wait(lambda e:e.get('cleanupConfirmed'));manual.stop();proxy.paused=False
        assert not any(e.get('state')=='reconnecting' for e in manual.events);passed('automatic-reconnect-disabled')
    finally:
        for session in live:
            if session.core.poll() is None:
                session.send({'action':'stop'})
                try:session.core.wait(timeout=20)
                except subprocess.TimeoutExpired:session.core.kill();session.core.wait()
        udp_running.clear();udp_thread.join(timeout=2);udp_socket.close()
        for peer in udp_peers.values():peer.close()
        second_proxy.shutdown()
    for _ in range(50):
        if not adapters():break
        time.sleep(.1)
    assert not adapters() and state()==baseline,'advanced sessions changed baseline networking'
pathlib.Path(os.environ.get('GEEKOC_TEST_RESULTS','/work/linux-session-results.json')).write_text(json.dumps(results,indent=2))
proxy.shutdown();polkit.terminate();resolved.terminate()
