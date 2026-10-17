// Rabta account security: username / display-name edits and device-bound sign-in.
//
// Every phone running a current app has its own non-exportable Android Keystore key. A correct PIN on a phone
// the account has not trusted yet is not enough: one of the account's trusted phones must approve it, or the
// user enters the one-time recovery code shown when the account got its first trusted phone. Accounts with no
// trusted phone yet (older apps) keep working until their first phone with the current app signs in.
const crypto = require('crypto');

const DAY = 86400000;
const USERNAME_COOLDOWN = 30 * DAY;
const USERNAME_RESERVE = 30 * DAY;
const REQUEST_LIFE = 5 * 60000;
const PROOF_WINDOW = 5 * 60000;
const NAME_MAX = 30;
const UN_RE = /^[a-z0-9_]{3,20}$/;
const RECOVERY_ALPHABET = 'ABCDEFGHJKMNPQRSTVWXYZ23456789';

module.exports = function install({ app, users, getPool, authToken, makeToken, checkPin, sendTo, closeDevice }) {
  const devices = new Map();   // userId -> Map(deviceId -> {id,key,name,created,seen})
  const reserved = new Map();  // old username -> {user, until}
  const requests = new Map();  // requestId -> {id,user,device,expires,status}
  const usedProofs = new Map(); // signature -> expiry, so a signed proof is accepted once

  const devicesOf = id => devices.get(id) || new Map();
  const byUsername = name => [...users.values()].find(u => u.username === name);
  const ensure = (ok, status, error) => { if (!ok) throw Object.assign(new Error(error), { status }); };
  const route = fn => async (req, res) => {
    try { await fn(req, res); } catch (e) {
      if (e.status) return res.status(e.status).json({ error: e.message, ...(e.extra || {}) });
      console.error('Account request failed:', e.message); res.status(503).json({ error: 'Could not save the change, please try again' });
    }
  };

  async function init() {
    const db = getPool(); if (!db) return;
    await db.query(`
      ALTER TABLE ru_users ADD COLUMN IF NOT EXISTS username_changed_at BIGINT;
      ALTER TABLE ru_users ADD COLUMN IF NOT EXISTS recovery_hash TEXT;
      CREATE TABLE IF NOT EXISTS ru_devices(user_id TEXT NOT NULL, device_id TEXT NOT NULL, public_key TEXT NOT NULL, name TEXT, created_at BIGINT, last_seen BIGINT, PRIMARY KEY(user_id, device_id));
      CREATE TABLE IF NOT EXISTS ru_reserved(username TEXT PRIMARY KEY, user_id TEXT NOT NULL, until BIGINT NOT NULL);
    `);
    for (const r of (await db.query('SELECT id,username_changed_at,recovery_hash FROM ru_users')).rows) {
      const u = users.get(r.id); if (u) { u.username_changed_at = r.username_changed_at == null ? null : Number(r.username_changed_at); u.recovery_hash = r.recovery_hash; }
    }
    for (const r of (await db.query('SELECT * FROM ru_devices')).rows) {
      if (!devices.has(r.user_id)) devices.set(r.user_id, new Map());
      devices.get(r.user_id).set(r.device_id, { id: r.device_id, key: r.public_key, name: r.name, created: Number(r.created_at), seen: Number(r.last_seen) });
    }
    for (const r of (await db.query('SELECT * FROM ru_reserved WHERE until > $1', [Date.now()])).rows) reserved.set(r.username, { user: r.user_id, until: Number(r.until) });
  }

  // ---------- usernames and display names ----------
  /** Whether [name] can be taken by [me] (null while registering). */
  function usernameStatus(name, me = null) {
    if (!UN_RE.test(name)) return { available: false, error: 'Use 3–20 letters, numbers or _' };
    const owner = byUsername(name);
    if (owner && owner.id === me) return { available: false, error: 'This is already your username' };
    const hold = reserved.get(name);
    if (owner || (hold && hold.until > Date.now() && hold.user !== me)) return { available: false, error: 'Username already used' };
    return { available: true };
  }
  const nextUsernameChange = u => u.username_changed_at ? Number(u.username_changed_at) + USERNAME_COOLDOWN : 0;
  function cleanName(raw) {
    const name = String(raw || '').replace(/[\u0000-\u001f\u007f]/g, '').replace(/\s+/g, ' ').trim();
    return name.length >= 1 && [...name].length <= NAME_MAX ? name : null;
  }

  app.get('/api/username/available', route(async (req, res) => {
    const name = String(req.query?.u || '').trim().toLowerCase();
    res.json(usernameStatus(name, authToken(req)?.id || null));
  }));

  app.get('/api/me/account', route(async (req, res) => {
    const auth = authToken(req); ensure(auth, 401, 'Login required');
    const u = users.get(auth.id); ensure(u, 404, 'Not found');
    const next = nextUsernameChange(u);
    res.json({ id: u.id, username: u.username, name: u.name, nextUsernameChangeAt: next > Date.now() ? next : 0,
      devices: devicesOf(u.id).size, hasRecoveryCode: !!u.recovery_hash, device: auth.device });
  }));

  app.post('/api/me/username', route(async (req, res) => {
    const auth = authToken(req); ensure(auth, 401, 'Login required');
    const u = users.get(auth.id); ensure(u, 404, 'Not found');
    const name = String(req.body?.username || '').trim().toLowerCase();
    const next = nextUsernameChange(u);
    if (next > Date.now()) throw Object.assign(new Error('You can change your username again after 30 days'), { status: 429, extra: { nextUsernameChangeAt: next } });
    const status = usernameStatus(name, u.id); ensure(status.available, 409, status.error);
    const old = u.username, now = Date.now();
    const db = getPool();
    if (db) {
      try { await db.query('UPDATE ru_users SET username=$1, username_changed_at=$2 WHERE id=$3', [name, now, u.id]); }
      catch (e) { if (e.code === '23505') throw Object.assign(new Error('Username already used'), { status: 409 }); throw e; }
      await db.query('INSERT INTO ru_reserved(username,user_id,until) VALUES($1,$2,$3) ON CONFLICT(username) DO UPDATE SET user_id=$2, until=$3', [old, u.id, now + USERNAME_RESERVE]);
      await db.query('DELETE FROM ru_reserved WHERE username=$1', [name]);
    }
    // The old name stays reserved for this account for 30 days, so nobody can pose as its owner.
    reserved.set(old, { user: u.id, until: now + USERNAME_RESERVE }); reserved.delete(name);
    u.username = name; u.username_changed_at = now;
    res.json({ username: name, nextUsernameChangeAt: now + USERNAME_COOLDOWN });
  }));

  app.post('/api/me/name', route(async (req, res) => {
    const auth = authToken(req); ensure(auth, 401, 'Login required');
    const u = users.get(auth.id); ensure(u, 404, 'Not found');
    const name = cleanName(req.body?.name); ensure(name, 400, `Display name must be 1–${NAME_MAX} characters`);
    const db = getPool(); if (db) await db.query('UPDATE ru_users SET name=$1 WHERE id=$2', [name, u.id]);
    u.name = name; res.json({ name });
  }));

  // ---------- device keys ----------
  /** A device proves possession of its key by signing "rabta-device / purpose / device id / time". */
  function verifyDevice(d, purpose) {
    try {
      if (!d || typeof d.key !== 'string' || d.key.length > 1000) return null;
      const der = Buffer.from(d.key, 'base64');
      const key = crypto.createPublicKey({ key: der, format: 'der', type: 'spki' });
      if (key.asymmetricKeyType !== 'ec') return null;
      const id = crypto.createHash('sha256').update(der).digest('hex').slice(0, 32);
      const time = Number(d.time), sig = String(d.sig || '');
      if (d.id !== id || !Number.isFinite(time) || Math.abs(Date.now() - time) > PROOF_WINDOW || usedProofs.has(sig)) return null;
      if (!crypto.verify('sha256', Buffer.from(`rabta-device\n${purpose}\n${id}\n${time}`), key, Buffer.from(sig, 'base64'))) return null;
      const now = Date.now(); for (const [s, until] of usedProofs) if (until < now) usedProofs.delete(s);
      usedProofs.set(sig, now + PROOF_WINDOW * 2);
      return { id, key: d.key, name: String(d.name || 'Phone').replace(/[\u0000-\u001f]/g, '').slice(0, 40) };
    } catch { return null; }
  }
  async function addDevice(userId, d) {
    const now = Date.now(), db = getPool();
    if (db) await db.query('INSERT INTO ru_devices(user_id,device_id,public_key,name,created_at,last_seen) VALUES($1,$2,$3,$4,$5,$5) ON CONFLICT(user_id,device_id) DO UPDATE SET last_seen=$5',
      [userId, d.id, d.key, d.name, now]);
    if (!devices.has(userId)) devices.set(userId, new Map());
    devices.get(userId).set(d.id, { id: d.id, key: d.key, name: d.name, created: now, seen: now });
  }
  async function removeDevice(userId, deviceId) {
    const db = getPool(); if (db) await db.query('DELETE FROM ru_devices WHERE user_id=$1 AND device_id=$2', [userId, deviceId]);
    devicesOf(userId).delete(deviceId); closeDevice(userId, deviceId);
  }
  function recoveryCode() {
    const bytes = crypto.randomBytes(12);
    const code = [...bytes].map(b => RECOVERY_ALPHABET[b % RECOVERY_ALPHABET.length]).join('');
    return code.match(/.{4}/g).join('-');
  }
  const normalizeCode = c => String(c || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
  async function rotateRecovery(u) {
    const code = recoveryCode(), s = crypto.randomBytes(16).toString('hex');
    const hash = s + ':' + crypto.scryptSync(normalizeCode(code), s, 32).toString('hex');
    const db = getPool(); if (db) await db.query('UPDATE ru_users SET recovery_hash=$1 WHERE id=$2', [hash, u.id]);
    u.recovery_hash = hash; return code;
  }
  const recoveryMatches = (u, code) => !!u.recovery_hash && normalizeCode(code).length === 12 && checkPin(normalizeCode(code), u.recovery_hash);
  const session = (u, deviceId, extra = {}) => ({ id: u.id, username: u.username, name: u.name, token: makeToken(u.id, deviceId), ...extra });

  /** A token is valid only for a trusted device; a token without a device only while the account has none. */
  function tokenAllowed(userId, deviceId) {
    const list = devicesOf(userId);
    if (!deviceId) return list.size === 0;
    const d = list.get(deviceId); if (d) d.seen = Date.now();
    return !!d;
  }

  /** After a correct PIN: returns [status, body, countsAsFailure]. */
  async function signIn(u, body) {
    const list = devicesOf(u.id);
    if (!body?.device) return list.size ? [426, { error: 'Update Rabta to sign in on this phone' }, false] : [200, session(u, null), false];
    const d = verifyDevice(body.device, 'login:' + u.username);
    if (!d) return [400, { error: 'This phone could not prove its identity — check the date and time' }, false];
    if (list.has(d.id)) return [200, session(u, d.id), false];
    if (body.recoveryCode) {
      if (!recoveryMatches(u, body.recoveryCode)) return [401, { error: 'Wrong recovery code' }, true];
      // Recovery means the other phones may be lost or stolen: this phone replaces them.
      for (const id of [...list.keys()]) await removeDevice(u.id, id);
      await addDevice(u.id, d);
      return [200, session(u, d.id, { recoveryCode: await rotateRecovery(u) }), false];
    }
    if (!list.size) {
      // The account's first phone with device security (a new account, or an existing one after the update).
      await addDevice(u.id, d);
      return [200, session(u, d.id, u.recovery_hash ? {} : { recoveryCode: await rotateRecovery(u) }), false];
    }
    const request = { id: crypto.randomBytes(16).toString('hex'), user: u.id, device: d, expires: Date.now() + REQUEST_LIFE, status: 'pending' };
    for (const [id, r] of requests) if (r.expires < Date.now() || (r.user === u.id && r.device.id === d.id)) requests.delete(id);
    requests.set(request.id, request);
    sendTo(u.id, { type: 'device_request', request: { id: request.id, name: d.name, at: Date.now(), expires: request.expires } });
    return [202, { pending: true, requestId: request.id, expires: request.expires }, false];
  }

  /** On registration: the creating phone becomes the first trusted phone. */
  async function registered(u, device) {
    const d = device && verifyDevice(device, 'register:' + u.username);
    if (!d) return session(u, null);
    await addDevice(u.id, d);
    return session(u, d.id, { recoveryCode: await rotateRecovery(u) });
  }

  // The new phone waits here; it gets a session only after a trusted phone approves it.
  app.post('/api/login/request/:id', route(async (req, res) => {
    const r = requests.get(String(req.params.id));
    ensure(r, 410, 'This sign-in request expired — sign in again');
    const d = verifyDevice(req.body?.device, 'poll:' + r.id);
    ensure(d && d.id === r.device.id, 403, 'This request belongs to another phone');
    if (r.expires < Date.now()) { requests.delete(r.id); ensure(false, 410, 'This sign-in request expired — sign in again'); }
    if (r.status === 'denied') { requests.delete(r.id); ensure(false, 403, 'Sign-in was declined on your other phone'); }
    if (r.status !== 'approved') return res.status(202).json({ pending: true, expires: r.expires });
    requests.delete(r.id);
    const u = users.get(r.user); ensure(u && devicesOf(u.id).has(d.id), 403, 'Sign-in was not approved');
    res.json(session(u, d.id));
  }));

  app.get('/api/devices', route(async (req, res) => {
    const auth = authToken(req); ensure(auth?.device, 401, 'Login required');
    const now = Date.now();
    res.json({
      devices: [...devicesOf(auth.id).values()].map(d => ({ id: d.id, name: d.name, created: d.created, lastSeen: d.seen, current: d.id === auth.device })),
      requests: [...requests.values()].filter(r => r.user === auth.id && r.status === 'pending' && r.expires > now).map(r => ({ id: r.id, name: r.device.name, expires: r.expires })),
    });
  }));

  app.post('/api/devices/requests/:id', route(async (req, res) => {
    const auth = authToken(req); ensure(auth?.device, 401, 'Login required');
    const r = requests.get(String(req.params.id));
    ensure(r && r.user === auth.id && r.status === 'pending' && r.expires > Date.now(), 410, 'This request expired');
    if (req.body?.approve === true) { await addDevice(auth.id, r.device); r.status = 'approved'; }
    else r.status = 'denied';
    res.json({ status: r.status });
  }));

  app.delete('/api/devices/:id', route(async (req, res) => {
    const auth = authToken(req); ensure(auth?.device, 401, 'Login required');
    const id = String(req.params.id);
    ensure(id !== auth.device, 400, 'Sign out from this phone instead');
    ensure(devicesOf(auth.id).has(id), 404, 'Phone not found');
    await removeDevice(auth.id, id); res.json({ ok: true });
  }));

  app.post('/api/me/recovery', route(async (req, res) => {
    const auth = authToken(req); ensure(auth?.device, 401, 'Login required');
    const u = users.get(auth.id); ensure(u && checkPin(String(req.body?.pin || ''), u.pass_hash), 401, 'Wrong PIN');
    res.json({ recoveryCode: await rotateRecovery(u) });
  }));

  // A phone already signed in with an older app version: it becomes trusted only if the account has no trusted phone yet.
  app.post('/api/me/device', route(async (req, res) => {
    const auth = authToken(req); ensure(auth, 401, 'Login required');
    const u = users.get(auth.id); ensure(u, 404, 'Not found');
    if (auth.device) return res.json(session(u, auth.device));
    const d = verifyDevice(req.body?.device, 'bootstrap:' + u.id);
    ensure(d, 400, 'This phone could not prove its identity — check the date and time');
    ensure(devicesOf(u.id).size === 0, 409, 'Sign in again on this phone');
    await addDevice(u.id, d);
    res.json(session(u, d.id, u.recovery_hash ? {} : { recoveryCode: await rotateRecovery(u) }));
  }));

  // For the owner only, when a user has lost every phone and the recovery code: set ADMIN_KEY (32+ chars) on the server.
  app.post('/api/admin/reset-devices', route(async (req, res) => {
    const key = process.env.ADMIN_KEY || '', given = String(req.headers['x-admin-key'] || '');
    const ok = key.length >= 32 && given.length === key.length && crypto.timingSafeEqual(Buffer.from(given), Buffer.from(key));
    ensure(ok, 404, 'Not found');
    const u = byUsername(String(req.body?.username || '').toLowerCase()); ensure(u, 404, 'User not found');
    for (const id of [...devicesOf(u.id).keys()]) await removeDevice(u.id, id);
    const db = getPool(); if (db) await db.query('UPDATE ru_users SET recovery_hash=NULL WHERE id=$1', [u.id]);
    u.recovery_hash = null; res.json({ ok: true });
  }));

  const pendingFor = userId => [...requests.values()].filter(r => r.user === userId && r.status === 'pending' && r.expires > Date.now())
    .map(r => ({ type: 'device_request', request: { id: r.id, name: r.device.name, at: Date.now(), expires: r.expires } }));

  return { init, usernameStatus, cleanName, signIn, registered, tokenAllowed, pendingFor };
};
