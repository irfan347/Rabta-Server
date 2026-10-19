const test=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');

async function fixture(database=false) {
  const routes=new Map(); let connect; const queries=[]; const timers=new Set(); const intervals=[]; const wakes=[];
  const setTimeout=(fn,ms)=>{const t={fn,ms,unref(){}};timers.add(t);return t;};const clearTimeout=t=>timers.delete(t);
  const app={use(){},post(p,f){routes.set('POST '+p,f)},get(p,f){routes.set('GET '+p,f)},delete(p,f){routes.set('DELETE '+p,f)}};
  const express=()=>app; express.json=()=>()=>{};
  class WebSocketServer { on(event,cb){if(event==='connection')connect=cb;} }
  let failing=null,responder=null; // SQL that should throw / custom rows, for storage tests
  class Pool { async query(sql,args){ queries.push([sql,args]); if(failing?.test(sql)) throw new Error('db unavailable'); return {rows:responder?.(sql,args)||[]}; } }
  const context=vm.createContext({require(name){if(name==='./features')return require('../features');if(name==='./account')return require('../account');if(name==='./push')return ()=>({init:async()=>{},enabled:()=>false,wake:(id,kind='message')=>wakes.push([id,kind])});if(name==='express')return express;if(name==='ws')return {WebSocketServer};if(name==='http')return {createServer(){return {listen(){}}}};if(name==='pg')return {Pool};return require(name)},setTimeout,clearTimeout,setInterval:fn=>{intervals.push(fn);return {unref(){}};},process:{env:database?{DATABASE_URL:'test'}:{}},console,Buffer,URL});
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../index.js'),'utf8'),context);
  const register=async name=>{let value;await routes.get('POST /api/register')({body:{username:name,pin:'123456',name}},{json(v){value=v},status(){return this}});return value;};
  const alice=await register('alice'),bob=await register('bob'),eve=await register('eve');
  function socket(user,{broken=false}={}){const handlers={},messages=[];const ws={readyState:1,pings:0,send(s,cb){if(broken)return cb?.(new Error('socket write failed'));messages.push(JSON.parse(s));cb?.()},close(){this.readyState=3;handlers.close?.()},terminate(){this.close()},ping(){this.pings++},on(e,f){handlers[e]=f}};connect(ws,{headers:{authorization:'Bearer '+user.token},url:'/ws'});return {messages,send:async m=>handlers.message(Buffer.from(JSON.stringify(m))),pong:()=>handlers.pong?.(),ws};}
  return {socket,alice,bob,eve,queries,routes,register,wakes,failSql:re=>{failing=re},onSql:fn=>{responder=fn},expire:()=>{for(const t of [...timers])t.fn();},beat:()=>intervals.forEach(fn=>fn())};
}
test('large offline attachment survives intact and retries do not duplicate',async()=>{
  const f=await fixture();const a=f.socket(f.alice);const body='x'.repeat(500000);
  const m={type:'msg',id:'ABCDEF123456',to:f.bob.id,body,msgType:'video'};
  await a.send(m);await a.send(m);const b=f.socket(f.bob);
  const msgs=b.messages.filter(m=>m.type==='msg');assert.equal(msgs.length,1);assert.equal(msgs[0].body,body);
});
test('database storage receives complete attachment, not truncated bytes',async()=>{
  const f=await fixture(true);const a=f.socket(f.alice);const body='y'.repeat(500000);
  await a.send({type:'msg',id:'ABCDEF123456',to:f.bob.id,body,msgType:'file'});
  const insert=f.queries.find(([sql])=>sql.startsWith('INSERT INTO ru_messages'));
  assert.equal(insert[1][4],body);
});
test('oversized and unknown types receive error receipts',async()=>{
  const f=await fixture();const a=f.socket(f.alice);
  await a.send({type:'msg',id:'ABCDEF123456',to:f.bob.id,body:'x'.repeat(12*1024*1024+1),msgType:'file'});
  await a.send({type:'msg',id:'ABCDEF123457',to:f.bob.id,body:'x',msgType:'script'});
  assert.equal(a.messages.filter(m=>m.status==='error').length,2);
});
test('video signaling only relays between registered call participants',async()=>{
  const f=await fixture();const a=f.socket(f.alice),b=f.socket(f.bob),e=f.socket(f.eve);
  await a.send({type:'video_offer',callId:'AAAABBBBCCCC',to:f.bob.id,sdp:'offer'});
  await e.send({type:'ice_candidate',callId:'AAAABBBBCCCC',to:f.bob.id,sdp:'attacker'});
  await b.send({type:'video_answer',callId:'AAAABBBBCCCC',to:f.alice.id,sdp:'answer'});
  assert.equal(b.messages.filter(m=>m.type==='video_offer').length,1);
  assert.equal(b.messages.filter(m=>m.sdp==='attacker').length,0);
  assert.equal(a.messages.filter(m=>m.type==='video_answer').length,1);
});
test('offline group attachment retains group identity',async()=>{
  const f=await fixture();let group;
  await f.routes.get('POST /api/groups')({headers:{authorization:'Bearer '+f.alice.token},body:{name:'Family',members:[f.bob.id]}},{json(v){group=v},status(){return this}});
  const a=f.socket(f.alice);await a.send({type:'group_msg',id:'AAAABBBBCCCC',to:group.id,body:'sticker-data',msgType:'sticker'});
  const b=f.socket(f.bob);const m=b.messages.find(m=>m.type==='group_msg');assert.equal(m.groupId,group.id);assert.equal(m.groupName,'Family');
});

test('offline caller stays calling; receiver acknowledgement starts ringing',async()=>{
  const f=await fixture(); const a=f.socket(f.alice); const id='112233445566';
  await a.send({type:'call_init',callId:id,to:f.bob.id});
  assert.equal(a.messages.at(-1).state,'calling');
  const b=f.socket(f.bob); assert.equal(b.messages.at(-1).type,'incoming_call');
  assert.equal(a.messages.at(-1).state,'calling');
  await b.send({type:'call_ringing',callId:id,to:f.alice.id}); assert.equal(a.messages.at(-1).state,'ringing');
  await b.send({type:'call_accept',callId:id,to:f.alice.id}); assert.equal(a.messages.at(-1).state,'accepted');
});
test('audio cannot be relayed until answer, and outsiders cannot ring or end calls',async()=>{
  const f=await fixture();const a=f.socket(f.alice),b=f.socket(f.bob),e=f.socket(f.eve);const id='112233445566';
  await a.send({type:'call_init',callId:id,to:f.bob.id});
  await a.send({type:'call_audio',callId:id,to:f.bob.id,data:'AAAA'});
  assert.equal(b.messages.filter(m=>m.type==='call_audio').length,0);
  await e.send({type:'call_ringing',callId:id,to:f.alice.id});
  await e.send({type:'call_end',callId:id,to:f.alice.id});
  assert.equal(a.messages.at(-1).state,'calling');
  await b.send({type:'call_accept',callId:id,to:f.alice.id});
  await a.send({type:'call_audio',callId:id,to:f.bob.id,data:'AAAA'});
  assert.equal(b.messages.at(-1).type,'call_audio');
});
test('unanswered calls expire and never ring after late reconnection',async()=>{
  const f=await fixture();const a=f.socket(f.alice);
  await a.send({type:'call_init',callId:'112233445566',to:f.bob.id});f.expire();
  assert.equal(a.messages.at(-1).type,'call_end');
  assert.equal(f.socket(f.bob).messages.filter(m=>m.type==='incoming_call').length,0);
});
test('offline video offer and ICE wait for receiver and answer',async()=>{
  const f=await fixture();const a=f.socket(f.alice);const id='112233445566';
  await a.send({type:'video_offer',callId:id,to:f.bob.id,sdp:'offer'});
  await a.send({type:'ice_candidate',callId:id,to:f.bob.id,sdp:'ice',sdpMid:'0',sdpMLineIndex:0});
  assert.equal(a.messages.at(-1).state,'calling');
  const b=f.socket(f.bob);assert.equal(b.messages.at(-1).type,'video_offer');
  await b.send({type:'call_ringing',callId:id,to:f.alice.id});assert.equal(a.messages.at(-1).state,'ringing');
  await b.send({type:'video_answer',callId:id,to:f.alice.id,sdp:'answer'});
  assert.equal(b.messages.at(-1).type,'ice_candidate');
});

const settle=()=>new Promise(r=>setImmediate(r));
const hexId=n=>n.toString(16).toUpperCase().padStart(12,'0');
test('offline messages stay pending when the socket write fails',async()=>{
  const f=await fixture();const a=f.socket(f.alice);
  await a.send({type:'msg',id:'ABCDEF123456',to:f.bob.id,body:'hello'});
  const lost=f.socket(f.bob,{broken:true});await settle();lost.ws.close();
  const b=f.socket(f.bob);await settle();
  assert.deepEqual(b.messages.filter(m=>m.type==='msg').map(m=>m.body),['hello']);
  const again=f.socket(f.bob);await settle();
  assert.equal(again.messages.filter(m=>m.type==='msg').length,0);
});
test('a failed live write is stored for later instead of acknowledged as delivered',async()=>{
  const f=await fixture();const lost=f.socket(f.bob,{broken:true});const a=f.socket(f.alice);
  await a.send({type:'msg',id:'ABCDEF123456',to:f.bob.id,body:'hello'});
  assert.equal(a.messages.at(-1).status,'stored');
  lost.ws.close();const b=f.socket(f.bob);await settle();
  assert.equal(b.messages.filter(m=>m.type==='msg').length,1);
});
test('offline backlog larger than one batch is fully delivered in order',async()=>{
  const f=await fixture();const a=f.socket(f.alice);
  for(let i=1;i<=450;i++) await a.send({type:'msg',id:hexId(i),to:f.bob.id,body:'m'+i});
  const b=f.socket(f.bob);await settle();
  const bodies=b.messages.filter(m=>m.type==='msg').map(m=>m.body);
  assert.equal(bodies.length,450);assert.equal(bodies[0],'m1');assert.equal(bodies.at(-1),'m450');
});
test('registration fails visibly and keeps nothing when the database insert fails',async()=>{
  const f=await fixture(true);f.failSql(/INSERT INTO ru_users/);
  let status=200,body;const res={status(n){status=n;return this},json(v){body=v}};
  await f.routes.get('POST /api/register')({body:{username:'zara',pin:'123456',name:'Zara'}},res);
  assert.equal(status,503);assert.ok(!body.token);
  f.failSql(null);const retry=await f.register('zara');assert.ok(retry.token);
});
test('group creation fails visibly when the database insert fails',async()=>{
  const f=await fixture(true);f.failSql(/INSERT INTO rg_groups/);const b=f.socket(f.bob);
  let status=200,body;const res={status(n){status=n;return this},json(v){body=v}};
  await f.routes.get('POST /api/groups')({headers:{authorization:'Bearer '+f.alice.token},body:{name:'Family',members:[f.bob.id]}},res);
  assert.equal(status,503);assert.ok(!body.id);
  assert.equal(b.messages.filter(m=>m.type==='group_created').length,0);
});
test('database backlog larger than one batch is fully delivered and marked',async()=>{
  const f=await fixture(true);const rows=[];
  for(let i=1;i<=450;i++) rows.push({id:i,msg_id:hexId(i),from_id:f.alice.id,from_name:'alice',to_id:f.bob.id,body:'m'+i,type:'text',sent_at:i,delivered_at:null});
  f.onSql((sql,args)=>{
    if(sql.startsWith('SELECT * FROM ru_messages')){const limit=Number(/LIMIT (\d+)/.exec(sql)[1]);return rows.filter(r=>r.to_id===args[0]&&!r.delivered_at&&r.id>(args[1]||0)).slice(0,limit);}
    if(sql.startsWith('UPDATE ru_messages SET delivered_at')) rows.filter(r=>args[1].includes(r.id)).forEach(r=>{r.delivered_at=args[0];if(/body=NULL/.test(sql))r.body=null});
  });
  const b=f.socket(f.bob);for(let i=0;i<5;i++) await settle();
  assert.equal(b.messages.filter(m=>m.type==='msg').length,450);
  assert.equal(rows.filter(r=>!r.delivered_at).length,0);
  assert.equal(rows.filter(r=>r.body!==null).length,0,'delivered messages keep no content on the server');
});
test('a delivered message keeps no content on the server, and sending it again does not deliver it twice',async()=>{
  const f=await fixture();const a=f.socket(f.alice);const m={type:'msg',id:'ABCDEF654321',to:f.bob.id,body:'voice-data',msgType:'voice'};
  await a.send(m);const b=f.socket(f.bob);await settle();b.ws.close();
  assert.deepEqual(b.messages.filter(x=>x.type==='msg').map(x=>x.body),['voice-data']);
  await a.send(m);assert.equal(a.messages.at(-1).status,'stored');
  const again=f.socket(f.bob);await settle();
  assert.equal(again.messages.filter(x=>x.type==='msg').length,0,'recognised by its marker');
});
test('now and then the server forgets markers of messages delivered over 30 days ago, and only those',async()=>{
  const f=await fixture(true);f.beat();await settle();
  const forget=f.queries.find(([sql])=>sql.startsWith('DELETE FROM ru_messages'));
  assert.ok(forget,'markers are cleaned up');assert.match(forget[0],/body IS NULL AND delivered_at < \$1/);
  assert.ok(Math.abs(forget[1][0]-(Date.now()-30*24*60*60*1000))<60000);
});
test('recipient receipt reaches an online sender; server acks keep their existing meaning',async()=>{
  const f=await fixture();const a=f.socket(f.alice),b=f.socket(f.bob);
  assert.equal(a.messages[0].receipts,true);
  await a.send({type:'msg',id:'ABCDEF123456',to:f.bob.id,body:'hello'});
  assert.equal(a.messages.at(-1).status,'delivered');assert.equal(a.messages.filter(m=>m.type==='receipt').length,0);
  await b.send({type:'receipt',id:'ABCDEF123456',to:f.alice.id});
  assert.deepEqual(a.messages.filter(m=>m.type==='receipt'),[{type:'receipt',id:'ABCDEF123456',by:f.bob.id}]);
});
test('receipt for an offline sender is kept until it reconnects, then delivered once',async()=>{
  const f=await fixture();const a=f.socket(f.alice);
  await a.send({type:'msg',id:'ABCDEF123456',to:f.bob.id,body:'hello'});a.ws.close();
  const b=f.socket(f.bob);await settle();
  await b.send({type:'receipt',id:'ABCDEF123456',to:f.alice.id});await b.send({type:'receipt',id:'ABCDEF123456',to:f.alice.id});
  const lost=f.socket(f.alice,{broken:true});await settle();lost.ws.close();
  const back=f.socket(f.alice);await settle();
  assert.equal(back.messages.filter(m=>m.type==='receipt'&&m.by===f.bob.id).length,1);
  const again=f.socket(f.alice);await settle();
  assert.equal(again.messages.filter(m=>m.type==='receipt').length,0);
});
test('malformed, self, unknown and blocked receipts are dropped',async()=>{
  const f=await fixture();const a=f.socket(f.alice),b=f.socket(f.bob);
  await b.send({type:'receipt',id:'not-an-id',to:f.alice.id});
  await b.send({type:'receipt',id:'ABCDEF123456',to:'FFFFFFFFFFFF'});
  await a.send({type:'receipt',id:'ABCDEF123456',to:f.alice.id});
  await f.routes.get('POST /api/block')({headers:{authorization:'Bearer '+f.alice.token},body:{target:f.bob.id}},{json(){},status(){return this}});
  await b.send({type:'receipt',id:'ABCDEF123457',to:f.alice.id});
  assert.equal(a.messages.filter(m=>m.type==='receipt').length,0);
});
test('database keeps receipts for an offline sender and removes them once written',async()=>{
  const f=await fixture(true);const rows=[];
  f.onSql((sql,args)=>{
    if(sql.startsWith('INSERT INTO ru_receipts'))rows.push({msg_id:args[0],by_id:args[1],to_id:args[2]});
    if(sql.startsWith('SELECT msg_id,by_id FROM ru_receipts'))return rows.filter(r=>r.to_id===args[0]);
    if(sql.startsWith('DELETE FROM ru_receipts'))for(let i=rows.length-1;i>=0;i--)if(rows[i].to_id===args[0]&&args[1].some((m,j)=>m===rows[i].msg_id&&args[2][j]===rows[i].by_id))rows.splice(i,1);
  });
  const b=f.socket(f.bob);await b.send({type:'receipt',id:'ABCDEF123456',to:f.alice.id});
  assert.equal(rows.length,1);
  const a=f.socket(f.alice);await settle();await settle();
  assert.equal(a.messages.filter(m=>m.type==='receipt').length,1);assert.equal(rows.length,0);
});
test('contact lookup by account ID needs sign-in and hides blocked contacts',async()=>{
  const f=await fixture();const get=f.routes.get('GET /api/user/id/:id');
  const call=async(token,id)=>{const r={code:200,status(n){this.code=n;return this},json(v){this.body=v;return this}};await get({headers:token?{authorization:'Bearer '+token}:{},params:{id},query:{}},r);return r;};
  assert.equal((await call(null,f.bob.id)).code,401);
  const found=await call(f.alice.token,f.bob.id.toLowerCase());assert.equal(found.code,200);assert.equal(found.body.username,'bob');assert.equal(found.body.id,f.bob.id);assert.equal(found.body.pass_hash,undefined);
  assert.equal((await call(f.alice.token,'FFFFFFFFFFFF')).code,404);
  await f.routes.get('POST /api/block')({headers:{authorization:'Bearer '+f.bob.token},body:{target:f.alice.id},params:{},query:{}},{status(){return this},json(){},setHeader(){}});
  assert.equal((await call(f.alice.token,f.bob.id)).code,404);
});

test('heartbeat closes sockets whose phone stopped answering, so their messages wait on the server',async()=>{
  const f=await fixture();const a=f.socket(f.alice),b=f.socket(f.bob),e=f.socket(f.eve);
  f.beat();assert.equal(a.ws.pings,1);assert.equal(b.ws.pings,1);
  b.pong();await e.send({type:'ping'});          // Bob's phone answers the ping, Eve's sends something; Alice's is gone
  f.beat();
  assert.equal(a.ws.readyState,3,'silent socket closed');assert.equal(b.ws.readyState,1);assert.equal(e.ws.readyState,1);
  await b.send({type:'msg',id:'AAAAAA000111',to:f.alice.id,body:'are you there',msgType:'text'});
  assert.equal(b.messages.filter(m=>m.type==='ack').at(-1).status,'stored');
  const again=f.socket(f.alice);assert.ok(again.messages.some(m=>m.type==='msg'&&m.body==='are you there'),'delivered on reconnect');
  assert.equal(e.messages.filter(m=>m.type==='pong').length,1);
});

test('a message or call for a phone that is not connected wakes it; a connected phone is not woken',async()=>{
  const f=await fixture();const a=f.socket(f.alice);let group;
  await a.send({type:'msg',id:'ABCDEF123456',to:f.bob.id,body:'hello'});
  assert.deepEqual(f.wakes,[[f.bob.id,'message']]);
  await f.routes.get('POST /api/groups')({headers:{authorization:'Bearer '+f.alice.token},body:{name:'Family',members:[f.bob.id]}},{json(v){group=v},status(){return this}});
  await a.send({type:'group_msg',id:'AAAABBBBCCCC',to:group.id,body:'hi all'});
  assert.deepEqual(f.wakes.at(-1),[f.bob.id,'message']);
  await a.send({type:'call_init',callId:'112233445566',to:f.bob.id});
  assert.deepEqual(f.wakes.at(-1),[f.bob.id,'call']);
  const e=f.socket(f.eve);f.wakes.length=0;
  await a.send({type:'msg',id:'ABCDEF123457',to:f.eve.id,body:'hi'});
  assert.equal(f.wakes.length,0,'Connected phones get the message directly');
  assert.equal(e.messages.filter(m=>m.type==='msg').length,1);
  let health;f.routes.get('GET /health')({},{json(v){health=v}});
  assert.equal(health.push,false,'The health check says whether wake pushes are on');
});
