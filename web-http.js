'use strict';
const { CompanionLinks } = require('./web-companion');
const crypto = require('node:crypto');
const path = require('node:path');
const express = require('express');
const qr = require('qrcode');

// Browser traffic never authenticates against /ws or replaces the phone socket.
module.exports = function mountWeb({ app, origin, authPhone, deviceAllowed, phoneSocket, links = new CompanionLinks() }) {
  const parsed = new URL(origin);
  if (parsed.origin !== origin || (parsed.protocol !== 'https:' && parsed.hostname !== 'localhost')) throw new Error('Invalid RABTA_WEB_ORIGIN');
  const secure = parsed.protocol === 'https:';
  const cookieName = secure ? '__Secure-rabta_web' : 'rabta_web_dev';
  const pending = new Map(), limits = new Map();
  const getSecret = req => String(req.headers.cookie || '').split(';').map(x => x.trim()).find(x => x.startsWith(cookieName + '='))?.slice(cookieName.length + 1) || '';
  const cookie = (res, value, maxAge = 28800) => res.setHeader('Set-Cookie', `${cookieName}=${value}; HttpOnly; SameSite=Strict; Path=/web; Max-Age=${maxAge}${secure ? '; Secure' : ''}`);
  function limited(key, maximum, ms) {
    const now = Date.now();
    for (const [k,v] of limits) if (v.until <= now) limits.delete(k);
    if (!limits.has(key)) { if (limits.size >= 2000) return true; limits.set(key,{count:0,until:now+ms}); }
    return ++limits.get(key).count > maximum;
  }
  app.get('/', (req,res,next) => { if(req.get('host')===parsed.host)return res.redirect('/web/');next(); });
  app.use('/web', (req,res,next) => {
    res.setHeader('Cache-Control','no-store');res.setHeader('Referrer-Policy','no-referrer');
    res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('X-Frame-Options','DENY');
    res.setHeader('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    next();
  });
  function browser(req,res,next) {
    // Origin + a non-simple header protect every browser API, including read requests.
    if (req.get('Origin') !== origin || req.get('X-Rabta-Web') !== '1') return res.status(403).json({error:'Open Rabta Web on its configured address.'});
    next();
  }
  function phone(req,res,next) {
    const a=authPhone(req);
    if (!a?.id || !a.device || !deviceAllowed(a.id,a.device)) return res.status(401).json({error:'Sign in on a trusted phone first.'});
    req.webPhone=a;next();
  }
  function session(req,res,next) {
    const secret=getSecret(req), link=links.inspect(secret,deviceAllowed);
    if (!link?.owner) return res.status(401).json({error:'Link this browser again.'});
    if (limited('session:'+link.id,120,60000)) return res.status(429).json({error:'Please wait before trying again.'});
    req.webLink=link;next();
  }
  function online(link) {
    const socket=phoneSocket(link.owner,link.device);
    return socket?.readyState===1 && socket.rabtaWebReady ? socket : null;
  }
  app.post('/web/api/pair',browser,async(req,res) => {
    if (limited('pair:'+req.ip,30,600000)) return res.status(429).json({error:'Too many linking attempts. Try later.'});
    try {
      links.logout(getSecret(req));
      const pair=links.create(req.body?.name || 'Web browser');cookie(res,pair.secret);
      const payload=JSON.stringify({kind:'rabta-web',v:1,origin,code:pair.code});
      res.json({code:pair.code,expires:pair.expires,qr:await qr.toDataURL(payload,{width:280,margin:2})});
    } catch (_) { res.status(503).json({error:'Linking is temporarily unavailable.'}); }
  });
  app.post('/web/api/state',browser,(req,res) => {
    const link=links.inspect(getSecret(req),deviceAllowed);
    if (!link) return res.status(401).json({error:'Link expired. Scan a new code.'});
    res.json({linked:!!link.owner,phoneOnline:!!link.owner && !!online(link),expires:link.expires});
  });
  app.post('/web/api/logout',browser,(req,res) => {links.logout(getSecret(req));cookie(res,'',0);res.json({ok:true});});
  app.post('/api/web-links/preview',phone,(req,res) => {
    if(limited('approval:'+req.webPhone.id,30,60000))return res.status(429).json({error:'Please wait before scanning again.'});
    const link=links.preview(req.body?.code);res.status(link?200:404).json(link ? {...link,origin} : {error:'This code expired or was already used.'});
  });
  app.post('/api/web-links/approve',phone,(req,res) => {
    if(limited('approval:'+req.webPhone.id,30,60000))return res.status(429).json({error:'Please wait before scanning again.'});
    const a=req.webPhone;
    if(links.list(a.id,a.device).length>=3)return res.status(409).json({error:'Unlink a browser first (maximum three).'});
    const link=links.approve(req.body?.code,a.id,a.device);
    res.status(link?200:404).json(link || {error:'This code expired or was already used.'});
  });
  app.get('/api/web-links',phone,(req,res) => res.json({links:links.list(req.webPhone.id,req.webPhone.device)}));
  app.delete('/api/web-links/:id',phone,(req,res) => {
    links.revoke(req.webPhone.id,req.webPhone.device,req.params.id);res.json({ok:true});
  });
  app.post('/web/api/command',browser,session,(req,res) => {
    const link=req.webLink, socket=online(link), body=req.body || {};
    if(!socket)return res.status(409).json({error:'Phone disconnected. Open Rabta on your phone.'});
    if(!['chats','messages','send'].includes(body.op))return res.status(400).json({error:'Unsupported action.'});
    if(body.op!=='chats' && !/^[A-F0-9]{12}$/.test(String(body.peer)))return res.status(400).json({error:'Invalid chat.'});
    if(body.op==='send' && (!/^[A-F0-9]{12}$/.test(String(body.messageId)) || typeof body.text!=='string' || !body.text.trim() || Buffer.byteLength(body.text)>12000))return res.status(400).json({error:'Invalid message.'});
    if(pending.size>=200 || [...pending.values()].some(p=>p.link.id===link.id))return res.status(429).json({error:'A phone request is still in progress.'});
    const requestId=crypto.randomBytes(16).toString('hex');
    const finish=(status,value)=>{const p=pending.get(requestId);if(!p)return;clearTimeout(p.timer);pending.delete(requestId);if(!res.destroyed)res.status(status).json(value);};
    const timer=setTimeout(()=>finish(504,{error:body.op==='send'?'Phone did not confirm. Retry this message to check its result.':'Phone is not responding. Open Rabta on your phone.'}),8000);
    timer.unref?.();pending.set(requestId,{link,secret:getSecret(req),finish,timer});
    res.on('close',()=>{clearTimeout(timer);pending.delete(requestId);});
    try { socket.send(JSON.stringify({type:'web_command',linkId:link.id,requestId,op:body.op,peer:body.peer,messageId:body.messageId,text:body.text})); }
    catch (_) { finish(409,{error:'Phone disconnected.'}); }
  });
  app.use('/web',express.static(path.join(__dirname,'web'),{index:'index.html',etag:false}));
  return {
    reply(user,device,msg) {
      const p=pending.get(msg.requestId);if(!p || p.link.owner!==user || p.link.device!==device)return;
      const link=links.inspect(p.secret,deviceAllowed);
      if(!link || link.id!==p.link.id) {p.finish(401,{error:'Browser access was revoked.'});return;}
      if(Buffer.byteLength(JSON.stringify(msg.data || {}))>256000){p.finish(502,{error:'Phone response too large.'});return;}
      p.finish(msg.ok===true?200:409,msg.ok===true?msg.data:{error:'The phone could not complete this action. Check chat privacy or reconnect.'});
    }
  };
};
