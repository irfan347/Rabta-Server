const test=require('node:test');
const assert=require('node:assert/strict');
const express=require('express');
const mount=require('../web-http');

async function fixture(t) {
 const app=express();app.use(express.json({limit:'20kb'}));
 const server=await new Promise(resolve=>{const s=app.listen(0,'127.0.0.1',()=>resolve(s));});
 t.after(()=>{server.closeAllConnections();server.close();});
 const base='http://127.0.0.1:'+server.address().port,origin='http://localhost:'+server.address().port;
 let trusted=true;const commands=[];let relay;
 const phone={readyState:1,rabtaWebReady:true,send:raw=>{commands.push(JSON.parse(raw));}};
 relay=mount({app,origin,authPhone:req=>req.headers.authorization==='Bearer phone'?{id:'alice',device:'phone-a'}:req.headers.authorization==='Bearer other'?{id:'bob',device:'phone-b'}:null,
  deviceAllowed:(user,device)=>trusted&&user==='alice'&&device==='phone-a',phoneSocket:(user,device)=>user==='alice'&&device==='phone-a'?phone:null});
 let cookie='';
 async function browser(route,body={},headers={}) {
  const r=await fetch(base+'/web/api/'+route,{method:'POST',headers:{Origin:origin,'X-Rabta-Web':'1','Content-Type':'application/json',Cookie:cookie,...headers},body:JSON.stringify(body)});
  if(r.headers.get('set-cookie'))cookie=r.headers.get('set-cookie').split(';')[0];
  return {status:r.status,body:await r.json(),headers:r.headers};
 }
 async function native(route,body,method='POST',token='phone') {
  const r=await fetch(base+'/api/web-links'+route,{method,headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},...(method==='GET'?{}:{body:JSON.stringify(body||{})})});return {status:r.status,body:await r.json()};
 }
 async function paired(){const p=await browser('pair',{name:'Laptop'});assert.equal(p.status,200);const a=await native('/approve',{code:p.body.code});assert.equal(a.status,200);return a.body;}
 async function command(){for(let i=0;i<100;i++){if(commands.length)return commands.shift();await new Promise(r=>setTimeout(r,5));}throw Error('Phone command not received');}
 return {app,base,origin,phone,browser,native,paired,command,relay,revokeTrust:()=>{trusted=false}};
}
test('browser origin protection, pending pair, cookie flags and single-use approval',async t=>{
 const f=await fixture(t);
 assert.equal((await f.browser('pair',{}, {Origin:'https://evil.example'})).status,403);
 assert.equal((await f.browser('pair',{}, {'X-Rabta-Web':''})).status,403);
 const p=await f.browser('pair',{name:'Laptop'});assert.match(p.headers.get('set-cookie'),/HttpOnly; SameSite=Strict/);
 assert.match(p.body.qr,/^data:image\/png;base64,/);
 assert.equal((await f.browser('state')).body.linked,false);
 assert.equal((await f.native('/preview',{code:p.body.code})).body.name,'Laptop');
 assert.equal((await f.native('/approve',{code:p.body.code})).status,200);
 assert.equal((await f.native('/approve',{code:p.body.code})).status,404);
 assert.equal((await f.browser('state')).body.phoneOnline,true);
 assert.equal((await f.native('',null,'GET','invalid')).status,401);
});
test('phone relay authenticates exact account/device and browser logout ends access',async t=>{
 const f=await fixture(t);await f.paired();
 const promise=f.browser('command',{op:'chats'});const c=await f.command();
 f.relay.reply('bob','phone-a',{requestId:c.requestId,ok:true,data:{chats:['private']}});
 f.relay.reply('alice','wrong-phone',{requestId:c.requestId,ok:true,data:{chats:['private']}});
 f.relay.reply('alice','phone-a',{requestId:c.requestId,ok:true,data:{chats:[{id:'ABCDEF123456',name:'Friend'}]}});
 const r=await promise;assert.equal(r.status,200);assert.equal(r.body.chats[0].name,'Friend');
 await f.browser('logout');assert.equal((await f.browser('command',{op:'chats'})).status,401);
});
test('offline phone and unsupported operations are rejected without queueing',async t=>{
 const f=await fixture(t);await f.paired();
 assert.equal((await f.browser('command',{op:'delete_account'})).status,400);
 assert.equal((await f.browser('command',{op:'send',peer:'ABCDEF123456',messageId:'ABCDEF654321',text:' '})).status,400);
 f.phone.readyState=3;assert.equal((await f.browser('state')).body.phoneOnline,false);
 assert.equal((await f.browser('command',{op:'chats'})).status,409);
});
test('revocation discards an in-flight response and revoked phone trust expires access',async t=>{
 const f=await fixture(t);const link=await f.paired();
 const pending=f.browser('command',{op:'chats'});const c=await f.command();
 assert.equal((await f.browser('command',{op:'chats'})).status,429);
 await f.native('/'+link.id,{},'DELETE');
 f.relay.reply('alice','phone-a',{requestId:c.requestId,ok:true,data:{chats:['secret']}});
 assert.equal((await pending).status,401);
 await f.paired();f.revokeTrust();assert.equal((await f.browser('state')).status,401);
});
test('static frontend forbids framing and caching and browser secret is not exposed in JSON',async t=>{
 const f=await fixture(t);const r=await fetch(f.base+'/web/');
 assert.equal(r.status,200);assert.equal(r.headers.get('x-frame-options'),'DENY');assert.equal(r.headers.get('cache-control'),'no-store');
 assert.match(await r.text(),/Rabta Web/);
 const p=await f.browser('pair');assert.equal(p.body.secret,undefined);
});
