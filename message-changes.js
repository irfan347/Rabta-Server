const crypto = require('crypto');

// The sender and original recipients are recorded before delivery. Changes have their own
// acknowledged inbox so an offline recipient can apply them even after the original was delivered.
module.exports = function ({app, authId, users, getPool, blocked, notify = () => {}, now = Date.now}) {
  const records = new Map(), locks = new Map();
  const EDIT = 15 * 60 * 1000, DELETE = 48 * 60 * 60 * 1000, KEEP = 30 * 86400000;
  const validId = value => /^[0-9A-F]{12}$/.test(value);
  const requireThat = (value, text, code = 400) => { if (!value) throw Object.assign(new Error(text), {code}); };
  const keyOf = (from, id) => from + ':' + id;
  async function serial(key, work) {
    const previous = locks.get(key) || Promise.resolve();
    const next = previous.catch(() => {}).then(work);
    locks.set(key, next);
    try { return await next; } finally { if (locks.get(key) === next) locks.delete(key); }
  }
  async function save(key, value) {
    const db = getPool();
    if (db) await db.query("INSERT INTO rf_data(kind,id,data) VALUES('message_change',$1,$2) ON CONFLICT(kind,id) DO UPDATE SET data=$2", [key, JSON.stringify(value)]);
    records.set(key, value);
  }
  async function init() {
    const db = getPool();
    if (db) for (const row of (await db.query("SELECT id,data FROM rf_data WHERE kind='message_change'")).rows) {
      try { const value = JSON.parse(row.data); records.set(row.id, value); } catch (_) {}
    }
    await tidy();
  }
  async function record({id, from, to, type, recipients, group = false}) {
    if (type === 'reaction') return;
    const key = keyOf(from, id);
    return serial(key, async () => {
      const old = records.get(key);
      if (old) {
        requireThat(old.to === to && old.type === type && old.group === group, 'Message ID already used');
        return;
      }
      requireThat(records.size < 100000, 'Message storage busy; retry later', 503);
      const time = now();
      await save(key, {id, from, to, type, group, recipients: [...new Set(recipients)], created: time, expires: time + DELETE, revision: 0, pending: []});
    });
  }
  function event(r, me) {
    return {id: r.id, from: r.from, convId: r.group ? r.to : (me === r.from ? r.to : r.from), revision: r.revision,
      action: r.action, text: r.text || '', changedAt: r.changedAt};
  }
  const route = fn => async (req, res) => {
    const me = authId(req);
    if (!me) return res.status(401).json({error: 'Login required'});
    try { await fn(req, res, me); }
    catch (e) { res.status(e.code || 503).json({error: e.code ? e.message : 'Could not save message change; try again'}); }
  };
  app.post('/api/messages/change', route(async (req, res, me) => {
    const {id, action, text, operationId} = req.body || {};
    requireThat(validId(id) && ['edit', 'delete'].includes(action) && /^[a-f0-9-]{36}$/.test(operationId || ''), 'Invalid message change');
    if (action === 'edit') requireThat(typeof text === 'string' && text.trim() && Buffer.byteLength(text) <= 12000, 'Enter up to 12000 bytes of text');
    const key = keyOf(me, id);
    await serial(key, async () => {
      const r = records.get(key);
      requireThat(r && users.has(me) && r.expires > now(), 'This message is not eligible for editing or deletion for everyone', 404);
      const hash = crypto.createHash('sha256').update(JSON.stringify([action, action === 'edit' ? text : ''])).digest('hex');
      if (r.operationId === operationId) {
        requireThat(r.operationHash === hash, 'This operation ID was already used', 409);
        return res.json({change: event(r, me)});
      }
      requireThat(r.action !== 'delete', 'This message was already deleted', 409);
      requireThat(now() - r.created <= (action === 'edit' ? EDIT : DELETE), action === 'edit' ? 'Messages can be edited for 15 minutes' : 'Delete for everyone is available for 48 hours');
      if (action === 'edit') requireThat(['text', 'rich'].includes(r.type), 'Only text messages can be edited');
      // A new edit must not revive content in a chat whose recipient blocked this sender.
      if (action === 'edit') requireThat(r.recipients.every(to => !blocked(me, to)), 'Cannot edit a blocked conversation', 403);
      const next = {...r, revision: r.revision + 1, action, text: action === 'edit' ? text : '', changedAt: now(),
        operationId, operationHash: hash, pending: [...new Set([me, ...r.recipients])].filter(id => users.has(id)), expires: now() + KEEP};
      await save(key, next);
      for (const to of next.pending) { try { notify(to); } catch (_) {} }
      res.json({change: event(next, me)});
    });
  }));
  app.get('/api/messages/changes', route(async (req, res, me) => {
    const batch = [...records.values()].filter(r => r.expires > now() && r.pending.includes(me)).slice(0, 100);
    res.json({changes: batch.map(r => event(r, me))});
  }));
  app.post('/api/messages/changes/ack', route(async (req, res, me) => {
    const items = req.body?.items;
    requireThat(Array.isArray(items) && items.length <= 100 && items.every(x => x && validId(x.id) && validId(x.from) && Number.isSafeInteger(x.revision) && x.revision > 0), 'Invalid acknowledgment');
    for (const item of items) {
      const key = keyOf(item.from, item.id);
      await serial(key, async () => {
        const r = records.get(key);
        if (!r || r.revision !== item.revision || !r.pending.includes(me)) return;
        const next = {...r, pending: r.pending.filter(id => id !== me)};
        // Once everybody stored it, keep only its fingerprint for retries, not the edited text.
        if (!next.pending.length) { next.text = ''; next.expires = Math.max(now() + 60000, r.created + DELETE); }
        await save(key, next);
      });
    }
    res.json({ok: true});
  }));
  async function tidy() {
    for (const [key, r] of records) if (r.expires <= now()) await serial(key, async () => {
      if (records.get(key)?.expires > now()) return;
      const db = getPool(); if (db) await db.query("DELETE FROM rf_data WHERE kind='message_change' AND id=$1", [key]);
      records.delete(key);
    });
  }
  async function forget(me) {
    for (const [key, r] of records) if (r.from === me || r.recipients.includes(me)) await serial(key, async () => {
      const current = records.get(key); if (!current) return;
      if (current.from === me) {
        const db = getPool(); if (db) await db.query("DELETE FROM rf_data WHERE kind='message_change' AND id=$1", [key]);
        records.delete(key);
      } else await save(key, {...current, recipients: current.recipients.filter(id => id !== me), pending: current.pending.filter(id => id !== me)});
    });
  }
  return {init, record, tidy, forget};
};
