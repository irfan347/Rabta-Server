const crypto=require('crypto');
module.exports=function install({app,authId,users,getPool}) {
  const statuses=new Map(),uploads=new Map(),blocks=new Set(),grants=new Map(),reports=[],photos=new Map();
  // Who a file was sent to (file -> people), who has saved it on their phone (file -> person -> when), and when it was last used.
  const sharedWith=new Map(),holders=new Map(),used=new Map();
  const busy=new Set();
  async function exclusive(key,fn){ensure(!busy.has(key),"Upload busy; retry from saved offset");busy.add(key);try{return await fn()}finally{busy.delete(key)}}
  const LIFE=86400000, SETTLE=10*60*1000, MAX=8*1024*1024, CHUNK=128*1024, PHOTO_MAX=256*1024, PNG=Buffer.from([0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a]);
  const id=()=>crypto.randomBytes(16).toString('hex');
  const blocked=(a,b)=>blocks.has(a+':'+b)||blocks.has(b+':'+a);
  let since=Date.now(),swept=0; // files from before the server cleaned up after itself are kept as they were
  const index=(map,key,make)=>{const at=key.indexOf(':'),file=key.slice(0,at);if(!map.has(file))map.set(file,make());return [map.get(file),key.slice(at+1)]};
  const share=key=>{const [people,to]=index(sharedWith,key,()=>new Set());people.add(to)};
  const hold=(key,when)=>{const [people,who]=index(holders,key,()=>new Map());people.set(who,when)};
  const media=s=>{try{return JSON.parse(s.body).remote}catch{return null}};
  async function persist(kind,key,value) {
    const db=getPool(); if(db) await db.query('INSERT INTO rf_data(kind,id,data) VALUES($1,$2,$3) ON CONFLICT(kind,id) DO UPDATE SET data=$3',[kind,key,JSON.stringify(value)]);
  }
  async function remove(kind,key) { const db=getPool();if(db)await db.query('DELETE FROM rf_data WHERE kind=$1 AND id=$2',[kind,key]); }
  async function init() {
    const db=getPool(); if(!db)return;
    await db.query('CREATE TABLE IF NOT EXISTS rf_data(kind TEXT,id TEXT,data TEXT,PRIMARY KEY(kind,id))');
    let start=0;
    for(const row of (await db.query('SELECT kind,id,data FROM rf_data')).rows) {
      try { const value=JSON.parse(row.data);if(row.kind==='status'&&value.expires>Date.now())statuses.set(row.id,value);if(row.kind==='upload')uploads.set(row.id,value);if(row.kind==='grant'){grants.set(row.id,value);share(row.id)}if(row.kind==='got')hold(row.id,Number(value));if(row.kind==='block')blocks.add(row.id);if(row.kind==='photo')photos.set(row.id,value);if(row.kind==='meta'&&row.id==='cleanup_since')start=Number(value); }catch{}
    }
    if(start)since=start;else await persist('meta','cleanup_since',since);
  }
  async function cleanup(force=false){const now=Date.now();for(const [key,s] of statuses)if(s.expires<=now){statuses.delete(key);await remove('status',key)};for(const [key,u] of uploads)if(!u.done&&u.created<now-LIFE&&!busy.has(key)){uploads.delete(key);await remove('upload',key)};while(reports.length&&reports[0].created<now-3600000)reports.shift();
    if(force||now-swept>=60000){swept=now;await sweep(now).catch(e=>console.error('File clean-up failed:',e.message))}}
  // A finished file stays on the server only while someone still needs it: a person it was sent to who has not saved
  // it on their phone yet, or an update on show to someone who has not. Phones keep their own copies, so nobody
  // notices it leave. It waits a little after its last use, so a send or download under way finishes first; a file
  // that was never sent anywhere waits a day.
  async function sweep(now){
    const showing=new Map();
    for(const s of statuses.values())if(s.kind!=='text'&&s.expires>now){const file=media(s);if(!showing.has(file))showing.set(file,[]);showing.get(file).push(s)}
    for(const u of [...uploads.values()]){
      if(!u.done||busy.has(u.id)||(u.created<since&&!holders.has(u.id)))continue;
      const shows=showing.get(u.id)||[],has=to=>holders.get(u.id)?.has(to),counts=(owner,to)=>users.has(to)&&!blocked(owner,to);
      const waiting=[...(sharedWith.get(u.id)||[])].some(to=>{const g=grants.get(u.id+':'+to);return(g===0||g>now)&&counts(u.owner,to)&&!has(to)})
        ||shows.some(s=>!s.ownCopy||s.audience.some(to=>counts(s.owner,to)&&!has(to)));
      const known=shows.length>0||sharedWith.has(u.id)||holders.has(u.id);
      if(!waiting&&now-(used.get(u.id)||u.created)>=(known?SETTLE:LIFE))await drop(u);
    }
  }
  async function drop(u){
    const db=getPool();if(db)await db.query("DELETE FROM rf_data WHERE (kind='upload' AND id=$1) OR (kind IN ('grant','got') AND id LIKE $2)",[u.id,u.id+':%']);
    uploads.delete(u.id);for(const to of sharedWith.get(u.id)||[])grants.delete(u.id+':'+to);sharedWith.delete(u.id);holders.delete(u.id);used.delete(u.id);
  }
  const wrap=fn=>async(req,res)=>{const me=authId(req);if(!me)return res.status(401).json({error:'Login required'});try{await cleanup();await fn(req,res,me)}catch(e){res.status(e.code||400).json({error:e.message||'Request failed'})}};
  const ensure=(v,message)=>{if(!v)throw new Error(message)};
  async function grant(body,from,to,expires=0) {
    let o;try{o=JSON.parse(body)}catch{return}
    if(!o.remote)return;
    const u=uploads.get(o.remote);ensure(u&&u.done&&canRead(o.remote,from),'Upload unavailable');
    ensure(o.sha256===u.sha&&Number(o.size)===u.size&&o.name===u.name&&o.mime===u.mime,'Invalid file metadata');
    used.set(u.id,Date.now());
    if(expires)return; // Status access is derived from the active status itself, so deletion revokes it.
    const key=o.remote+':'+to;const existing=grants.get(key);
    const expiry=existing===0?0:expires;
    await persist('grant',key,expiry);grants.set(key,expiry);share(key);
  }
  function canRead(file,me) {
    const u=uploads.get(file),g=grants.get(file+':'+me);if(!u)return false;if(u.owner===me)return true;if(blocked(u.owner,me))return false;
    if(g!==undefined&&(g===0||g>Date.now()))return true;
    return [...statuses.values()].some(s=>s.expires>Date.now()&&s.audience.includes(me)&&!blocked(s.owner,me)&&s.kind!=='text'&&JSON.parse(s.body).remote===file);
  }
  function validMedia(body,me) {const o=JSON.parse(body),u=uploads.get(o.remote);ensure(u&&u.done&&u.owner===me,'Upload media first');return JSON.stringify({remote:u.id,name:u.name,mime:u.mime,size:u.size,sha256:u.sha});}
  app.get('/api/ice',wrap(async(req,res)=>{
    const servers=[{urls:['stun:stun.l.google.com:19302']}];
    if(process.env.TURN_URL&&process.env.TURN_SECRET){const username=(Math.floor(Date.now()/1000)+3600)+':'+authId(req);servers.push({urls:process.env.TURN_URL.split(','),username,credential:crypto.createHmac('sha1',process.env.TURN_SECRET).update(username).digest('base64')});}
    res.json({servers,relayConfigured:servers.length>1});
  }));
  app.post('/api/me/photo',wrap(async(req,res,me)=>{const photo=String(req.body.photo||'');
    if(!photo){await remove('photo',me);photos.delete(me);return res.json({ok:true});}
    ensure(/^[A-Za-z0-9+/]+={0,2}$/.test(photo),'Invalid photo');const bytes=Buffer.from(photo,'base64');
    ensure(bytes.length>0&&bytes.length<=PHOTO_MAX,'Photo must be up to 256 KB');ensure((bytes[0]===0xff&&bytes[1]===0xd8)||bytes.subarray(0,8).equals(PNG),'Photo must be JPEG or PNG');
    await persist('photo',me,photo);photos.set(me,photo);res.json({ok:true});}));
  app.get('/api/user/photo/:id',wrap(async(req,res,me)=>{const owner=String(req.params.id||'').toUpperCase(),photo=photos.get(owner);if(!photo||blocked(owner,me))return res.status(404).json({error:'No photo'});res.json({photo});}));
  app.get('/api/privacy',wrap(async(req,res,me)=>res.json({blocked:[...blocks].filter(x=>x.startsWith(me+':')).map(x=>x.split(':')[1])})));
  app.post('/api/block',wrap(async(req,res,me)=>{const target=String(req.body.target||'');ensure(users.has(target)&&target!==me,'Invalid contact');const key=me+':'+target;
    if(req.body.blocked===false){await remove('block',key);blocks.delete(key)}else{await persist('block',key,true);blocks.add(key)}res.json({ok:true});}));
  app.post('/api/report',wrap(async(req,res,me)=>{ensure(users.has(req.body.target)&&req.body.target!==me,'Invalid contact');const reason=String(req.body.reason||'').trim();ensure(reason.length>=5&&reason.length<=1000,'Describe the issue (5–1000 characters)');
    ensure(reports.filter(r=>r.reporter===me&&r.created>Date.now()-3600000).length<10,'Please try later');const r={id:id(),reporter:me,target:req.body.target,reason,created:Date.now()};await persist('report',r.id,r);reports.push(r);res.json({id:r.id,status:'received'});}));
  app.post('/api/uploads',wrap(async(req,res,me)=>exclusive('owner:'+me,async()=>{
    const size=Number(req.body.size),sha=String(req.body.sha256||'');ensure(Number.isInteger(size)&&size>0&&size<=MAX&&/^[a-f0-9]{64}$/.test(sha),'File must be up to 8 MB');
    const existing=[...uploads.values()].find(u=>u.owner===me&&u.sha===sha&&u.size===size);if(existing){used.set(existing.id,Date.now());return res.json({id:existing.id,received:existing.received,done:existing.done,chunkSize:CHUNK})}
    ensure([...uploads.values()].filter(u=>u.owner===me).reduce((n,u)=>n+u.size,0)+size<=64*1024*1024,'Account upload storage is full (64 MB)');
    const u={id:id(),owner:me,size,sha,name:String(req.body.name||'file').replace(/[\/\\\x00-\x1f]/g,'_').slice(0,120),mime:String(req.body.mime||'application/octet-stream').slice(0,100),received:0,chunks:[],done:false,created:Date.now()};await persist('upload',u.id,u);uploads.set(u.id,u);res.json({id:u.id,received:0,done:false,chunkSize:CHUNK});
  })));
  app.get('/api/uploads/:id',wrap(async(req,res,me)=>{const u=uploads.get(req.params.id);ensure(u&&u.owner===me,'Upload not found');used.set(u.id,Date.now());res.json({id:u.id,received:u.received,done:u.done,chunkSize:CHUNK});}));
  app.post('/api/uploads/:id/chunks',wrap(async(req,res,me)=>exclusive(req.params.id,async()=>{
    const u=uploads.get(req.params.id);ensure(u&&u.owner===me,'Upload not found');const offset=Number(req.body.offset),data=String(req.body.data||'');ensure(data.length<=CHUNK*4/3+4&&/^[A-Za-z0-9+/]*={0,2}$/.test(data),'Invalid chunk');const bytes=Buffer.from(data,'base64');ensure(bytes.length>0&&bytes.length<=CHUNK,'Invalid chunk size');
    if(offset<u.received){const old=u.chunks.find(c=>c.offset===offset);ensure(old&&old.data===data,'Chunk differs from saved data');return res.json({received:u.received});}
    ensure(!u.done&&offset===u.received&&offset+bytes.length<=u.size,'Resume from server offset');
    const next={...u,chunks:[...u.chunks,{offset,data}],received:u.received+bytes.length};
    await persist('upload',u.id,next);uploads.set(u.id,next);res.json({received:next.received});
  })));
  app.post('/api/uploads/:id/complete',wrap(async(req,res,me)=>exclusive(req.params.id,async()=>{const u=uploads.get(req.params.id);ensure(u&&u.owner===me&&u.received===u.size,'Upload incomplete');const bytes=Buffer.concat(u.chunks.map(c=>Buffer.from(c.data,'base64')));ensure(crypto.createHash('sha256').update(bytes).digest('hex')===u.sha,'File checksum failed');const next={...u,done:true};await persist('upload',u.id,next);uploads.set(u.id,next);used.set(u.id,Date.now());res.json({remote:u.id,name:u.name,mime:u.mime,size:u.size,sha256:u.sha});})));
  app.delete('/api/uploads/:id',wrap(async(req,res,me)=>exclusive(req.params.id,async()=>{const u=uploads.get(req.params.id);ensure(u&&u.owner===me&&!u.done,'Only unfinished uploads can be cancelled');await remove('upload',u.id);uploads.delete(u.id);res.json({ok:true});})));
  app.get('/api/files/:id',wrap(async(req,res,me)=>{const u=uploads.get(req.params.id);ensure(u&&u.done&&canRead(u.id,me),'File unavailable');used.set(u.id,Date.now());res.setHeader('Content-Type','application/octet-stream');res.setHeader('X-Content-Type-Options','nosniff');res.send(Buffer.concat(u.chunks.map(c=>Buffer.from(c.data,'base64'))));}));
  // The app says it has saved a file on the phone (after checking its hash). Once everyone it was meant for has, the
  // server lets it go (see sweep). Asking again, or after the file left, is fine.
  app.post('/api/files/:id/got',wrap(async(req,res,me)=>{const u=uploads.get(req.params.id);
    if(u&&u.done&&u.owner!==me&&canRead(u.id,me)&&!holders.get(u.id)?.has(me)){const now=Date.now();await persist('got',u.id+':'+me,now);hold(u.id+':'+me,now);used.set(u.id,now)}
    res.json({ok:true});}));
  app.post('/api/status',wrap(async(req,res,me)=>{
    const kind=req.body.kind;ensure(['text','image','video'].includes(kind),'Unsupported status');
    const audience=[...new Set(Array.isArray(req.body.audience)?req.body.audience:[])].filter(x=>x!==me&&users.has(x)&&!blocked(me,x));ensure(audience.length<=200,'Select up to 200 contacts');
    ensure([...statuses.values()].filter(s=>s.owner===me&&s.expires>Date.now()).length<20,'Maximum 20 active statuses');
    let body=String(req.body.body||'');if(kind==='text')ensure(body.trim().length>0&&body.length<=700,'Status must be 1–700 characters');else {body=validMedia(body,me);ensure(JSON.parse(body).mime.startsWith(kind==='image'?'image/':'video/'),'Media type does not match status');}
    // Optional caption on photo/video updates; older clients simply never send it.
    const caption=kind==='text'?'':String(req.body.caption||'').trim();ensure(caption.length<=700,'Caption must be up to 700 characters');
    const s={id:id(),owner:me,kind,body,audience,created:Date.now(),expires:Date.now()+LIFE,viewers:[],viewedAt:{}};if(caption)s.caption=caption;
    // The owner's phone keeps its own copy (apps that say so), so the media can leave once the whole audience has it.
    if(kind!=='text'){if(req.body.ownCopy===true)s.ownCopy=true;used.set(media(s),Date.now())}
    // Optional look of a text update (colour and font from the full-screen composer); older clients never send it.
    if(kind==='text'){const bg=String(req.body.bg||''),font=String(req.body.font||'');if(/^#[0-9A-Fa-f]{6}$/.test(bg))s.bg=bg;if(['sans','serif','mono','script','bold'].includes(font))s.font=font;}
    if(kind!=='text')for(const to of audience)await grant(body,me,to,s.expires);
    await persist('status',s.id,s);statuses.set(s.id,s);res.json({id:s.id,expires:s.expires});
  }));
  app.get('/api/status',wrap(async(req,res,me)=>{
    // Owners also get who viewed and when (records saved before view times existed report 0).
    const feed=[...statuses.values()].filter(s=>s.expires>Date.now()&&!blocked(s.owner,me)&&(s.owner===me||s.audience.includes(me))).sort((a,b)=>b.created-a.created).map(s=>({...s,name:users.get(s.owner)?.name||s.owner,audience:undefined,viewedAt:undefined,ownCopy:undefined,viewers:s.owner===me?s.viewers:undefined,
      views:s.owner===me?s.viewers.map(v=>({id:v,name:users.get(v)?.name||v,at:(s.viewedAt||{})[v]||0})):undefined}));
    res.json({statuses:feed});
  }));
  app.post('/api/status/:id/view',wrap(async(req,res,me)=>{const s=statuses.get(req.params.id);ensure(s&&s.expires>Date.now()&&!blocked(s.owner,me)&&(s.owner===me||s.audience.includes(me)),'Status unavailable');if(me!==s.owner&&!s.viewers.includes(me)){const next={...s,viewers:[...s.viewers,me],viewedAt:{...(s.viewedAt||{}),[me]:Date.now()}};await persist('status',s.id,next);statuses.set(s.id,next)}res.json({ok:true});}));
  app.delete('/api/status/:id',wrap(async(req,res,me)=>{const s=statuses.get(req.params.id);ensure(s&&s.owner===me,'Status unavailable');await remove('status',s.id);statuses.delete(s.id);res.json({ok:true});}));
  return {init,blocked,grant,tidy:()=>cleanup(true)};
};
