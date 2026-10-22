const test=require('node:test'),assert=require('node:assert/strict');
const install=require('../message-changes');
const A='AAA000000001',B='BBB000000002',C='CCC000000003',ID='DDD000000004';
const OP='12345678-1234-1234-1234-123456789012';
function fixture() {
 const routes=new Map(),saved=new Map(),notified=[];let time=1000000,fail=false;
 const api=install({app:{get:(p,f)=>routes.set('GET '+p,f),post:(p,f)=>routes.set('POST '+p,f)},authId:r=>r.me,
 users:new Map([A,B,C].map(id=>[id,{}])),getPool:()=>({query:async(sql,args)=>{
  if(fail)throw Error('database unavailable');
  if(sql.startsWith('INSERT'))saved.set(args[0],args[1]);
  if(sql.startsWith('DELETE'))saved.delete(args[0]);
  return {rows:sql.startsWith('SELECT')?[...saved].map(([id,data])=>({id,data})):[]};}}),blocked:()=>false,notify:id=>notified.push(id),now:()=>time});
 async function call(route,me=A,body={}) {let code=200,data;await routes.get(route)({me,body},{status(c){code=c;return this},json(d){data=d}});return {code,data};}
 const record=(overrides={})=>api.record({id:ID,from:A,to:B,type:'text',recipients:[B],...overrides});
 const change=(action='edit',text='updated',operationId=OP,me=A)=>call('POST /api/messages/change',me,{id:ID,action,text,operationId});
 return {api,call,record,change,saved,notified,advance:t=>time+=t,fail:v=>fail=v};
}
test('only original sender can change an accepted message; strangers cannot read its changes',async()=>{
 const f=fixture();await f.record();assert.equal((await f.change('edit','forged',OP,B)).code,404);
 assert.equal((await f.call('GET /api/messages/changes',null)).code,401);
 assert.equal((await f.change()).code,200);
 assert.equal((await f.call('GET /api/messages/changes',C)).data.changes.length,0);
 const event=(await f.call('GET /api/messages/changes',B)).data.changes[0];assert.equal(event.from,A);assert.equal(event.convId,A);assert.equal(event.text,'updated');
});
test('changes are durable before publication, retry IDs are idempotent and conflicting reuse fails',async()=>{
 const f=fixture();await f.record();f.fail(true);assert.equal((await f.change()).code,503);assert.deepEqual(f.notified,[]);
 f.fail(false);assert.equal((await f.change()).data.change.revision,1);assert.equal((await f.change()).data.change.revision,1);
 assert.equal((await f.change('edit','different')).code,409);await f.api.init();assert.equal((await f.call('GET /api/messages/changes',B)).data.changes[0].revision,1);
});
test('old acknowledgments and outsider acknowledgments cannot discard a newer change',async()=>{
 const f=fixture();await f.record();await f.change();await f.change('edit','second',OP.replace('1','2'));
 for(const me of [B,C])await f.call('POST /api/messages/changes/ack',me,{items:[{id:ID,from:A,revision:1}]});
 assert.equal((await f.call('GET /api/messages/changes',B)).data.changes[0].text,'second');
 await f.call('POST /api/messages/changes/ack',B,{items:[{id:ID,from:A,revision:2}]});
 assert.equal((await f.call('GET /api/messages/changes',B)).data.changes.length,0);
 assert.equal((await f.call('GET /api/messages/changes',A)).data.changes.length,1);
});
test('edit window uses server time; deletion is final and changes stay available offline',async()=>{
 const f=fixture();await f.record();f.advance(16*60000);assert.equal((await f.change()).code,400);
 assert.equal((await f.change('delete')).code,200);
 assert.equal((await f.change('edit','revive',OP.replace('1','2'))).code,409);
 f.advance(3*86400000);assert.equal((await f.call('GET /api/messages/changes',B)).data.changes[0].action,'delete');
 f.advance(31*86400000);await f.api.tidy();assert.equal((await f.call('GET /api/messages/changes',B)).data.changes.length,0);
});
test('group recipients are snapshotted and duplicate originals cannot change destination',async()=>{
 const f=fixture();await f.record({to:'GROUP',group:true,recipients:[B,C]});
 await assert.rejects(f.record({to:C}));await f.change();
 assert.equal((await f.call('GET /api/messages/changes',C)).data.changes[0].convId,'GROUP');
 await f.api.forget(C);assert.equal((await f.call('GET /api/messages/changes',C)).data.changes.length,0);
 await f.api.forget(A);assert.equal((await f.call('GET /api/messages/changes',B)).data.changes.length,0);
});
test('media cannot be edited, expired originals cannot be deleted, invalid bodies are rejected',async()=>{
 const f=fixture();await f.record({type:'image'});assert.equal((await f.change()).code,400);
 assert.equal((await f.change('edit','')).code,400);assert.equal((await f.call('POST /api/messages/changes/ack',A,{items:[{}]})).code,400);
 f.advance(49*3600000);assert.equal((await f.change('delete')).code,404);
});
