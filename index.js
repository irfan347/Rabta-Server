// Rabta Server v1.0
// HTTP: register, login, user lookup
// WebSocket /ws : real-time message relay + offline store
const http = require('http');
const express = require('express');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

let webCompanion = null;
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
const memMessages = [];          // pending offline messages (delivered ones keep only their ids, see deliverPending)
let memMsgId = 1;
const memReceipts = [];          // recipient receipts waiting for an offline sender

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
    ALTER TABLE ru_users ADD COLUMN IF NOT EXISTS phone_number TEXT;
    ALTER TABLE ru_users ADD COLUMN IF NOT EXISTS phone_discoverable BOOLEAN NOT NULL DEFAULT FALSE;
    CREATE TABLE IF NOT EXISTS ru_messages (
      id SERIAL PRIMARY KEY, msg_id TEXT NOT NULL, from_id TEXT, from_name TEXT,
      to_id TEXT NOT NULL, body TEXT, type TEXT DEFAULT 'text',
      sent_at BIGINT, delivered_at BIGINT
    );
    ALTER TABLE ru_messages ADD COLUMN IF NOT EXISTS group_id TEXT;
    ALTER TABLE ru_messages ADD COLUMN IF NOT EXISTS group_name TEXT;
    CREATE INDEX IF NOT EXISTS idx_rum_to ON ru_messages (to_id, delivered_at);
    CREATE INDEX IF NOT EXISTS idx_rum_pending_to_id ON ru_messages (to_id,id) WHERE delivered_at IS NULL;
    CREATE TABLE IF NOT EXISTS rg_groups(id TEXT PRIMARY KEY, name TEXT, creator TEXT, members TEXT, created_at BIGINT);
    CREATE TABLE IF NOT EXISTS ru_receipts(msg_id TEXT NOT NULL, by_id TEXT NOT NULL, to_id TEXT NOT NULL, at BIGINT, PRIMARY KEY(msg_id, by_id, to_id));
    CREATE INDEX IF NOT EXISTS idx_receipts_to_at ON ru_receipts (to_id,at);
  `);
  const rows = (await pool.query('SELECT id,username,pass_hash,name,phone_number,phone_discoverable FROM ru_users')).rows;
  for (const r of rows) memUsers.set(r.id, r);
  await account.init();
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
// Tokens name the trusted device they were issued to ("id.expiry.device.sig"); older "id.expiry.sig" tokens
// stay valid only while the account has no trusted device.
function makeToken(id, device = null) {
  const payload = id + '.' + (Date.now() + 30 * 24 * 3600 * 1000) + (device ? '.' + device : '');
  return payload + '.' + crypto.createHmac('sha256', TOKEN_SECRET).update(payload).digest('hex');
}
function readTokenFull(tok) {
  const p = String(tok || '').split('.');
  if (p.length !== 3 && p.length !== 4) return null;
  const payload = p.slice(0, -1).join('.');
  const sig = crypto.createHmac('sha256', TOKEN_SECRET).update(payload).digest('hex');
  if (!safeEq(sig, p[p.length - 1]) || Number(p[1]) < Date.now()) return null;
  const device = p.length === 4 ? p[2] : null;
  return account.tokenAllowed(p[0], device) ? { id: p[0], device } : null;
}
const readToken = tok => readTokenFull(tok)?.id || null;
const bearer = req => String(req.headers.authorization || '').replace(/^Bearer\s+/i, '');
function authId(req) { return readToken(bearer(req)); }

const account = require('./account')({ app, users: memUsers, getPool: () => pool, authToken: req => readTokenFull(bearer(req)), makeToken, checkPin, sendTo,
  closeDevice: (userId, device) => { const w = online.get(userId); if (w && w.rabtaDevice === device) try { w.close(1008, 'auth'); } catch (_) {} } });

// ---------- register ----------
const regFails = new Map();
app.post('/api/register', async (req, res) => {
  const username = String(req.body?.username || '').trim().toLowerCase();
  const pin = String(req.body?.pin || '');
  const name = account.cleanName(req.body?.name || username) || username;
  // Optional for older clients; supplied numbers are private, unverified metadata, never identity proof.
  const rawNumber = req.body?.phoneNumber;
  if (rawNumber !== undefined && (typeof rawNumber !== 'string' || !/^\+[1-9][0-9]{7,14}$/.test(rawNumber)))
    return res.status(400).json({ error: 'Enter a phone number with country code' });
  const phoneNumber = rawNumber ?? null;
  if (!UN_RE.test(username)) return res.status(400).json({ error: 'Username 3-20 letters/numbers only' });
  if (!/^\d{4,8}$/.test(pin)) return res.status(400).json({ error: 'PIN must be 4-8 digits' });
  // One account per phone: this phone already has one, so it signs in to that instead.
  const owner = account.phoneAccount(req.body?.phone);
  if (owner) return res.status(409).json({ error: `This phone already has a Rabta account (@${owner.username}). Sign in to it instead.`, username: owner.username });
  const status = account.usernameStatus(username);
  if (!status.available) return res.status(409).json({ error: status.error });
  const id = crypto.randomBytes(6).toString('hex').toUpperCase();
  const user = { id, username, phone_number:phoneNumber, phone_discoverable:false, pass_hash: hashPin(pin), name, created_at: Date.now(), last_seen: Date.now() };
  memUsers.set(id, user); // reserves the username while the insert is in flight
  // ...and the phone, in memory at once; saving it never fails the sign-up.
  const phoneSaved = account.linkPhone(req.body?.phone, id).catch(e => console.error('Phone link failed:', e.message));
  if (pool) {
    try {
      await pool.query('INSERT INTO ru_users(id,username,pass_hash,name,created_at,last_seen,phone_number) VALUES($1,$2,$3,$4,$5,$6,$7)',
        [id, username, user.pass_hash, name, user.created_at, user.last_seen, phoneNumber]);
    } catch (e) {
      memUsers.delete(id); account.unlinkPhone(req.body?.phone, id);
      if (e.code === '23505') return res.status(409).json({ error: 'Username already used' });
      console.error('Account storage failed:', e.message);
      return res.status(503).json({ error: 'Account could not be saved, please try again' });
    }
  }
  await phoneSaved;
  try { res.json(await account.registered(user, req.body?.device)); }
  catch (e) { console.error('Device registration failed:', e.message); res.json({ id, username, name, token: makeToken(id) }); }
});

// ---------- login ----------
const loginFails = new Map();
app.post('/api/login', async (req, res) => {
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
  try {
    const [status, body, failed] = await account.signIn(user, req.body);
    if (status === 200) await account.linkPhone(req.body?.phone, user.id).catch(e => console.error('Phone link failed:', e.message));
    if (failed) { const cur = (f && f.until > Date.now()) ? f : { count: 0, until: Date.now() + 15 * 60 * 1000 }; cur.count++; loginFails.set(username, cur); }
    else if (status === 200) loginFails.delete(username);
    res.status(status).json(body);
  } catch (e) { console.error('Sign-in failed:', e.message); res.status(503).json({ error: 'Sign-in could not be completed, please try again' }); }
});

// ---------- user lookup ----------
app.get('/api/user/:username', (req, res) => {
  const user = [...memUsers.values()].find(u => u.username === req.params.username.toLowerCase());
  if (!user) return res.status(404).json({ error: 'User not found' });
  const presence = presenceOf(user.id,authId(req));
  res.json({ id: user.id, username: user.username, name: user.name, online: presence.online, lastSeen: presence.lastSeen });
});
// Contact info by account ID (signed-in callers only): the same public fields as a username lookup.
app.get('/api/user/id/:id', (req, res) => {
  const me = authId(req); if (!me) return res.status(401).json({ error: 'login required' });
  const user = memUsers.get(String(req.params.id || '').toUpperCase());
  if (!user || features.blocked(user.id, me)) return res.status(404).json({ error: 'User not found' });
  res.json({ id: user.id, username: user.username, name: user.name, online: presenceOf(user.id,me).online, lastSeen: presenceOf(user.id,me).lastSeen });
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
// "Online" is Rabta open on the phone's screen, as in WhatsApp, not a phone kept connected in the background. Phones
// that report it say so when they connect (X-Rabta-Presence) and then send {type:'presence',active}; older ones count
// as online while connected, as before. A phone not heard from lately (its data went off) is not online any more, well
// before its socket is closed: while Rabta shows, the app speaks every 10 s; older phones ping every 25 s.
const SHOWN_MS = 25000;
function isOnline(id) {
  const w = online.get(id); if (!w || w.rabtaActive === false) return false;
  return Date.now() - w.rabtaHeard < (w.rabtaActive ? SHOWN_MS : QUIET_MS);
}
function presenceOf(id,viewer) { return { type: 'presence', id, online: features.visible(id,viewer,'hideOnline') && isOnline(id), lastSeen: features.visible(id,viewer,'hideLastSeen') ? (memUsers.get(id)?.last_seen || 0) : 0 }; }
// Tells the phones that have this contact's chat open (see 'watch').
function presenceChanged(id) {
  const w = online.get(id); if (w) w.rabtaTold = isOnline(id);
  for (const x of online.values()) if (x.rabtaWatch === id && x.readyState === 1 && !features.blocked(id, x.rabtaUser)) try { x.send(JSON.stringify(presenceOf(id,x.rabtaUser))); } catch (_) {}
}
// After anything that may change it ([was]: what watchers were told): last seen is when it stopped being online ([at]).
function presenceUpdate(id, was, at = Date.now()) {
  const now = isOnline(id); if (now === !!was) return;
  updateLastSeen(id, now ? Date.now() : at); presenceChanged(id);
}
// Resolves true once the frame is written to the socket, false if the socket is gone or the write failed.
// This is a transport write, not a recipient receipt; clients ignore duplicate message IDs on redelivery.
function sendFrame(socket, payload) {
  return new Promise(resolve => {
    if (socket?.readyState !== 1) return resolve(false);
    try { socket.send(JSON.stringify(payload), err => resolve(!err)); } catch (_) { resolve(false); }
  });
}
function callState(call,state) { sendTo(call.from,{type:'call_state',from:call.to,callId:call.id,state}); }
function endCall(call) {
  clearTimeout(call.timer); for(const t of Object.values(call.away||{})) clearTimeout(t);
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
  if(features.blocked(from,to)){sendTo(from,{type:'call_state',from:to,callId:id,state:'busy'});return;}
  const all=[...voiceCalls.values(),...videoCalls.values()];
  // Retried initiation is idempotent; a crossed call never creates a second session or auto-answers.
  const duplicate=all.find(c=>c.id===id&&c.from===from&&c.to===to&&c.video===video);
  if(duplicate) { callState(duplicate,duplicate.answered?'accepted':'calling');deliverCall(duplicate);return; }
  const crossed=all.find(c=>c.from===to&&c.to===from&&!c.answered);
  if(crossed) {
    sendTo(from,{type:'call_state',from:to,callId:id,state:'busy',reason:'crossed_call'});
    deliverCall(crossed);return;
  }
  if(all.some(c=>[c.from,c.to].includes(from)||[c.from,c.to].includes(to))) { sendTo(from,{type:'call_state',from:to,callId:id,state:'busy',reason:'on_another_call'});return; }
  const call={id,from,to,video,sdp,answered:false,expires:Date.now()+45000,ice:[]};
  call.timer=setTimeout(()=>endCall(call),45000);call.timer.unref?.();
  (video?videoCalls:voiceCalls).set(id,call); callState(call,'calling'); deliverCall(call);
  // Wake the phone too, also when it looks connected: its connection may have died unnoticed while it slept. Once
  // Rabta connects, the ringing call reaches it as above; a phone that already rings just keeps ringing.
  push.wake(to,'call');
}
// A phone whose connection drops in an answered call gets a moment to come back before the call ends: mobile networks
// blink, and a video call's picture even goes on directly between the phones meanwhile. The other phone hears
// 'reconnecting', then 'back' once the phone says the call goes on ('call_alive'). A call still ringing ends at once.
const RETURN_MS=120000;
function away(call,userId) {
  if(!call.answered) { endCall(call); return; }
  call.away=call.away||{}; if(call.away[userId]) return;
  const t=setTimeout(()=>endCall(call),RETURN_MS); t.unref?.(); call.away[userId]=t;
  sendTo(call.from===userId?call.to:call.from,{type:'call_state',from:userId,callId:call.id,state:'reconnecting'});
}
function back(call,userId) {
  if(!call.away?.[userId]) return;
  clearTimeout(call.away[userId]); delete call.away[userId];
  sendTo(call.from===userId?call.to:call.from,{type:'call_state',from:userId,callId:call.id,state:'back'});
}
function participantCall(msg,userId) {
  const call=voiceCalls.get(msg.callId)||videoCalls.get(msg.callId);
  return call && ((call.from===userId&&call.to===msg.to)||(call.to===userId&&call.from===msg.to)) ? call : null;
}

const PENDING_BATCH = 200;
// Drains the whole backlog in batches; a row is marked delivered only after its frame was written,
// so a dropped connection leaves the rest pending for the next connection.
async function deliverPending(userId, ws) {
  let after = 0;
  for (;;) {
    const msgs = pool
      ? (await pool.query('SELECT * FROM ru_messages WHERE to_id=$1 AND delivered_at IS NULL AND id>$2 ORDER BY id ASC LIMIT ' + PENDING_BATCH, [userId, after])).rows
      : memMessages.filter(m => m.to_id === userId && !m.delivered_at && m.id > after).slice(0, PENDING_BATCH);
    if (!msgs.length) return;
    after = msgs[msgs.length - 1].id;
    const written = await Promise.all(msgs.map(m => features.blocked(m.from_id, userId) ? true : // blocked senders are dropped, as before
      sendFrame(ws, { type: m.group_id ? 'group_msg' : 'msg', groupId: m.group_id || '', groupName: m.group_name || '', id: m.msg_id, from: m.from_id, fromName: m.from_name, body: m.body, msgType: m.type || 'text', t: Number(m.sent_at), offline: true })));
    const done = msgs.filter((_, i) => written[i]);
    // The phone has these now, so the server lets go of their content (text, voice, photos). A small marker stays
    // for a while so a copy the sender sends again is recognised and not delivered twice (see tidy).
    if (done.length) {
      if (pool) await pool.query('UPDATE ru_messages SET delivered_at=$1, body=NULL WHERE id=ANY($2::int[])', [Date.now(), done.map(m => m.id)]);
      else done.forEach(m => { m.delivered_at = Date.now(); m.body = null; });
    }
    if (done.length < msgs.length || msgs.length < PENDING_BATCH) return;
  }
}

// A message written to a phone's socket has only arrived once the phone's receipt comes back: a socket that died
// unnoticed (the phone went to sleep or lost its network) swallows what is written to it, and the server would not
// wake the phone because it looks connected. Without the receipt in 20 s the message waits on the server as for a
// phone that is offline, and the phone is woken; a copy that did arrive is recognised by its id.
const RECEIPT_MS = 20000;
const awaiting = new Map();   // msgId|from|to -> timer
function expectReceipt(from, fromName, to, msgId, body, type, sentAt) {
  const key = msgId + '|' + from + '|' + to;
  clearTimeout(awaiting.get(key));
  const t = setTimeout(() => { awaiting.delete(key); storeMessage(from, fromName, to, msgId, body, type, sentAt).catch(e => console.error('Offline storage failed:', e.message)); }, RECEIPT_MS);
  t.unref?.(); awaiting.set(key, t);
}
function received(msgId, from, to) { const key = msgId + '|' + from + '|' + to; clearTimeout(awaiting.get(key)); awaiting.delete(key); }
// Rabta pings its connection every 25 s: one not heard from for a while may be dead, so what goes there is kept too.
const QUIET_MS = 35000, SILENT_MS = 70000;
const quiet = socket => !!socket && socket.rabtaPings > 0 && Date.now() - socket.rabtaHeard > QUIET_MS;

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
  // Stored because the recipient is not connected: wake their phone so Rabta comes for it.
  push.wake(toId);
}

// A receipt is sent by the recipient's app after it has stored a message; it is the only signal that
// means "delivered". Server acks ('delivered' = written to the recipient's socket, 'stored') stay unchanged
// for existing apps. Receipts for an offline sender wait in ru_receipts until it reconnects.
async function relayReceipt(byId, toId, msgId) {
  if (await sendFrame(online.get(toId), { type: 'receipt', id: msgId, by: byId })) return;
  if (pool) await pool.query('INSERT INTO ru_receipts(msg_id,by_id,to_id,at) VALUES($1,$2,$3,$4) ON CONFLICT DO NOTHING', [msgId, byId, toId, Date.now()]);
  else if (!memReceipts.some(r => r.msg_id === msgId && r.by_id === byId && r.to_id === toId)) {
    memReceipts.push({ msg_id: msgId, by_id: byId, to_id: toId, at: Date.now() });
    if (memReceipts.length > 5000) memReceipts.shift();
  }
}

async function deliverReceipts(userId, ws) {
  for (;;) {
    const rows = pool
      ? (await pool.query('SELECT msg_id,by_id FROM ru_receipts WHERE to_id=$1 ORDER BY at ASC LIMIT ' + PENDING_BATCH, [userId])).rows
      : memReceipts.filter(r => r.to_id === userId).slice(0, PENDING_BATCH);
    if (!rows.length) return;
    const written = await Promise.all(rows.map(r => sendFrame(ws, { type: 'receipt', id: r.msg_id, by: r.by_id })));
    const done = rows.filter((_, i) => written[i]);
    if (!pool) for (const r of done) memReceipts.splice(memReceipts.indexOf(r), 1);
    else if (done.length) await pool.query('DELETE FROM ru_receipts r USING unnest($2::text[],$3::text[]) AS d(m,b) WHERE r.to_id=$1 AND r.msg_id=d.m AND r.by_id=d.b',
      [userId, done.map(r => r.msg_id), done.map(r => r.by_id)]);
    if (done.length < rows.length || rows.length < PENDING_BATCH) return;
  }
}

function updateLastSeen(userId, at = Date.now()) {
  const u = memUsers.get(userId); if (u) u.last_seen = at;
  if (pool) pool.query('UPDATE ru_users SET last_seen=$1 WHERE id=$2', [at, userId]).catch(() => {});
}

wss.on('connection', (ws, req) => {
  const token = req.headers['authorization']?.replace(/^Bearer\s+/i, '') || new URL(req.url, 'http://x').searchParams.get('token');
  const auth = readTokenFull(token), userId = auth?.id;
  if (!userId || !memUsers.has(userId)) { ws.close(1008, 'auth'); return; }
  ws.rabtaDevice = auth.device; ws.rabtaUser = userId;
  // A phone that reports when Rabta is on its screen is away until it says it is.
  if (req.headers['x-rabta-presence']) ws.rabtaActive = false;
  const user = memUsers.get(userId), was = online.get(userId)?.rabtaTold;
  if (online.has(userId)) try { online.get(userId).close(); } catch (_) {}
  online.set(userId, ws);
  ws.rabtaAlive = true; ws.rabtaHeard = Date.now(); ws.rabtaPings = 0;
  ws.on('pong', () => { ws.rabtaAlive = true; ws.rabtaHeard = Date.now(); });
  ws.on('ping', () => { ws.rabtaAlive = true; ws.rabtaHeard = Date.now(); ws.rabtaPings++; });   // the app pings every 25 s
  if (ws.rabtaActive !== false) updateLastSeen(userId);
  presenceUpdate(userId, was); ws.rabtaTold = isOnline(userId);

  ws.send(JSON.stringify({ type: 'ready', id: userId, name: user.name, protocol: 2, receipts: true }));
  for (const frame of account.pendingFor(userId)) ws.send(JSON.stringify(frame));
  deliverPending(userId, ws).then(() => deliverReceipts(userId, ws)).catch(e => console.error('Offline delivery failed:', e.message));
  for(const call of [...voiceCalls.values(),...videoCalls.values()]) if(call.to===userId) deliverCall(call);

  ws.on('message', async (raw) => {
    ws.rabtaAlive = true; ws.rabtaHeard = Date.now();
    let msg; try { msg = JSON.parse(raw.toString()); } catch { return; }
    if (msg.type === 'web_ready') { ws.rabtaWebReady = true; return; }
    if (msg.type === 'web_reply') { webCompanion?.reply(userId,ws.rabtaDevice,msg); return; }
    if (msg.type === 'msg' || msg.type === 'group_msg') {
      if (!/^[0-9A-F]{12}$/.test(String(msg.id || '')) || typeof msg.body !== 'string' || !msg.body || Buffer.byteLength(msg.body) > MAX_BODY || !['text','image','voice','video','file','sticker','rich','reaction'].includes(msg.msgType || 'text')) {
        ws.send(JSON.stringify({type:'ack',id:msg.id,status:'error'})); return;
      }
    }
    if ((msg.type==='msg'||msg.type==='group_msg')&&['rich','reaction'].includes(msg.msgType)) {
      try { const b=JSON.parse(msg.body);if(msg.msgType==='rich'){if(typeof b.text!=='string'||!b.text.trim()||Buffer.byteLength(b.text)>12000||typeof b.reply!=='string'||b.reply.length>300)throw new Error();}
        else if(!/^[0-9A-F]{12}$/.test(b.target)||!['👍','❤️','😂','😮','😢','🙏'].includes(b.emoji))throw new Error();
      } catch {ws.send(JSON.stringify({type:'ack',id:msg.id,status:'error'}));return;}
    }
    if (msg.type === 'msg') {
      const { id: msgId, to, body, msgType = 'text', t } = msg;
      if (!msgId || !to || !body) return;
      if (!memUsers.has(to)) { ws.send(JSON.stringify({type:'ack',id:msgId,status:'error'})); return; }
      if(features.blocked(userId,to)){ws.send(JSON.stringify({type:"ack",id:msgId,status:"error"}));return;}
      try { await features.grant(body,userId,to); } catch(_) { ws.send(JSON.stringify({type:"ack",id:msgId,status:"error"}));return; }
      const sentAt = Number(t) || Date.now();
      if (await sendFrame(online.get(to), { type: 'msg', id: msgId, from: userId, fromName: user.name, body, msgType, t: sentAt })) {
        ws.send(JSON.stringify({ type: 'ack', id: msgId, status: 'delivered' }));
        expectReceipt(userId, user.name, to, msgId, body, msgType, sentAt);
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
    } else if (msg.type === 'call_alive') {
      // Back online in a call: it goes on, or it ended meanwhile and this phone hears so now.
      const call=participantCall(msg,userId);
      if(call) back(call,userId);
      else for(const type of ['call_end','video_end']) ws.send(JSON.stringify({type,from:String(msg.to||''),callId:String(msg.callId||'')}));
    } else if (msg.type === 'presence') {
      // Rabta came on the screen or left it: online while it shows, last seen when it went.
      ws.rabtaActive = msg.active === true;
      if (online.get(userId) === ws) presenceUpdate(userId, ws.rabtaTold);
    } else if (msg.type === 'watch') {
      // The chat on screen: its contact's online and last seen now, and again whenever they change.
      const id = String(msg.id || '').toUpperCase();
      ws.rabtaWatch = id !== userId && memUsers.has(id) && !features.blocked(id, userId) ? id : '';
      if (ws.rabtaWatch) ws.send(JSON.stringify(presenceOf(id,userId)));
    } else if (msg.type === 'typing') {
      // "recording" while a voice message is recorded; older phones show it as typing.
      const tgt = online.get(msg.to);
      if (tgt && tgt.readyState === 1 && features.visible(userId, String(msg.to),'hideOnline')) tgt.send(JSON.stringify({ type: 'typing', from: userId, ...(msg.kind === 'recording' ? { kind: 'recording' } : {}) }));
    } else if (msg.type === 'read') {
      const tgt = online.get(msg.to); if (tgt && tgt.readyState === 1) tgt.send(JSON.stringify({ type: 'read', msgId: msg.msgId, by: userId }));
    } else if (msg.type === 'group_msg') {
      const g = groups.get(String(msg.to||'').toUpperCase());
      if (!g || !g.members.has(userId)) { ws.send(JSON.stringify({ type:'ack', id:msg.id, status:'error' })); return; }
      const now = Date.now();
      const out = { type:'group_msg', id:msg.id, groupId:g.id, groupName:g.name, from:userId, fromName:user.name, body:msg.body, msgType:msg.msgType||'text', t:now };
      for (const mid of g.members) {
        if (mid===userId || features.blocked(userId,mid)) continue;
        try { await features.grant(msg.body,userId,mid); } catch(_) { ws.send(JSON.stringify({type:"ack",id:msg.id,status:"error"}));return; }
        if (!(await sendFrame(online.get(mid), out)) || quiet(online.get(mid))) { try { await storeMessage(userId, user.name, mid, msg.id, msg.body, msg.msgType||'text', now, g.id, g.name); } catch (_) { ws.send(JSON.stringify({type:'ack',id:msg.id,status:'error'})); return; } }
      }
      ws.send(JSON.stringify({ type:'ack', id:msg.id, status:'sent' }));
    } else if (msg.type === 'receipt') {
      const id = String(msg.id || ''), to = String(msg.to || '');
      received(id, to, userId);
      if (/^[0-9A-F]{12}$/.test(id) && to !== userId && memUsers.has(to) && !features.blocked(userId, to))
        await relayReceipt(userId, to, id).catch(e => console.error('Receipt storage failed:', e.message));
    } else if (msg.type === 'ping') {
      ws.send(JSON.stringify({ type: 'pong' }));
    }
  });

  ws.on('close', () => {
    if (online.get(userId) === ws) {
      // Gone while Rabta showed: last seen now. A phone connected in the background keeps when Rabta last showed.
      online.delete(userId); presenceUpdate(userId, ws.rabtaTold);
      for(const call of [...voiceCalls.values(),...videoCalls.values()]) if(call.from===userId||call.to===userId) away(call,userId);
    }
  });
  ws.on('error', () => { try { ws.close(); } catch (_) {} });
});

// A phone that lost its network (asleep, or moved between Wi-Fi and mobile data) leaves a socket that still looks
// open: a message written there is lost and a call rings nowhere. Every few minutes each socket is pinged (the app
// answers by itself); one that stayed silent since the previous round is closed, so its messages wait on the server
// and its calls ring once the phone reconnects.
const HEARTBEAT_MS = 4 * 60 * 1000;
setInterval(() => {
  for (const socket of [...online.values()]) {
    if (socket.rabtaAlive === false) { try { socket.terminate(); } catch (_) {} continue; }
    socket.rabtaAlive = false;
    try { socket.ping(); } catch (_) {}
  }
}, HEARTBEAT_MS).unref?.();

// Now and then the server forgets what phones already have: the content of messages delivered before it let go of it
// on delivery (once, then this finds nothing), markers of delivered messages after 30 days, and files everyone has
// saved (features.js). Messages still on their way are never touched.
const TIDY_MS = 30 * 60 * 1000, MARKER_MS = 30 * 24 * 60 * 60 * 1000;
async function tidy() {
  if (pool) {
    await pool.query('UPDATE ru_messages SET body=NULL WHERE delivered_at IS NOT NULL AND body IS NOT NULL');
    await pool.query('DELETE FROM ru_messages WHERE body IS NULL AND delivered_at < $1', [Date.now() - MARKER_MS]);
  }
  await features.tidy();
}
setInterval(() => tidy().catch(e => console.error('Clean-up failed:', e.message)), TIDY_MS).unref?.();

// A socket whose app stopped pinging is dead (the phone slept or lost its network without closing it). Closing it
// within about a minute lets what comes next wait on the server and wake the phone, instead of going nowhere.
setInterval(() => {
  const now = Date.now();
  for (const socket of [...online.values()]) if (socket.rabtaPings > 0 && now - socket.rabtaHeard > SILENT_MS) { try { socket.terminate(); } catch (_) {} }
  // A phone gone quiet stops showing online (last seen: when it was last heard); one heard again shows it again.
  for (const [id, socket] of [...online]) presenceUpdate(id, socket.rabtaTold, socket.rabtaHeard);
}, 15000).unref?.();

app.post('/api/groups', async (req, res) => {
  const creator = authId(req); if (!creator) return res.status(401).json({ error: 'login required' });
  const name = String(req.body?.name||'').trim().slice(0,40); if (!name) return res.status(400).json({ error: 'name required' });
  const raw = Array.isArray(req.body?.members) ? req.body.members : [];
  const members = new Set([creator, ...raw.filter(m => memUsers.has(m)).slice(0,49)]);
  const id = require('crypto').randomBytes(6).toString('hex').toUpperCase();
  const created_at = Date.now();
  if (pool) {
    try {
      await pool.query('INSERT INTO rg_groups(id,name,creator,members,created_at) VALUES($1,$2,$3,$4,$5) ON CONFLICT(id) DO NOTHING',
        [id, name, creator, [...members].join(','), created_at]);
    } catch (e) {
      console.error('Group storage failed:', e.message);
      return res.status(503).json({ error: 'Group could not be saved, please try again' });
    }
  }
  groups.set(id, { id, name, creator, members, created_at });
  const payload = JSON.stringify({ type:'group_created', group:{ id, name, members:[...members] } });
  for (const mid of members) { const w = online.get(mid); if (w && w.readyState===1) w.send(payload); }
  res.json({ id, name, members:[...members] });
});

// ---------- delete account ----------
// Google Play asks apps with sign-up to let people delete their account, in the app and on the web. The account goes
// with everything it left on the server: messages and receipts still on their way to or from it, its place in groups,
// its updates, files, photo, blocks, reports, push tokens, phones and devices, and its calls end. Other people keep
// what is on their own phones; the username becomes free.
async function deleteAccount(userId) {
  const user = memUsers.get(userId); if (!user) return;
  // Storage first, sign-in last: if a step fails, the account still works and the person can try again.
  if (pool) {
    await pool.query('DELETE FROM ru_messages WHERE from_id=$1 OR to_id=$1', [userId]);
    await pool.query('DELETE FROM ru_receipts WHERE by_id=$1 OR to_id=$1', [userId]);
    for (const g of groups.values()) if (g.members.has(userId)) await pool.query('UPDATE rg_groups SET members=$1 WHERE id=$2', [[...g.members].filter(m => m !== userId).join(','), g.id]);
  }
  await features.forget(userId); await push.forgetUser(userId); await account.forget(userId);
  if (pool) await pool.query('DELETE FROM ru_users WHERE id=$1', [userId]);
  memUsers.delete(userId); loginFails.delete(user.username);
  for (const g of groups.values()) g.members.delete(userId);
  for (let i = memMessages.length - 1; i >= 0; i--) if (memMessages[i].from_id === userId || memMessages[i].to_id === userId) memMessages.splice(i, 1);
  for (let i = memReceipts.length - 1; i >= 0; i--) if (memReceipts[i].by_id === userId || memReceipts[i].to_id === userId) memReceipts.splice(i, 1);
  for (const [key, t] of [...awaiting]) { const [, from, to] = key.split('|'); if (from === userId || to === userId) { clearTimeout(t); awaiting.delete(key); } }
  for (const call of [...voiceCalls.values(), ...videoCalls.values()]) if (call.from === userId || call.to === userId) endCall(call);
  const w = online.get(userId); if (w) try { w.close(1008, 'auth'); } catch (_) {}
  presenceChanged(userId); for (const x of online.values()) if (x.rabtaWatch === userId) x.rabtaWatch = '';
}
// The PIN is typed again to delete, under the same limit as signing in: 10 wrong ones in 15 minutes.
const tooManyTries = username => { const f = loginFails.get(username); return !!f && f.count >= 10 && f.until > Date.now(); };
function wrongPin(username) { const f = loginFails.get(username), cur = (f && f.until > Date.now()) ? f : { count: 0, until: Date.now() + 15 * 60 * 1000 }; cur.count++; loginFails.set(username, cur); }
async function deleted(res, userId) {
  try { await deleteAccount(userId); res.json({ ok: true }); }
  catch (e) { console.error('Account deletion failed:', e.message); res.status(503).json({ error: 'The account could not be deleted, please try again' }); }
}
// From the app: the signed-in phone (a trusted one, once the account has any), with the PIN.
app.post('/api/me/delete', async (req, res) => {
  const auth = readTokenFull(bearer(req)), user = auth && memUsers.get(auth.id);
  if (!user) return res.status(401).json({ error: 'Login required' });
  if (tooManyTries(user.username)) return res.status(429).json({ error: 'Too many attempts, wait 15 minutes' });
  if (!checkPin(String(req.body?.pin || ''), user.pass_hash)) { wrongPin(user.username); return res.status(401).json({ error: 'Wrong PIN' }); }
  await deleted(res, user.id);
});
// From the web page: the PIN, and the recovery code once the account has a trusted phone, as signing in on a new phone needs.
app.post('/api/delete-account', async (req, res) => {
  const username = String(req.body?.username || '').trim().toLowerCase(), code = String(req.body?.recoveryCode || '');
  if (tooManyTries(username)) return res.status(429).json({ error: 'Too many attempts, wait 15 minutes' });
  const user = [...memUsers.values()].find(u => u.username === username);
  if (!user || !checkPin(String(req.body?.pin || ''), user.pass_hash)) { wrongPin(username); return res.status(401).json({ error: 'Wrong username or PIN' }); }
  if (!account.mayDelete(user, code)) {
    if (code) wrongPin(username);
    return res.status(403).json({ error: code ? 'Wrong recovery code' : 'Enter the recovery code you saved when you set up Rabta, or delete the account in the app: Settings → Delete account', recoveryCode: true });
  }
  await deleted(res, user.id);
});

// ---------- web pages: privacy policy and account deletion ----------
const CONTACT = String(process.env.CONTACT_EMAIL || '').trim().replace(/[<>&"']/g, '');
const page = (title, body) => `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${title}</title>` +
  `<style>body{font:16px/1.55 system-ui,sans-serif;max-width:720px;margin:0 auto;padding:24px 16px;color:#1d2433;background:#fff}h1{font-size:26px}h2{font-size:19px;margin-top:28px}` +
  `label{display:block;margin:14px 0 4px;font-weight:600}input{width:100%;box-sizing:border-box;padding:10px;font:inherit;border:1px solid #9aa3b2;border-radius:8px}` +
  `button{margin-top:18px;padding:12px 18px;font:inherit;font-weight:600;color:#fff;background:#c62828;border:0;border-radius:8px}#out{margin-top:16px;font-weight:600}` +
  `@media(prefers-color-scheme:dark){body{color:#e8ecf4;background:#0f141c}input{background:#1a212c;color:inherit}}</style></head><body>${body}</body></html>`;
app.get('/privacy', (req, res) => res.type('html').send(page('Rabta privacy policy', `<h1>Rabta privacy policy</h1><p>Updated 20 October 2026. Rabta is a messaging app for chats, voice and video calls, status updates and messages between nearby phones without internet.</p>
<h2>What the Rabta server keeps, and why</h2><ul>
<li><b>Your account:</b> your username, display name and account ID, and your PIN stored only as a salted hash, so you can sign in. When you were last online, which your contacts see. Your profile photo, if you add one.</li>
<li><b>Your phone number:</b> newer app versions collect a private contact number at signup. It is not SMS verified or used as proof of identity. If you opt into phone discovery, people who already have that number can find your account. Contact matching sends numbers you select from your address book to the server; these lookup lists are not stored. It is deleted with your account.</li>
<li><b>Your phone:</b> a one-way hash of the phone's Android ID, so a phone has one Rabta account, and the public keys of the phones you trust for signing in.</li>
<li><b>Messages, photos, videos, voice messages and files</b> go through the server, which keeps a message only until it is delivered and a file only until the people it was sent to have saved it; then the content is deleted. Small delivery records (message IDs and times, no content) are kept for up to 30 days. Internet messages are protected by TLS on the way, but they are not end-to-end encrypted, so they pass through the server readable.</li>
<li><b>Calls:</b> voice calls pass through the server as they happen, and video calls go between the phones or through a relay. Calls are never recorded or stored.</li>
<li><b>Status updates</b> are kept for 24 hours and shown only to the people you choose.</li>
<li><b>Notifications:</b> a Firebase Cloud Messaging token (from Google) lets the server wake Rabta for new messages and calls. The wake carries no message content.</li>
<li>The people you block, and reports you send about misuse.</li></ul>
<h2>On your phone</h2><p>Your chats are stored on your phone. A backup is made only when you choose, encrypted with your password. Nearby messaging uses Bluetooth and Wi-Fi to find Rabta phones around you; some Android versions need the location permission for that, but Rabta does not collect or store your location. Nearby messages are encrypted between the phones.</p>
<h2>Who else sees it</h2><p>Rabta shows no ads, sells no data and uses no analytics or tracking. Google delivers the wake notifications, and the server and its database run on hosting providers that process the data only to run Rabta.</p>
<h2>Deleting your account</h2><p>In Rabta: <b>Settings → Delete account</b>. On the web: <a href="/delete-account">delete your account here</a>. This removes your account, profile photo, status updates, phones and devices, push tokens, and the messages and files still on their way to or from you; Rabta also deletes that account's chats on your phone. Messages already delivered stay on the phones of the people who received them.</p>
<h2>Children</h2><p>Rabta is not meant for children under 13.</p>
${CONTACT ? `<h2>Contact</h2><p>Questions about privacy: <a href="mailto:${CONTACT}">${CONTACT}</a></p>` : ''}`)));
app.get('/delete-account', (req, res) => res.type('html').send(page('Delete your Rabta account', `<h1>Delete your Rabta account</h1>
<p>The easiest way is in the app: <b>Settings → Delete account</b>. You can also do it here, for example if you no longer have your phone.</p>
<p>This deletes your account, profile photo, status updates, phones and devices, push tokens, and messages and files still on their way to or from you. It cannot be undone. Messages already delivered stay on the phones of the people who received them. Your username becomes free for others.</p>
<form id="f"><label for="u">Username</label><input id="u" name="username" autocomplete="username" required>
<label for="p">PIN</label><input id="p" name="pin" type="password" inputmode="numeric" autocomplete="current-password" required>
<label for="c">Recovery code (needed if you have a trusted phone)</label><input id="c" name="recoveryCode" autocomplete="off" placeholder="XXXX-XXXX-XXXX">
<button type="submit">Delete my account</button></form><p id="out" role="status"></p>
<script>const v=id=>document.getElementById(id).value;document.getElementById('f').addEventListener('submit',async e=>{e.preventDefault();const out=document.getElementById('out');
if(!confirm('Delete this Rabta account for good?'))return;out.textContent='Deleting…';
try{const r=await fetch('/api/delete-account',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:v('u'),pin:v('p'),recoveryCode:v('c')})});
const b=await r.json().catch(()=>({}));out.textContent=r.ok?'Your Rabta account has been deleted.':(b.error||'Could not delete, please try again');if(r.ok)e.target.reset();}
catch(_){out.textContent='No connection, please try again';}});</script>
${CONTACT ? `<p>Need help? <a href="mailto:${CONTACT}">${CONTACT}</a></p>` : ''}`)));

app.get('/health', (req, res) => res.json({ ok: true, capabilities: ['phone-collection','contact-discovery','account-visibility','call-recovery-120s'], db: !!pool, online: online.size, users: memUsers.size, push: push.enabled(), ...push.stats?.() }));

const features=require('./features')({app,authId,users:memUsers,getPool:()=>pool,onPrivacyChanged:presenceChanged});
require('./discovery')({app,authId,users:memUsers,blocked:features.blocked,getPool:()=>pool});
const push=require('./push')({app,authId,getPool:()=>pool});
const PORT = process.env.PORT || 3000;
initDb().then(()=>features.init()).then(()=>push.init()).then(() => server.listen(PORT, process.env.HOST, () => console.log('Rabta server on', PORT)))
  .catch(e => { console.error('Database initialization failed:', e.message); process.exitCode=1; });


// Opt-in until the linked browser and updated phone have passed acceptance.
if (process.env.RABTA_WEB_ORIGIN) webCompanion = require('./web-http')({
  app, origin: process.env.RABTA_WEB_ORIGIN,
  authPhone: req => readTokenFull(bearer(req)),
  deviceAllowed: (user,device) => memUsers.has(user) && account.tokenAllowed(user,device),
  phoneSocket: (user,device) => { const socket=online.get(user); return socket?.rabtaDevice===device ? socket : null; }
});
