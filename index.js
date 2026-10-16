// Rabta Server v1.0
// HTTP: register, login, user lookup
// WebSocket /ws : real-time message relay + offline store
const http = require('http');
const express = require('express');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const app = express();
app.use(express.json({ limit: '2mb' }));
const server = http.createServer(app);
const MAX_BODY = 12 * 1024 * 1024;
const wss = new WebSocketServer({ server, path: '/ws', maxPayload: MAX_BODY + 4096 });

const TOKEN_SECRET = process.env.TOKEN_SECRET || crypto.randomBytes(32).toString('hex');
const UN_RE = /^[a-zA-Z0-9_]{3,20}$/;

// ---------- storage ----------
let pool = null;
const memUsers = new Map();      // id -> {id,username,pass_hash,name,created_at}
const memMessages = [];          // pending offline messages
let memMsgId = 1;

if (process.env.DATABASE_URL) {
  const { Pool } = require('pg');
  pool = new Pool({
    connectionString: process.env.DATABASE_URL,
    ssl: process.env.PGSSL === 'disable' ? false : { rejectUnauthorized: false }
  });
}

async function initDb() {
  if (!pool) return;
  await pool.query(`
    CREATE TABLE IF NOT EXISTS ru_users (
      id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL,
      pass_hash TEXT NOT NULL, name TEXT, created_at BIGINT, last_seen BIGINT
    );
    CREATE TABLE IF NOT EXISTS ru_messages (
      id SERIAL PRIMARY KEY, msg_id TEXT NOT NULL, from_id TEXT, from_name TEXT,
      to_id TEXT NOT NULL, body TEXT, type TEXT DEFAULT 'text',
      sent_at BIGINT, delivered_at BIGINT
    );
    ALTER TABLE ru_messages ADD COLUMN IF NOT EXISTS group_id TEXT;
    ALTER TABLE ru_messages ADD COLUMN IF NOT EXISTS group_name TEXT;
    CREATE INDEX IF NOT EXISTS idx_rum_to ON ru_messages (to_id, delivered_at);
    CREATE TABLE IF NOT EXISTS rg_groups(id TEXT PRIMARY KEY, name TEXT, creator TEXT, members TEXT, created_at BIGINT);
  `);
  const rows = (await pool.query('SELECT id,username,pass_hash,name FROM ru_users LIMIT 10000')).rows;
  for (const r of rows) memUsers.set(r.id, r);
  for (const g of (await pool.query('SELECT * FROM rg_groups')).rows) groups.set(g.id, { ...g, members: new Set(g.members.split(',')) });
}

// ---------- crypto ----------
const safeEq = (a, b) => { try { return crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b)); } catch { return false; } };
function hashPin(pin) {
  const s = crypto.randomBytes(16).toString('hex');
  return s + ':' + crypto.scryptSync(pin, s, 32).toString('hex');
}
function checkPin(pin, stored) {
  const [s, h] = String(stored).split(':');
  if (!s || !h) return false;
  return safeEq(crypto.scryptSync(pin, s, 32).toString('hex'), h);
}
function makeToken(id) {
  const payload = id + '.' + (Date.now() + 30 * 24 * 3600 * 1000);
  return payload + '.' + crypto.createHmac('sha256', TOKEN_SECRET).update(payload).digest('hex');
}
function readToken(tok) {
  const p = String(tok || '').split('.');
  if (p.length !== 3) return null;
  const payload = p[0] + '.' + p[1];
  const sig = crypto.createHmac('sha256', TOKEN_SECRET).update(payload).digest('hex');
  if (!safeEq(sig, p[2]) || Number(p[1]) < Date.now()) return null;
  return p[0];
}
function authId(req) { return readToken(String(req.headers.authorization || '').replace(/^Bearer\s+/i, '')); }

// ---------- register ----------
const regFails = new Map();
app.post('/api/register', async (req, res) => {
  const username = String(req.body?.username || '').trim().toLowerCase();
  const pin = String(req.body?.pin || '');
  const name = String(req.body?.name || username).trim().slice(0, 40);
  if (!UN_RE.test(username)) return res.status(400).json({ error: 'Username 3-20 letters/numbers only' });
  if (!/^\d{4,8}$/.test(pin)) return res.status(400).json({ error: 'PIN must be 4-8 digits' });
  const existing = [...memUsers.values()].find(u => u.username === username);
  if (existing) return res.status(409).json({ error: 'Username already taken' });
  const id = crypto.randomBytes(6).toString('hex').toUpperCase();
  const user = { id, username, pass_hash: hashPin(pin), name, created_at: Date.now(), last_seen: Date.now() };
  memUsers.set(id, user);
  if (pool) await pool.query('INSERT INTO ru_users(id,username,pass_hash,name,created_at,last_seen) VALUES($1,$2,$3,$4,$5,$6)',
    [id, username, user.pass_hash, name, user.created_at, user.last_seen]).catch(() => {});
  res.json({ id, username, name, token: makeToken(id) });
});

// ---------- login ----------
const loginFails = new Map();
app.post('/api/login', (req, res) => {
  const username = String(req.body?.username || '').trim().toLowerCase();
  const pin = String(req.body?.pin || '');
  const f = loginFails.get(username);
  if (f && f.count >= 10 && f.until > Date.now()) return res.status(429).json({ error: 'Too many attempts, wait 15 minutes' });
  const user = [...memUsers.values()].find(u => u.username === username);
  if (!user || !checkPin(pin, user.pass_hash)) {
    const cur = (f && f.until > Date.now()) ? f : { count: 0, until: Date.now() + 15 * 60 * 1000 };
    cur.count++; loginFails.set(username, cur);
    return res.status(401).json({ error: 'Wrong username or PIN' });
  }
  loginFails.delete(username);
  res.json({ id: user.id, username: user.username, name: user.name, token: makeToken(user.id) });
});

// ---------- user lookup ----------
app.get('/api/user/:username', (req, res) => {
  const user = [...memUsers.values()].find(u => u.username === req.params.username.toLowerCase());
  if (!user) return res.status(404).json({ error: 'User not found' });
  const onlineNow = online.has(user.id);
  res.json({ id: user.id, username: user.username, name: user.name, online: onlineNow, lastSeen: user.last_seen });
});
app.get('/api/me', (req, res) => {
  const id = authId(req); if (!id) return res.status(401).json({ error: 'login required' });
  const user = memUsers.get(id); if (!user) return res.status(404).json({ error: 'not found' });
  res.json({ id: user.id, username: user.username, name: user.name });
});

// ---------- WebSocket ----------
const online = new Map(); // userId -> ws
const videoCalls = new Map();
const voiceCalls = new Map();
const groups = new Map();  // groupId -> {id,name,creator,members:Set}

function sendTo(id, payload) { const socket=online.get(id); if(socket?.readyState===1) socket.send(JSON.stringify(payload)); }
function callState(call,state) { sendTo(call.from,{type:'call_state',from:call.to,callId:call.id,state}); }
function endCall(call) {
  clearTimeout(call.timer);
  const type=call.video?'video_end':'call_end';
  sendTo(call.from,{type,from:call.to,callId:call.id});
  sendTo(call.to,{type,from:call.from,callId:call.id});
  (call.video?videoCalls:voiceCalls).delete(call.id);
}
function deliverCall(call) {
  if(call.answered || call.expires<=Date.now() || online.get(call.to)?.readyState!==1) return;
  sendTo(call.to,{type:call.video?'video_offer':'incoming_call',from:call.from,fromName:memUsers.get(call.from)?.name,callId:call.id,sdp:call.sdp});
}
function beginCall(from,to,id,video,sdp) {
  if(!/^[0-9A-F]{12}$/.test(id) || !memUsers.has(to) || from===to) return;
  const all=[...voiceCalls.values(),...videoCalls.values()];
  if(all.some(c=>[c.from,c.to].includes(from)||[c.from,c.to].includes(to))) { sendTo(from,{type:'call_state',from:to,callId:id,state:'busy'});return; }
  const call={id,from,to,video,sdp,answered:false,expires:Date.now()+45000,ice:[]};
  call.timer=setTimeout(()=>endCall(call),45000);call.timer.unref?.();
  (video?videoCalls:voiceCalls).set(id,call); callState(call,'calling'); deliverCall(call);
}
function participantCall(msg,userId) {
  const call=voiceCalls.get(msg.callId)||videoCalls.get(msg.callId);
  return call && ((call.from===userId&&call.to===msg.to)||(call.to===userId&&call.from===msg.to)) ? call : null;
}

async function deliverPending(userId, ws) {
  let msgs;
  if (pool) {
    const r = await pool.query('SELECT * FROM ru_messages WHERE to_id=$1 AND delivered_at IS NULL ORDER BY sent_at ASC LIMIT 200', [userId]);
    msgs = r.rows;
    if (msgs.length) await pool.query('UPDATE ru_messages SET delivered_at=$1 WHERE id=ANY($2::int[])', [Date.now(), msgs.map(m => m.id)]);
  } else {
    msgs = memMessages.filter(m => m.to_id === userId && !m.delivered_at);
    msgs.forEach(m => { m.delivered_at = Date.now(); });
  }
  for (const m of msgs) {
    ws.send(JSON.stringify({ type: m.group_id ? 'group_msg' : 'msg', groupId: m.group_id || '', groupName: m.group_name || '', id: m.msg_id, from: m.from_id, fromName: m.from_name, body: m.body, msgType: m.type || 'text', t: Number(m.sent_at), offline: true }));
  }
}

async function storeMessage(fromId, fromName, toId, msgId, body, type, sentAt, groupId = null, groupName = null) {
  if (pool) {
    await pool.query('INSERT INTO ru_messages(msg_id,from_id,from_name,to_id,body,type,sent_at,group_id,group_name) SELECT $1,$2,$3,$4,$5,$6,$7,$8,$9 WHERE NOT EXISTS (SELECT 1 FROM ru_messages WHERE msg_id=$1 AND from_id=$2 AND to_id=$4)',
      [msgId, fromId, fromName, toId, body, type, sentAt, groupId, groupName]);
  } else {
    if (memMessages.some(m => m.msg_id === msgId && m.from_id === fromId && m.to_id === toId)) return;
    const pending = memMessages.filter(m => !m.delivered_at);
    if (pending.length >= 1000 || pending.reduce((n,m) => n + Buffer.byteLength(m.body || ''), 0) + Buffer.byteLength(body) > 64*1024*1024) throw new Error('Offline storage full');
    memMessages.push({ group_id: groupId, group_name: groupName, id: memMsgId++, msg_id: msgId, from_id: fromId, from_name: fromName, to_id: toId, body, type, sent_at: sentAt, delivered_at: null });
    while(memMessages.length > 2000 && memMessages[0].delivered_at) memMessages.shift();
  }
}

function updateLastSeen(userId) {
  const u = memUsers.get(userId); if (u) u.last_seen = Date.now();
  if (pool) pool.query('UPDATE ru_users SET last_seen=$1 WHERE id=$2', [Date.now(), userId]).catch(() => {});
}

wss.on('connection', (ws, req) => {
  const token = req.headers['authorization']?.replace(/^Bearer\s+/i, '') || new URL(req.url, 'http://x').searchParams.get('token');
  const userId = readToken(token);
  if (!userId || !memUsers.has(userId)) { ws.close(1008, 'auth'); return; }
  const user = memUsers.get(userId);
  if (online.has(userId)) try { online.get(userId).close(); } catch (_) {}
  online.set(userId, ws);
  updateLastSeen(userId);

  ws.send(JSON.stringify({ type: 'ready', id: userId, name: user.name }));
  deliverPending(userId, ws).catch(() => {});
  for(const call of [...voiceCalls.values(),...videoCalls.values()]) if(call.to===userId) deliverCall(call);

  ws.on('message', async (raw) => {
    let msg; try { msg = JSON.parse(raw.toString()); } catch { return; }
    if (msg.type === 'msg' || msg.type === 'group_msg') {
      if (!/^[0-9A-F]{12}$/.test(String(msg.id || '')) || typeof msg.body !== 'string' || !msg.body || Buffer.byteLength(msg.body) > MAX_BODY || !['text','image','voice','video','file','sticker'].includes(msg.msgType || 'text')) {
        ws.send(JSON.stringify({type:'ack',id:msg.id,status:'error'})); return;
      }
    }
    if (msg.type === 'msg') {
      const { id: msgId, to, body, msgType = 'text', t } = msg;
      if (!msgId || !to || !body) return;
      if (!memUsers.has(to)) { ws.send(JSON.stringify({type:'ack',id:msgId,status:'error'})); return; }
      const sentAt = Number(t) || Date.now();
      const recipient = online.get(to);
      if (recipient && recipient.readyState === 1) {
        recipient.send(JSON.stringify({ type: 'msg', id: msgId, from: userId, fromName: user.name, body, msgType, t: sentAt }));
        ws.send(JSON.stringify({ type: 'ack', id: msgId, status: 'delivered' }));
      } else {
        try { await storeMessage(userId, user.name, to, msgId, body, msgType, sentAt); } catch (_) { ws.send(JSON.stringify({type:'ack',id:msgId,status:'error'})); return; }
        ws.send(JSON.stringify({ type: 'ack', id: msgId, status: 'stored' }));
      }
    } else if (msg.type === 'call_init' || msg.type === 'video_offer') {
      const video=msg.type==='video_offer';
      if(video && (typeof msg.sdp!=='string'||msg.sdp.length>65536)) return;
      beginCall(userId,msg.to,String(msg.callId||''),video,msg.sdp);
    } else if (msg.type === 'call_ringing') {
      const call=participantCall(msg,userId);
      if(call && call.to===userId && !call.answered) callState(call,'ringing');
    } else if (msg.type === 'call_accept' || msg.type === 'video_answer') {
      const call=participantCall(msg,userId);
      if(!call || call.to!==userId || call.answered || call.video!==(msg.type==='video_answer')) return;
      if(call.video && (typeof msg.sdp!=='string'||msg.sdp.length>65536)) return;
      call.answered=true; clearTimeout(call.timer); call.expires=Date.now()+2*60*60*1000; callState(call,'accepted');
      if(call.video) {
        sendTo(call.from,{type:'video_answer',from:userId,callId:call.id,sdp:msg.sdp});
        for(const candidate of call.ice) sendTo(candidate.to,candidate.payload);
        call.ice=[];
      }
    } else if (msg.type === 'ice_candidate') {
      const call=participantCall(msg,userId);
      if(!call?.video || typeof msg.sdp!=='string'||msg.sdp.length>4096) return;
      const payload={type:'ice_candidate',from:userId,callId:call.id,sdp:msg.sdp,sdpMid:msg.sdpMid,sdpMLineIndex:msg.sdpMLineIndex};
      if(call.answered) sendTo(msg.to,payload); else if(call.ice.length<64) call.ice.push({to:msg.to,payload});
    } else if (msg.type === 'call_audio') {
      const call=participantCall(msg,userId);
      if(call && !call.video && call.answered && typeof msg.data==='string' && msg.data.length<=14000) sendTo(msg.to,{type:'call_audio',from:userId,callId:call.id,data:msg.data});
    } else if (msg.type === 'call_end' || msg.type === 'video_end') {
      const call=participantCall(msg,userId); if(call) endCall(call);
    } else if (msg.type === 'typing') {
      const tgt = online.get(msg.to); if (tgt && tgt.readyState === 1) tgt.send(JSON.stringify({ type: 'typing', from: userId }));
    } else if (msg.type === 'read') {
      const tgt = online.get(msg.to); if (tgt && tgt.readyState === 1) tgt.send(JSON.stringify({ type: 'read', msgId: msg.msgId, by: userId }));
    } else if (msg.type === 'group_msg') {
      const g = groups.get(String(msg.to||'').toUpperCase());
      if (!g || !g.members.has(userId)) { ws.send(JSON.stringify({ type:'ack', id:msg.id, status:'error' })); return; }
      const now = Date.now();
      const out = { type:'group_msg', id:msg.id, groupId:g.id, groupName:g.name, from:userId, fromName:user.name, body:msg.body, msgType:msg.msgType||'text', t:now };
      for (const mid of g.members) {
        if (mid===userId) continue;
        const r = online.get(mid);
        if (r && r.readyState===1) r.send(JSON.stringify(out));
        else { try { await storeMessage(userId, user.name, mid, msg.id, msg.body, msg.msgType||'text', now, g.id, g.name); } catch (_) { ws.send(JSON.stringify({type:'ack',id:msg.id,status:'error'})); return; } }
      }
      ws.send(JSON.stringify({ type:'ack', id:msg.id, status:'sent' }));
    } else if (msg.type === 'ping') {
      ws.send(JSON.stringify({ type: 'pong' }));
    }
  });

  ws.on('close', () => {
    if (online.get(userId) === ws) { online.delete(userId); updateLastSeen(userId); for(const call of [...voiceCalls.values(),...videoCalls.values()]) if(call.from===userId||call.to===userId) endCall(call); }
  });
  ws.on('error', () => { try { ws.close(); } catch (_) {} });
});

app.post('/api/groups', async (req, res) => {
  const creator = authId(req); if (!creator) return res.status(401).json({ error: 'login required' });
  const name = String(req.body?.name||'').trim().slice(0,40); if (!name) return res.status(400).json({ error: 'name required' });
  const raw = Array.isArray(req.body?.members) ? req.body.members : [];
  const members = new Set([creator, ...raw.filter(m => memUsers.has(m)).slice(0,49)]);
  const id = require('crypto').randomBytes(6).toString('hex').toUpperCase();
  groups.set(id, { id, name, creator, members, created_at: Date.now() });
  if (pool) await pool.query('INSERT INTO rg_groups(id,name,creator,members,created_at) VALUES($1,$2,$3,$4,$5) ON CONFLICT(id) DO NOTHING',
    [id, name, creator, [...members].join(','), Date.now()]).catch(()=>{});
  const payload = JSON.stringify({ type:'group_created', group:{ id, name, members:[...members] } });
  for (const mid of members) { const w = online.get(mid); if (w && w.readyState===1) w.send(payload); }
  res.json({ id, name, members:[...members] });
});

app.get('/health', (req, res) => res.json({ ok: true, db: !!pool, online: online.size, users: memUsers.size }));

const PORT = process.env.PORT || 3000;
initDb().catch(e => console.error('DB init:', e.message))
  .finally(() => server.listen(PORT, () => console.log('Rabta server on', PORT)));

