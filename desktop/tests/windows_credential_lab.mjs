import assert from 'node:assert/strict';
import {spawn,execFileSync} from 'node:child_process';
import {createHash,randomUUID} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {createInterface} from 'node:readline';

// The fixture authenticates normally but advertises a default route, so no helper or adapter starts.
assert.equal(process.platform,'win32');
assert.equal(process.env.GEEKOC_ISOLATED_TEST,'1');
const server=process.env.LAB_SERVER;
assert.match(server,/^127\.0\.0\.1:\d+$/);
const credentials=JSON.parse(readFileSync(process.env.LAB_CREDENTIAL_FILE,'utf8'));
const target='GeekOCTunnel/verification-windows/'+createHash('sha256').update(server).digest('hex');
const clear=()=>execFileSync('cmdkey.exe',['/delete:'+target],{stdio:'ignore',windowsHide:true});
let saved=false;
async function login(reuse) {
  const sessionId=randomUUID();
  const arguments_=['--test-server',server,'--ca-file',process.env.LAB_CA_FILE];
  const child=spawn(process.env.GEEKOC_LAUNCHER || process.env.GEEKOC_CORE,process.env.GEEKOC_LAUNCHER ? [process.env.GEEKOC_CORE,...arguments_] : arguments_,{windowsHide:true,stdio:['pipe','pipe','ignore']});
  const events=[],forms={};let failure;
  const send=value=>child.stdin.write(JSON.stringify({sessionId,...value})+'\n');
  child.stdin.on('error',()=>{});
  const ended=new Promise((resolve,reject)=>{child.once('error',reject);child.once('exit',resolve);});
  const timeout=setTimeout(()=>{failure='authentication timeout';child.kill();},25000);
  createInterface({input:child.stdout}).on('line',line=>{
    try {
      const event=JSON.parse(line);events.push(event);
      if(event.event==='credential'&&event.saved)saved=true;
      if(event.event==='authentication') {
        assert.equal(reuse,false,'new Core must restore Group and credentials without another form');
        assert.ok(!event.rejected,'fixture login should succeed');
        const values={};
        for(const field of event.fields) {
          forms[field.name]=(forms[field.name]||0)+1;
          if(field.type==='select') {
            const group=field.choices.find(choice=>choice.value==='ssl-vpn2');
            assert.ok(group,'second SSL group must be offered');values[field.name]=group.value;
          } else values[field.name]=field.secret ? credentials.password : credentials.username;
        }
        send({action:'reply',challengeId:event.challengeId,values,remember:true});
      }
      if(event.event==='certificate'||event.event==='permission') throw new Error('fixture unexpectedly reached certificate or elevation');
    } catch(error) {failure=error.message;send({action:'stop'});}
  });
  send({schemaVersion:1,autoReconnect:false});
  try {
    await ended;
    console.log(JSON.stringify({phase:reuse?'reuse':'save',events:events.map(({event,state,code,saved})=>({event,state,code,saved}))}));
    assert.ok(!failure,failure);
    assert.ok(events.some(event=>event.code==='authenticated'),'gateway must accept login');
    assert.ok(events.some(event=>event.code==='full_tunnel_not_supported'),'fixture must stop before network setup');
    assert.ok(events.some(event=>event.cleanupConfirmed),'clean shutdown');
    if(reuse) assert.equal(Object.keys(forms).length,0);
    else {
      assert.ok(events.some(event=>event.event==='credential'&&event.saved),'system credential save must succeed');
      assert.equal(forms.username,1);assert.equal(forms.password,1);
    }
    console.log(JSON.stringify({test:reuse?'fresh-process-restores-group-and-login':'save-login-and-second-group',passed:true}));
  } finally {clearTimeout(timeout);if(child.exitCode===null)child.kill();}
}
// Only this fixed synthetic profile/loopback endpoint is touched; real profiles are never loaded.
try {try{clear();}catch{}await login(false);await login(true);await login(true);}
finally {if(saved)clear();}
