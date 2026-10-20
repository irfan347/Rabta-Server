const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

// The real server in a sandbox with fake HTTP/WebSocket objects; no network or production data.
function server(database = false) {
  const routes = new Map(); let connect; const queries = [];
  const app = { use() {}, post(p, f) { routes.set('POST ' + p, f); }, get(p, f) { routes.set('GET ' + p, f); }, delete(p, f) { routes.set('DELETE ' + p, f); } };
  const express = () => app; express.json = () => () => {};
  class WebSocketServer { on(event, cb) { if (event === 'connection') connect = cb; } }
  class Pool { async query(sql, args) { queries.push([sql, args]); return { rows: [] }; } }
  const context = vm.createContext({ require(name) { if (name === './features') return require('../features'); if (name === './account') return require('../account'); if (name === './push') return require('../push'); if (name === 'express') return express; if (name === 'ws') return { WebSocketServer }; if (name === 'http') return { createServer() { return { listen() {} }; } }; if (name === 'pg') return { Pool }; return require(name); },
    setTimeout: (fn, ms) => ({ unref() {} }), clearTimeout() {}, setInterval: (fn, ms) => ({ unref() {} }), process: { env: database ? { DATABASE_URL: 'test' } : {}, exitCode: 0 }, console, Buffer, URL });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../index.js'), 'utf8'), context);
  async function call(method, route, { body = {}, token, params = {}, query = {}, headers = {} } = {}) {
    let status = 200, json;
    const res = { status(n) { status = n; return this; }, json(v) { json = v; }, send(v) { json = v; }, setHeader() {}, type() { return this; } };
    await routes.get(method + ' ' + route)({ body, params, query, headers: { ...headers, ...(token ? { authorization: 'Bearer ' + token } : {}) } }, res);
    return { status, ...json };
  }
  function socket(token) {
    const handlers = {}, messages = []; const ws = { readyState: 1, closed: false, send(s, cb) { messages.push(JSON.parse(s)); cb?.(); }, close() { this.readyState = 3; this.closed = true; handlers.close?.(); }, on(e, f) { handlers[e] = f; } };
    connect(ws, { headers: { authorization: 'Bearer ' + token }, url: '/ws' }); return { ws, messages };
  }
  async function page(route) {
    let type = '', html = '';
    await routes.get('GET ' + route)({ headers: {}, params: {}, query: {} }, { type(t) { type = t; return this; }, send(v) { html = v; } });
    return { type, html };
  }
  return { call, socket, queries, page };
}

// A phone's Keystore key, as the app creates it: EC P-256, public key sent as X.509 (SPKI) DER.
function phone(name = 'Pixel') {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const der = publicKey.export({ format: 'der', type: 'spki' });
  const id = crypto.createHash('sha256').update(der).digest('hex').slice(0, 32);
  return { id, proof(purpose, time = Date.now()) {
    return { id, key: der.toString('base64'), name, time, sig: crypto.sign('sha256', Buffer.from(`rabta-device\n${purpose}\n${id}\n${time}`), privateKey).toString('base64') };
  } };
}
const register = (s, username, device) => s.call('POST', '/api/register', { body: { username, pin: '123456', name: username, ...(device ? { device: device.proof('register:' + username) } : {}) } });
const login = (s, username, device, extra = {}) => s.call('POST', '/api/login', { body: { username, pin: '123456', ...(device ? { device: device.proof('login:' + username) } : {}), ...extra } });
const me = (s, token) => s.call('GET', '/api/me', { token });

test('username: first change is immediate, the next waits 30 days, and the old name stays reserved', async () => {
  const s = server(); const ali = await register(s, 'ali'); await register(s, 'sara');
  assert.deepEqual(await s.call('GET', '/api/username/available', { query: { u: 'sara' } }), { status: 200, available: false, error: 'Username already used' });
  assert.equal((await s.call('GET', '/api/username/available', { query: { u: 'a!' } })).available, false);
  assert.equal((await s.call('GET', '/api/username/available', { query: { u: 'ali_new' } })).available, true);
  assert.equal((await s.call('GET', '/api/username/available', { query: { u: 'ali' }, token: ali.token })).error, 'This is already your username');

  const changed = await s.call('POST', '/api/me/username', { token: ali.token, body: { username: 'Ali_New' } });
  assert.equal(changed.status, 200); assert.equal(changed.username, 'ali_new');
  assert.equal((await me(s, ali.token)).username, 'ali_new', 'Same account, same ID, new name');
  assert.equal((await s.call('POST', '/api/me/username', { token: ali.token, body: { username: 'sara' } })).status, 429);
  const early = await s.call('POST', '/api/me/username', { token: ali.token, body: { username: 'ali_third' } });
  assert.equal(early.status, 429); assert.ok(early.nextUsernameChangeAt > Date.now() + 29 * 86400000);

  assert.equal((await register(s, 'ali')).status, 409, 'The old name is reserved for 30 days');
  assert.equal((await s.call('GET', '/api/username/available', { query: { u: 'ali' } })).error, 'Username already used');
  assert.equal((await s.call('GET', '/api/username/available', { query: { u: 'ali' }, token: ali.token })).available, true, 'Its owner may take it back');

  const real = Date.now;
  try {
    Date.now = () => real() + 31 * 86400000;
    assert.equal((await s.call('POST', '/api/me/username', { token: ali.token, body: { username: 'ali_third' } })).status, 200);
    assert.equal((await s.call('GET', '/api/username/available', { query: { u: 'ali' } })).available, true, 'Released after 30 days');
  } finally { Date.now = real; }
});

test('display name can change any time and is limited to 30 characters', async () => {
  const s = server(); const ali = await register(s, 'ali');
  for (const name of ['Ali Khan', 'Ali', '  Ali   Raza  ']) assert.equal((await s.call('POST', '/api/me/name', { token: ali.token, body: { name } })).status, 200);
  assert.equal((await me(s, ali.token)).name, 'Ali Raza');
  assert.equal((await s.call('POST', '/api/me/name', { token: ali.token, body: { name: 'x'.repeat(31) } })).status, 400);
  assert.equal((await s.call('POST', '/api/me/name', { token: ali.token, body: { name: '   ' } })).status, 400);
  assert.equal((await s.call('POST', '/api/me/name', { body: { name: 'Nope' } })).status, 401);
});

test('a stolen PIN on a new phone waits for approval from the trusted phone', async () => {
  const s = server(); const mine = phone('My phone'), thief = phone('Unknown phone');
  const created = await register(s, 'ali', mine);
  assert.match(created.recoveryCode, /^[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$/);
  assert.equal(created.token.split('.').length, 4);
  const trusted = s.socket(created.token);

  const attempt = await login(s, 'ali', thief);
  assert.equal(attempt.status, 202); assert.equal(attempt.token, undefined, 'No session from the PIN alone');
  assert.equal(trusted.messages.find(m => m.type === 'device_request').request.name, 'Unknown phone');
  assert.equal((await s.call('POST', '/api/login/request/:id', { params: { id: attempt.requestId }, body: { device: thief.proof('poll:' + attempt.requestId) } })).status, 202);
  assert.equal((await s.call('POST', '/api/login/request/:id', { params: { id: attempt.requestId }, body: { device: mine.proof('poll:' + attempt.requestId) } })).status, 403, 'Only the waiting phone can collect it');

  assert.equal((await s.call('POST', '/api/devices/requests/:id', { token: created.token, params: { id: attempt.requestId }, body: { approve: false } })).status, 'denied');
  assert.equal((await s.call('POST', '/api/login/request/:id', { params: { id: attempt.requestId }, body: { device: thief.proof('poll:' + attempt.requestId) } })).status, 403);

  const again = await login(s, 'ali', thief);
  const listed = await s.call('GET', '/api/devices', { token: created.token });
  assert.equal(listed.devices.length, 1); assert.equal(listed.requests[0].id, again.requestId);
  await s.call('POST', '/api/devices/requests/:id', { token: created.token, params: { id: again.requestId }, body: { approve: true } });
  const approved = await s.call('POST', '/api/login/request/:id', { params: { id: again.requestId }, body: { device: thief.proof('poll:' + again.requestId) } });
  assert.equal(approved.status, 200); assert.equal((await me(s, approved.token)).username, 'ali');
  assert.equal((await login(s, 'ali', thief)).status, 200, 'An approved phone signs in directly next time');
});

test('the recovery code signs in a new phone, removes the old ones and is replaced', async () => {
  const s = server(); const lost = phone(), fresh = phone(), other = phone();
  const created = await register(s, 'ali', lost);
  const oldSocket = s.socket(created.token);
  assert.equal((await login(s, 'ali', fresh, { recoveryCode: 'AAAA-BBBB-CCCC' })).status, 401);
  const recovered = await login(s, 'ali', fresh, { recoveryCode: created.recoveryCode.toLowerCase().replace(/-/g, ' ') });
  assert.equal(recovered.status, 200); assert.ok(recovered.recoveryCode && recovered.recoveryCode !== created.recoveryCode);
  assert.equal((await me(s, created.token)).status, 401, 'The lost phone is signed out');
  assert.equal(oldSocket.ws.closed, true);
  assert.equal((await login(s, 'ali', other, { recoveryCode: created.recoveryCode })).status, 401, 'A used code stops working');
  assert.equal((await login(s, 'ali', lost)).status, 202, 'The lost phone now needs approval');
});

test('device proofs cannot be replayed, altered or reused for another account', async () => {
  const s = server(); const mine = phone();
  await register(s, 'ali', mine); await register(s, 'sara');
  const proof = mine.proof('login:ali');
  assert.equal((await s.call('POST', '/api/login', { body: { username: 'ali', pin: '123456', device: proof } })).status, 200);
  assert.equal((await s.call('POST', '/api/login', { body: { username: 'ali', pin: '123456', device: proof } })).status, 400, 'Replay');
  assert.equal((await s.call('POST', '/api/login', { body: { username: 'ali', pin: '123456', device: mine.proof('login:sara') } })).status, 400, 'Wrong purpose');
  assert.equal((await s.call('POST', '/api/login', { body: { username: 'ali', pin: '123456', device: { ...mine.proof('login:ali'), id: 'f'.repeat(32) } } })).status, 400, 'Claimed another device ID');
  assert.equal((await s.call('POST', '/api/login', { body: { username: 'ali', pin: '123456', device: mine.proof('login:ali', Date.now() - 10 * 60000) } })).status, 400, 'Stale');
  assert.equal((await s.call('POST', '/api/login', { body: { username: 'ali', pin: '000000', device: mine.proof('login:ali') } })).status, 401, 'PIN is still required');
});

test('existing accounts: older apps keep working until the first updated phone becomes trusted', async () => {
  const s = server(); const legacy = await register(s, 'ali');
  assert.equal(legacy.token.split('.').length, 3); assert.equal(legacy.recoveryCode, undefined);
  assert.equal((await login(s, 'ali')).status, 200, 'Old app, no trusted phone yet');
  const mine = phone();
  const upgraded = await s.call('POST', '/api/me/device', { token: legacy.token, body: { device: mine.proof('bootstrap:' + legacy.id) } });
  assert.equal(upgraded.status, 200); assert.ok(upgraded.recoveryCode); assert.equal(upgraded.token.split('.').length, 4);
  assert.equal((await me(s, legacy.token)).status, 401, 'Old-style tokens stop once a phone is trusted');
  assert.equal((await login(s, 'ali')).status, 426, 'Old apps must update to sign in');
  assert.equal((await login(s, 'ali', phone())).status, 202, 'Other phones now need approval');
  const second = await s.call('POST', '/api/me/device', { token: (await login(s, 'ali', mine)).token, body: { device: phone().proof('bootstrap:' + legacy.id) } });
  assert.equal(second.status, 200, 'Already trusted phone just keeps its session');
});

test('trusted phones can see and remove other phones; admin reset needs the server key', async () => {
  const s = server(); const a = phone('A'), b = phone('B');
  const created = await register(s, 'ali', a);
  const pending = await login(s, 'ali', b);
  await s.call('POST', '/api/devices/requests/:id', { token: created.token, params: { id: pending.requestId }, body: { approve: true } });
  const bToken = (await s.call('POST', '/api/login/request/:id', { params: { id: pending.requestId }, body: { device: b.proof('poll:' + pending.requestId) } })).token;
  assert.equal((await s.call('DELETE', '/api/devices/:id', { token: created.token, params: { id: a.id } })).status, 400, 'Cannot remove the current phone');
  assert.equal((await s.call('DELETE', '/api/devices/:id', { token: created.token, params: { id: b.id } })).status, 200);
  assert.equal((await me(s, bToken)).status, 401);

  assert.equal((await s.call('POST', '/api/admin/reset-devices', { body: { username: 'ali' } })).status, 404);
  const key = 'k'.repeat(40); process.env.ADMIN_KEY = key;
  try {
    assert.equal((await s.call('POST', '/api/admin/reset-devices', { body: { username: 'ali' }, headers: { 'x-admin-key': 'x'.repeat(40) } })).status, 404);
    assert.equal((await s.call('POST', '/api/admin/reset-devices', { body: { username: 'ali' }, headers: { 'x-admin-key': key } })).status, 200);
  } finally { delete process.env.ADMIN_KEY; }
  const restarted = await login(s, 'ali', phone());
  assert.equal(restarted.status, 200); assert.ok(restarted.recoveryCode, 'After a reset the next phone becomes trusted with a new code');
});

test('device records and username changes are written to the database', async () => {
  const s = server(true); const created = await register(s, 'ali', phone());
  assert.ok(s.queries.some(([sql, a]) => sql.startsWith('INSERT INTO ru_devices') && a[0] === created.id));
  assert.ok(s.queries.some(([sql]) => sql.startsWith('UPDATE ru_users SET recovery_hash')));
  await s.call('POST', '/api/me/username', { token: created.token, body: { username: 'ali_new' } });
  assert.ok(s.queries.some(([sql, a]) => sql.startsWith('INSERT INTO ru_reserved') && a[0] === 'ali'));
});

test('one account per phone: the phone that has an account cannot make another; signing in stays open', async () => {
  const s = server(true); const p = 'a'.repeat(64), q = 'b'.repeat(64), r = 'c'.repeat(64);
  const signUp = (username, phone) => s.call('POST', '/api/register', { body: { username, pin: '123456', name: username, ...(phone ? { phone } : {}) } });
  assert.equal((await signUp('first', p)).status, 200);
  const again = await signUp('second', p);
  assert.equal(again.status, 409); assert.equal(again.username, 'first'); assert.match(again.error, /@first/);
  assert.equal((await signUp('second', q)).status, 200, 'Another phone may make an account');
  assert.ok(s.queries.some(([sql, args]) => /INSERT INTO ru_phones/.test(sql) && args[0] === p), 'The phone is stored with the account');

  // An account made before the rule (an older app sent no phone) learns its phone once the updated app connects.
  const older = await signUp('older');
  assert.equal(older.status, 200, 'Older apps can still make accounts');
  assert.equal((await s.call('POST', '/api/me/phone', { token: older.token, body: { phone: r } })).status, 200);
  assert.equal((await signUp('newer', r)).status, 409, 'That phone now counts as having an account');

  // Signing in to any existing account on the phone still works, and is remembered.
  assert.equal((await login(s, 'second', null, { phone: p })).status, 200);
  assert.equal((await signUp('third', p)).username, 'second', 'The account used most recently is named');
  assert.equal((await s.call('POST', '/api/me/phone', { token: older.token, body: { phone: 'not-a-phone' } })).status, 400);
  assert.equal((await s.call('POST', '/api/me/phone', { body: { phone: r } })).status, 401);
});

// ---------- deleting an account (Google Play requires it in the app and on the web) ----------
const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10]).toString('base64');
test('deleting the account in the app: the PIN again, then the account and what it left are gone', async () => {
  const s = server(); const p = phone();
  const a = await register(s, 'gone', p), b = await register(s, 'stays');
  await s.call('POST', '/api/me/photo', { token: a.token, body: { photo: JPEG } });
  await s.call('POST', '/api/status', { token: a.token, body: { kind: 'text', body: 'hello', audience: [b.id] } });
  await s.call('POST', '/api/block', { token: b.token, body: { target: a.id } });
  const sa = s.socket(a.token);
  assert.equal((await s.call('POST', '/api/me/delete', { token: a.token, body: { pin: '000000' } })).status, 401);
  assert.equal(sa.ws.closed, false, 'A wrong PIN deletes nothing');
  assert.equal((await s.call('POST', '/api/me/delete', { token: a.token, body: { pin: '123456' } })).ok, true);
  assert.equal(sa.ws.closed, true, 'Its phone is disconnected');
  assert.equal((await s.call('GET', '/api/me', { token: a.token })).status, 401, 'Its sign-in stops working');
  assert.equal((await s.call('POST', '/api/login', { body: { username: 'gone', pin: '123456', device: p.proof('login:gone') } })).status, 401);
  assert.equal((await s.call('GET', '/api/status', { token: b.token })).statuses.length, 0, 'Its updates are gone');
  assert.equal((await s.call('GET', '/api/user/photo/:id', { token: b.token, params: { id: a.id } })).status, 404, 'Its photo is gone');
  assert.deepEqual((await s.call('GET', '/api/privacy', { token: b.token })).blocked, [], 'Blocks it was in are gone');
  assert.equal((await register(s, 'gone')).username, 'gone', 'Its username is free again');
  assert.equal((await s.call('POST', '/api/me/delete', { body: { pin: '123456' } })).status, 401, 'Deleting needs a signed-in phone');
});
test('deleting on the web: the PIN, and the recovery code once the account has a trusted phone', async () => {
  const s = server(); const a = await register(s, 'webgone', phone());
  const web = body => s.call('POST', '/api/delete-account', { body });
  assert.equal((await web({ username: 'webgone', pin: '999999' })).status, 401);
  const need = await web({ username: 'webgone', pin: '123456' });
  assert.equal(need.status, 403); assert.equal(need.recoveryCode, true);
  assert.equal((await web({ username: 'webgone', pin: '123456', recoveryCode: 'AAAA-BBBB-CCCC' })).status, 403);
  assert.equal((await web({ username: ' WebGone', pin: '123456', recoveryCode: a.recoveryCode.toLowerCase() })).ok, true);
  assert.equal((await s.call('GET', '/api/user/:username', { params: { username: 'webgone' } })).status, 404);
  await register(s, 'oldphone');
  assert.equal((await web({ username: 'oldphone', pin: '123456' })).ok, true, 'An account with no trusted phone needs only the PIN, as signing in does');
});
test('deleting is limited like signing in: after 10 wrong PINs it waits', async () => {
  const s = server(); const a = await register(s, 'tries');
  for (let i = 0; i < 10; i++) assert.equal((await s.call('POST', '/api/me/delete', { token: a.token, body: { pin: '000000' } })).status, 401);
  assert.equal((await s.call('POST', '/api/me/delete', { token: a.token, body: { pin: '123456' } })).status, 429);
  assert.equal((await s.call('POST', '/api/delete-account', { body: { username: 'tries', pin: '123456' } })).status, 429);
});
test('deletion clears the account from storage, its sign-in last', async () => {
  const s = server(true); const a = await register(s, 'stored', phone());
  assert.equal((await s.call('POST', '/api/me/delete', { token: a.token, body: { pin: '123456' } })).ok, true);
  const sql = s.queries.map(([q]) => q), at = re => sql.findIndex(q => re.test(q));
  for (const re of [/DELETE FROM ru_messages WHERE from_id=\$1 OR to_id=\$1/, /DELETE FROM ru_receipts/, /DELETE FROM rf_data WHERE kind IN \('grant','got'\)/, /kind='photo'/,
    /DELETE FROM rf_data WHERE kind='push'/, /DELETE FROM ru_devices WHERE user_id=\$1$/, /DELETE FROM ru_phones/, /DELETE FROM ru_reserved/, /DELETE FROM ru_users/]) assert.ok(at(re) >= 0, String(re));
  assert.ok(at(/DELETE FROM ru_users/) > at(/DELETE FROM ru_devices WHERE user_id=\$1$/), 'Sign-in goes last, so a failure on the way leaves a working account');
  assert.ok(s.queries.filter(([q]) => /DELETE FROM ru_users/.test(q)).every(([, args]) => args[0] === a.id));
});
test('the privacy policy and the delete-account page are public web pages', async () => {
  const s = server();
  const privacy = await s.page('/privacy'), del = await s.page('/delete-account');
  assert.equal(privacy.type, 'html'); assert.equal(del.type, 'html');
  assert.match(privacy.html, /not end-to-end encrypted/); assert.match(privacy.html, /Settings → Delete account/); assert.match(privacy.html, /href="\/delete-account"/);
  assert.match(del.html, /\/api\/delete-account/); assert.match(del.html, /Recovery code/);
});

