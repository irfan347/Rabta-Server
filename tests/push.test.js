const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const install = require('../push');

// A made-up Firebase project with its own key; requests go to a recorder, never to Google.
function setup({ answer = () => ({ status: 200, body: '{"name":"projects/rabta-test/messages/1"}' }), account } = {}) {
  const { privateKey, publicKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const key = privateKey.export({ type: 'pkcs8', format: 'pem' });
  const routes = new Map(), calls = []; let now = 1_700_000_000_000;
  const app = { post(p, f) { routes.set('POST ' + p, f); }, delete(p, f) { routes.set('DELETE ' + p, f); } };
  const request = async (url, headers, body) => {
    calls.push({ url, headers, body });
    if (url === 'https://oauth2.googleapis.com/token') return { status: 200, body: JSON.stringify({ access_token: 'access-1', expires_in: 3600 }) };
    return answer(body);
  };
  const push = install({ app, authId: req => req.user || null, getPool: () => null, request, clock: () => now,
    account: account === undefined ? JSON.stringify({ project_id: 'rabta-test', client_email: 'push@rabta-test.iam.gserviceaccount.com', private_key: key }) : account });
  const call = async (route, user, body) => {
    let out, code = 200;
    await routes.get(route)({ user, body }, { json(v) { out = v; }, status(c) { code = c; return this; } });
    return { code, out };
  };
  const settle = () => new Promise(resolve => setTimeout(resolve, 20));
  const sends = () => calls.filter(c => c.url.endsWith('/messages:send'));
  return { push, calls, sends, publicKey, call, settle, tick: ms => { now += ms; } };
}
const TOKEN = 'cXJhYnRhLXRlc3QtcGhvbmU:APA91b' + 'Z'.repeat(120);

test('a waiting message wakes the phone through Firebase without its text', async () => {
  const f = setup();
  assert.deepEqual((await f.call('POST /api/me/push', 'ALICE0000001', { token: TOKEN })).out, { ok: true, push: true });
  f.push.wake('ALICE0000001', 'message'); await f.settle();

  // Google's server sign-in: a JWT signed with the service account's key.
  const signIn = new URLSearchParams(f.calls[0].body);
  assert.equal(signIn.get('grant_type'), 'urn:ietf:params:oauth:grant-type:jwt-bearer');
  const [head, claims, signature] = signIn.get('assertion').split('.');
  assert.ok(crypto.verify('RSA-SHA256', Buffer.from(head + '.' + claims), f.publicKey, Buffer.from(signature, 'base64url')));
  const c = JSON.parse(Buffer.from(claims, 'base64url'));
  assert.equal(c.iss, 'push@rabta-test.iam.gserviceaccount.com');
  assert.equal(c.scope, 'https://www.googleapis.com/auth/firebase.messaging');
  assert.equal(c.exp - c.iat, 3600);

  const [send] = f.sends();
  assert.equal(send.url, 'https://fcm.googleapis.com/v1/projects/rabta-test/messages:send');
  assert.equal(send.headers.authorization, 'Bearer access-1');
  assert.deepEqual(JSON.parse(send.body), { message: { token: TOKEN, data: { type: 'wake', kind: 'message' }, android: { priority: 'HIGH', ttl: '86400s' } } });
});

test('message wakes are spaced out, calls always wake, and the sign-in is reused', async () => {
  const f = setup();
  await f.call('POST /api/me/push', 'ALICE0000001', { token: TOKEN });
  f.push.wake('ALICE0000001'); f.push.wake('ALICE0000001'); await f.settle();
  assert.equal(f.sends().length, 1, 'One wake fetches everything waiting');
  f.tick(31_000); f.push.wake('ALICE0000001'); await f.settle();
  assert.equal(f.sends().length, 2);
  f.push.wake('ALICE0000001', 'call'); f.push.wake('ALICE0000001', 'call'); await f.settle();
  const calls = f.sends().slice(2).map(s => JSON.parse(s.body).message);
  assert.deepEqual(calls.map(m => [m.data.kind, m.android.ttl]), [['call', '45s'], ['call', '45s']]);
  assert.equal(f.calls.filter(c => c.url.includes('oauth2')).length, 1, 'One sign-in per hour');
});

test('a token Firebase no longer knows is dropped', async () => {
  const f = setup({ answer: () => ({ status: 404, body: '{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}' }) });
  await f.call('POST /api/me/push', 'ALICE0000001', { token: TOKEN });
  f.push.wake('ALICE0000001', 'call'); await f.settle();
  f.push.wake('ALICE0000001', 'call'); await f.settle();
  assert.equal(f.sends().length, 1);
});

test('only the signed-in owner registers or removes a token', async () => {
  const f = setup();
  assert.equal((await f.call('POST /api/me/push', null, { token: TOKEN })).code, 401);
  assert.equal((await f.call('POST /api/me/push', 'ALICE0000001', { token: 'short' })).code, 400);
  await f.call('POST /api/me/push', 'ALICE0000001', { token: TOKEN });
  await f.call('DELETE /api/me/push', 'EVE000000001', { token: TOKEN });
  f.push.wake('ALICE0000001', 'call'); await f.settle();
  assert.equal(f.sends().length, 1, "Someone else cannot remove Alice's phone");
  await f.call('DELETE /api/me/push', 'ALICE0000001', { token: TOKEN });
  f.push.wake('ALICE0000001', 'call'); await f.settle();
  assert.equal(f.sends().length, 1, 'Signed out: no more wakes');
});

test('a phone signing in to another account takes its token along', async () => {
  const f = setup();
  await f.call('POST /api/me/push', 'ALICE0000001', { token: TOKEN });
  await f.call('POST /api/me/push', 'BOB000000002', { token: TOKEN });
  f.push.wake('ALICE0000001', 'call'); await f.settle();
  assert.equal(f.sends().length, 0);
  f.push.wake('BOB000000002', 'call'); await f.settle();
  assert.equal(f.sends().length, 1);
});

test('without a Firebase service account nothing is sent', async () => {
  const f = setup({ account: '' });
  assert.deepEqual((await f.call('POST /api/me/push', 'ALICE0000001', { token: TOKEN })).out, { ok: true, push: false });
  f.push.wake('ALICE0000001', 'call'); await f.settle();
  assert.equal(f.calls.length, 0);
});
