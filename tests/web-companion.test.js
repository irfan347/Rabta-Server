const test = require('node:test');
const assert = require('node:assert/strict');
const { CompanionLinks } = require('../web-companion');

test('pair approval is single-use and bound to the approving account and phone', () => {
  const links = new CompanionLinks(); const pair = links.create('My browser');
  assert.equal(links.inspect(pair.secret, () => true).owner, null);
  assert.equal(links.approve(pair.code, 'alice', ''), null);
  assert.equal(links.approve(pair.code, 'alice', 'phone-a').owner, 'alice');
  assert.equal(links.approve(pair.code, 'bob', 'phone-b'), null);
  assert.equal(links.inspect(pair.code, () => true), null);
  assert.equal(links.inspect(pair.secret, () => true).device, 'phone-a');
  assert.equal(links.revoke('bob', 'phone-a', pair.id), false);
  assert.equal(links.revoke('alice', 'phone-b', pair.id), false);
  assert.equal(links.revoke('alice', 'phone-a', pair.id), true);
  assert.equal(links.inspect(pair.secret, () => true), null);
});

test('expired pairing and linked sessions cannot be reused', () => {
  let time = 0; const links = new CompanionLinks({ now: () => time, pairMs: 10, linkMs: 20 });
  const expired = links.create('Browser'); time = 10;
  assert.equal(links.approve(expired.code, 'alice', 'phone'), null);
  const pair = links.create('Browser'); links.approve(pair.code, 'alice', 'phone');
  time = 29; assert.ok(links.inspect(pair.secret, () => true));
  time = 30; assert.equal(links.inspect(pair.secret, () => true), null);
});

test('phone trust revocation invalidates browser access and logout removes its secret', () => {
  const links = new CompanionLinks(); const pair = links.create('Browser');
  links.approve(pair.code, 'alice', 'phone');
  assert.equal(links.inspect(pair.secret, (owner, device) => { assert.equal(owner, 'alice'); assert.equal(device, 'phone'); return false; }), null);
  assert.equal(links.inspect(pair.secret, () => true), null);
  const other = links.create('Browser'); assert.equal(links.logout(other.secret), true);
  assert.equal(links.inspect(other.secret, () => true), null);
});

test('capacity is bounded and expired requests free capacity without evicting live links', () => {
  let time = 0; const links = new CompanionLinks({ now: () => time, limit: 1, pairMs: 10 });
  const pair = links.create('Browser');
  assert.throws(() => links.create('Another'), /capacity/);
  assert.ok(links.inspect(pair.secret, () => true));
  time = 10; assert.ok(links.create('Another'));
});

test('list is phone-scoped and returned metadata cannot mutate stored authorization', () => {
  const links = new CompanionLinks(); const pair = links.create('Browser');
  const approved = links.approve(pair.code, 'alice', 'phone'); approved.owner = 'bob';
  assert.equal(links.list('bob', 'phone').length, 0);
  assert.equal(links.list('alice', 'other').length, 0);
  assert.equal(links.list('alice', 'phone')[0].owner, 'alice');
  assert.equal(JSON.stringify([...links.links.values()]).includes(pair.secret), false);
  assert.equal(JSON.stringify([...links.links.values()]).includes(pair.code), false);
});
