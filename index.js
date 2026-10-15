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
const wss = new WebSocketServer({ server, path: '/ws' });

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
    CREATE INDEX IF NOT EXISTS idx_rum_to ON ru_messages (to_id, delivered_at);
    CREATE TABLE IF NOT EXISTS rg_groups(id TEXT PRIMARY KEY, name TEXT, creator TEXT, members TEXT, created_at BIGINT);
  `);
  const rows = (await pool.query('SELECT id,username,pass_hash,name FROM ru_users LIMIT 10000')).rows;
  for (const r of rows) memUsers.set(r.id, r);
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
const groups = new Map();  // groupId -> {id,name,creator,members:Set}

async function deliverPending(userId, ws) {
  let msgs;
  if (pool) {
    const r = await pool.query('SELECT * FROM ru_messages WHERE to_id=$1 AND delivered_at IS NULL ORDER BY sent_at ASC LIMIT 200', [userId]);
    msgs = r.rows;
    if (msgs.length) await pool.query('UPDATE ru_messages SET delivered_at=$1 WHERE to_id=$2 AND delivered_at IS NULL', [Date.now(), userId]);
  } else {
    msgs = memMessages.filter(m => m.to_id === userId && !m.delivered_at);
    msgs.forEach(m => { m.delivered_at = Date.now(); });
  }
  for (const m of msgs) {
    ws.send(JSON.stringify({ type: 'msg', id: m.msg_id, from: m.from_id, fromName: m.from_name, body: m.body, msgType: m.type || 'text', t: Number(m.sent_at), offline: true }));
  }
}

async function storeMessage(fromId, fromName, toId, msgId, body, type, sentAt) {
  if (pool) {
    await pool.query('INSERT INTO ru_messages(msg_id,from_id,from_name,to_id,body,type,sent_at) VALUES($1,$2,$3,$4,$5,$6,$7)',
      [msgId, fromId, fromName, toId, (body||'').substring(0,65535), type, sentAt]).catch(() => {});
  } else {
    memMessages.push({ id: memMsgId++, msg_id: msgId, from_id: fromId, from_name: fromName, to_id: toId, body, type, sent_at: sentAt, delivered_at: null });
    if (memMessages.length > 10000) memMessages.splice(0, memMessages.length - 10000);
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

  ws.on('message', async (raw) => {
    let msg; try { msg = JSON.parse(raw.toString()); } catch { return; }
    if (msg.type === 'msg') {
      const { id: msgId, to, body, msgType = 'text', t } = msg;
      if (!msgId || !to || !body) return;
      const sentAt = Number(t) || Date.now();
      const recipient = online.get(to);
      if (recipient && recipient.readyState === 1) {
        recipient.send(JSON.stringify({ type: 'msg', id: msgId, from: userId, fromName: user.name, body, msgType, t: sentAt }));
        ws.send(JSON.stringify({ type: 'ack', id: msgId, status: 'delivered' }));
      } else {
        await storeMessage(userId, user.name, to, msgId, body, msgType, sentAt);
        ws.send(JSON.stringify({ type: 'ack', id: msgId, status: 'stored' }));
      }
    } else if (msg.type === 'call_init') {
      // Notify the other person of an incoming call
      const callee = online.get(msg.to);
      if (callee) callee.send(JSON.stringify({ type: 'incoming_call', from: userId, fromName: user.name }));
    } else if (msg.type === 'call_audio') {
      // Relay audio frame instantly (low latency)
      const callee = online.get(msg.to);
      if (callee && callee.readyState === 1) callee.send(JSON.stringify({ type: 'call_audio', from: userId, data: msg.data }));
    } else if (msg.type === 'call_end') {
      const callee = online.get(msg.to);
      if (callee) callee.send(JSON.stringify({ type: 'call_end', from: userId }));
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
        else await storeMessage(userId, user.name, mid, msg.id, msg.body, msg.msgType||'text', now);
      }
      ws.send(JSON.stringify({ type:'ack', id:msg.id, status:'sent' }));
    } else if (msg.type === 'ping') {
      ws.send(JSON.stringify({ type: 'pong' }));
    }
  });

  ws.on('close', () => {
    if (online.get(userId) === ws) { online.delete(userId); updateLastSeen(userId); }
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
