'use strict';
// Wakes a phone through Firebase Cloud Messaging when a message or call waits for it and Rabta is not connected there
// (closed, or stopped by the phone). The push carries no message text, only "wake": Rabta then connects and fetches
// what waits, as when it is opened. Off until FCM_SERVICE_ACCOUNT holds the Firebase service account JSON (Firebase:
// Project settings > Service accounts > Generate new private key). No packages needed.
const crypto = require('crypto');
const https = require('https');

const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';
const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const VALID = /^[\w:.-]{32,4096}$/;   // FCM registration tokens: letters, digits and : _ - .
const MESSAGE_GAP = 30000;            // one wake per person per 30 s: the phone fetches everything waiting at once
const PER_PERSON = 5;                 // phones kept per account; the oldest goes first

function post(url, headers, body) {
  return new Promise((resolve, reject) => {
    const req = https.request(url, { method: 'POST', headers: { ...headers, 'content-length': Buffer.byteLength(body) }, timeout: 10000 }, res => {
      let data = ''; res.setEncoding('utf8');
      res.on('data', d => { data += d; }); res.on('end', () => resolve({ status: res.statusCode, body: data }));
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
    req.end(body);
  });
}

/**
 * The service account from FCM_SERVICE_ACCOUNT, read the way people paste it: the whole JSON file, the same in quotes or
 * base64, or with its key's line breaks written as \\n. Gives the account, or why wake pushes stay off (never the key).
 */
function readAccount(raw) {
  let text = String(raw || '').trim();
  if (!text) return { problem: 'FCM_SERVICE_ACCOUNT is not set' };
  if (!text.startsWith('{') && !text.startsWith('"')) {
    const decoded = Buffer.from(text, 'base64').toString('utf8').trim();
    if (decoded.startsWith('{')) text = decoded;
  }
  let o;
  try { o = JSON.parse(text); if (typeof o === 'string') o = JSON.parse(o); } catch { return { problem: 'FCM_SERVICE_ACCOUNT is not JSON: paste the whole downloaded key file' }; }
  if (!o || typeof o !== 'object') return { problem: 'FCM_SERVICE_ACCOUNT is not JSON: paste the whole downloaded key file' };
  if (o.project_info || o.client) return { problem: 'FCM_SERVICE_ACCOUNT holds google-services.json; it needs the service account key (Firebase > Project settings > Service accounts > Generate new private key)' };
  if (!o.client_email || !o.private_key || !o.project_id) return { problem: 'FCM_SERVICE_ACCOUNT is not a complete service account key: paste the whole downloaded file' };
  o.private_key = String(o.private_key).replace(/\\n/g, '\n');
  try { crypto.createPrivateKey(o.private_key); } catch { return { problem: "FCM_SERVICE_ACCOUNT's private key is damaged: paste the whole downloaded file as it is" }; }
  return { account: o };
}

module.exports = function install({ app, authId, getPool, account = process.env.FCM_SERVICE_ACCOUNT, request = post, clock = Date.now }) {
  const setup = readAccount(account);
  const sa = setup.account || null;
  if (account && !sa) console.error(setup.problem + '; push stays off.');
  const tokens = new Map();    // token -> userId
  const lastWake = new Map();  // userId -> time of the last message wake
  let access = '', accessUntil = 0;
  let lastSend = null;         // {status, at} of the latest wake sent to Firebase, for /health

  async function store(token, user) {
    const db = getPool();
    if (db) await db.query('INSERT INTO rf_data(kind,id,data) VALUES($1,$2,$3) ON CONFLICT(kind,id) DO UPDATE SET data=$3', ['push', token, JSON.stringify({ user, updated: clock() })]);
  }
  async function forget(token) {
    tokens.delete(token);
    const db = getPool(); if (db) await db.query('DELETE FROM rf_data WHERE kind=$1 AND id=$2', ['push', token]);
  }
  async function init() {
    const db = getPool(); if (!db) return;
    await db.query('CREATE TABLE IF NOT EXISTS rf_data(kind TEXT,id TEXT,data TEXT,PRIMARY KEY(kind,id))');
    for (const row of (await db.query("SELECT id,data FROM rf_data WHERE kind='push'")).rows) try { tokens.set(row.id, JSON.parse(row.data).user); } catch {}
  }

  // Google's sign-in for servers: a JWT signed with the service account's key buys an access token for an hour.
  async function accessToken() {
    if (access && clock() < accessUntil) return access;
    const now = Math.floor(clock() / 1000), part = o => Buffer.from(JSON.stringify(o)).toString('base64url');
    const unsigned = part({ alg: 'RS256', typ: 'JWT' }) + '.' + part({ iss: sa.client_email, scope: SCOPE, aud: TOKEN_URL, iat: now, exp: now + 3600 });
    const assertion = unsigned + '.' + crypto.sign('RSA-SHA256', Buffer.from(unsigned), sa.private_key).toString('base64url');
    const res = await request(TOKEN_URL, { 'content-type': 'application/x-www-form-urlencoded' },
      new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }).toString());
    let o = {}; try { o = JSON.parse(res.body); } catch {}
    if (res.status !== 200 || !o.access_token) throw new Error('Firebase sign-in failed (' + res.status + ')');
    access = o.access_token; accessUntil = clock() + Math.max(60, (Number(o.expires_in) || 3600) - 300) * 1000;
    return access;
  }

  async function send(token, kind) {
    const url = `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(sa.project_id)}/messages:send`;
    // High priority, so Android delivers it at once, also in deep sleep. A call ends after 45 s; a message waits a day.
    const body = JSON.stringify({ message: { token, data: { type: 'wake', kind }, android: { priority: 'HIGH', ttl: kind === 'call' ? '45s' : '86400s' } } });
    const res = await request(url, { 'content-type': 'application/json', authorization: 'Bearer ' + await accessToken() }, body);
    lastSend = { status: res.status, at: clock() };
    if (res.status === 401) access = '';
    // Rabta was uninstalled or the phone got a new token: this one never works again.
    else if (res.status === 404 || (res.status === 400 && /UNREGISTERED|INVALID_ARGUMENT/.test(res.body))) await forget(token);
    return res.status === 200;
  }

  /** Something waits for [userId], who is not connected: wake their phones. Calls every time, messages once per 30 s. */
  function wake(userId, kind = 'message') {
    if (!sa) return;
    if (kind !== 'call') {
      if (clock() - (lastWake.get(userId) || 0) < MESSAGE_GAP) return;
      lastWake.set(userId, clock());
    }
    for (const [token, user] of tokens) if (user === userId) send(token, kind).catch(e => { lastSend = { status: 'error', at: clock() }; console.error('Push failed:', e.message); });
  }

  const signedIn = (req, res) => { const me = authId(req); if (!me) res.status(401).json({ error: 'Login required' }); return me; };
  app.post('/api/me/push', async (req, res) => {
    const me = signedIn(req, res); if (!me) return;
    const token = String(req.body?.token || '');
    if (!VALID.test(token)) return res.status(400).json({ error: 'Invalid push token' });
    // A phone is signed in to one account at a time, so its token moves along with it.
    tokens.delete(token); tokens.set(token, me);
    const mine = [...tokens].filter(([, user]) => user === me).map(([t]) => t);
    for (const old of mine.slice(0, -PER_PERSON)) await forget(old).catch(() => {});
    try { await store(token, me); } catch { return res.status(503).json({ error: 'Could not save; try again' }); }
    res.json({ ok: true, push: !!sa });
  });
  app.delete('/api/me/push', async (req, res) => {
    const me = signedIn(req, res); if (!me) return;
    const token = String(req.body?.token || '');
    if (tokens.get(token) === me) await forget(token).catch(() => {});
    res.json({ ok: true });
  });

  // How many phones can be woken, and how the latest wake went (no tokens, ids or message content).
  const stats = () => ({ pushPhones: tokens.size, pushLast: lastSend, pushSetup: sa ? 'ok' : setup.problem, pushProject: sa ? sa.project_id : null });
  return { init, wake, enabled: () => !!sa, stats };
};
