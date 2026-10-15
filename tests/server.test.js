const test=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const path=require('node:path');

async function fixture(database=false) {
  const routes=new Map(); let connect; const queries=[];
  const app={use(){},post(p,f){routes.set('POST '+p,f)},get(p,f){routes.set('GET '+p,f)}};
  const express=()=>app; express.json=()=>()=>{};
  class WebSocketServer { on(event,cb){if(event==='connection')connect=cb;} }
  class Pool { async query(sql,args){ queries.push([sql,args]); return {rows:[]}; } }
  const context=vm.createContext({require(name){if(name==='express')return express;if(name==='ws')return {WebSocketServer};if(name==='http')return {createServer(){return {listen(){}}}};if(name==='pg')return {Pool};return require(name)},process:{env:database?{DATABASE_URL:'test'}:{}},console,Buffer,URL});
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../index.js'),'utf8'),context);
  const register=async name=>{let value;await routes.get('POST /api/register')({body:{username:name,pin:'123456',name}},{json(v){value=v},status(){return this}});return value;};
  const alice=await register('alice'),bob=await register('bob'),eve=await register('eve');
  function socket(user){const handlers={},messages=[];const ws={readyState:1,send(s){messages.push(JSON.parse(s))},close(){this.readyState=3;handlers.close?.()},on(e,f){handlers[e]=f}};connect(ws,{headers:{authorization:'Bearer '+user.token},url:'/ws'});return {messages,send:async m=>handlers.message(Buffer.from(JSON.stringify(m))),ws};}
  return {socket,alice,bob,eve,queries,routes};
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
