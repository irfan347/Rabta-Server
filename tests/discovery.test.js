const test=require('node:test');const assert=require('node:assert/strict');
const discovery=require('../discovery');
function fixture(){
 const routes=new Map(),queries=[];
 const users=new Map([['a',{id:'a',username:'alice',name:'Alice',phone_number:'+923001234567',phone_discoverable:false}],['b',{id:'b',username:'bobby',name:'Bob',phone_number:'+923001234568',phone_discoverable:true}],['c',{id:'c',username:'bobblocked',name:'Blocked',phone_number:'+923001234568',phone_discoverable:true}]]);
 let fail=false;
 discovery({app:{get:(p,f)=>routes.set('GET '+p,f),post:(p,f)=>routes.set('POST '+p,f)},authId:r=>r.me,users,blocked:(a,b)=>b==='c',getPool:()=>({query:async(...q)=>{if(fail)throw Error('db');queries.push(q)}})});
 async function call(route,body={},query={},me='a'){let code=200,data;await routes.get(route)({body,query,me},{status(n){code=n;return this},json(x){data=x;return this}});return {code,data};}
 return {call,users,queries,fail:()=>fail=true};
}
test('prefix search requires auth, is bounded and hides blocked/private metadata',async()=>{
 const f=fixture();assert.equal((await f.call('GET /api/contacts/search',{}, {q:'bo'},null)).code,401);
 const r=await f.call('GET /api/contacts/search',{}, {q:'bo'});assert.deepEqual(r.data.users,[{id:'b',username:'bobby',name:'Bob'}]);
 assert.equal((await f.call('GET /api/contacts/search',{}, {q:'b'})).code,400);
});
test('phone matching only exposes opted-in nonblocked candidates and labels unverified',async()=>{
 const f=fixture();const r=await f.call('POST /api/contacts/match',{numbers:['+923001234567','+923001234568']});
 assert.equal(r.data.users.length,1);assert.equal(r.data.users[0].id,'b');assert.equal(r.data.users[0].verified,false);
 assert.equal((await f.call('POST /api/contacts/match',{numbers:['bad']})).code,400);
 assert.equal((await f.call('POST /api/contacts/match',{numbers:Array(501).fill('+923001234568')})).code,400);
});
test('phone preference is durable before publication and cannot overwrite another account',async()=>{
 const f=fixture();assert.equal((await f.call('POST /api/me/contact-details',{phoneNumber:'+923009876543',discoverable:true,id:'b'})).code,200);
 assert.equal(f.users.get('a').phone_number,'+923009876543');assert.equal(f.users.get('b').phone_number,'+923001234568');assert.equal(f.queries[0][1][2],'a');
 f.fail();assert.equal((await f.call('POST /api/me/contact-details',{phoneNumber:'+923009876540',discoverable:false})).code,503);
 assert.equal(f.users.get('a').phone_discoverable,true);
});
test('search and matching share a per-account rate limit',async()=>{
 const f=fixture();for(let i=0;i<30;i++)assert.equal((await f.call('GET /api/contacts/search',{}, {q:'bo'})).code,200);
 assert.equal((await f.call('POST /api/contacts/match',{numbers:[]})).code,429);
});
