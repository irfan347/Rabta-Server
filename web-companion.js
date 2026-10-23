'use strict';
const crypto = require('node:crypto');

// In-memory pairing foundation for a phone-connected companion. Not mounted in production.
// Restarting the server deliberately revokes links; no phone auth token is shared with a browser.
class CompanionLinks {
  constructor({ now = Date.now, limit = 1000, pairMs = 120000, linkMs = 8 * 3600000 } = {}) {
    this.now = now; this.limit = limit; this.pairMs = pairMs; this.linkMs = linkMs;
    this.links = new Map();
  }
  hash(value) { return crypto.createHash('sha256').update(value).digest('hex'); }
  prune() {
    for (const [key, link] of this.links) if (link.expires <= this.now()) this.links.delete(key);
  }
  create(browserName) {
    this.prune();
    if (this.links.size >= this.limit) throw new Error('Pairing capacity reached');
    const secret = crypto.randomBytes(32).toString('hex');
    const code = crypto.randomBytes(16).toString('hex');
    const link = { id: crypto.randomBytes(16).toString('hex'), codeHash: this.hash(code),
      name: String(browserName || 'Browser').replace(/[\x00-\x1f\x7f]/g, '').slice(0, 80),
      expires: this.now() + this.pairMs, owner: null, device: null, linkedAt: null };
    this.links.set(this.hash(secret), link);
    return { secret, code, id: link.id, expires: link.expires };
  }
  // Caller must authenticate the phone AND show explicit confirmation before approval.
  approve(code, owner, device) {
    this.prune();
    if (!owner || !device || !/^[a-f0-9]{32}$/.test(String(code))) return null;
    const hash = this.hash(code);
    const link = [...this.links.values()].find(item => item.codeHash === hash && !item.owner);
    if (!link) return null;
    link.codeHash = null; link.owner = owner; link.device = device;
    link.linkedAt = this.now(); link.expires = this.now() + this.linkMs;
    return this.publicLink(link);
  }
  inspect(secret, deviceAllowed) {
    this.prune();
    if (!/^[a-f0-9]{64}$/.test(String(secret))) return null;
    const key = this.hash(secret), link = this.links.get(key);
    if (link?.owner && !deviceAllowed(link.owner, link.device)) { this.links.delete(key); return null; }
    return link ? this.publicLink(link) : null;
  }
  publicLink(link) {
    return { id: link.id, name: link.name, owner: link.owner, device: link.device,
      linkedAt: link.linkedAt, expires: link.expires };
  }
  list(owner, device) {
    this.prune();
    return [...this.links.values()].filter(link => link.owner === owner && link.device === device)
      .map(link => this.publicLink(link));
  }
  revoke(owner, device, id) {
    for (const [key, link] of this.links) if (link.owner === owner && link.device === device && link.id === id) {
      this.links.delete(key); return true;
    }
    return false;
  }
  logout(secret) { return typeof secret === 'string' && this.links.delete(this.hash(secret)); }
}
module.exports = { CompanionLinks };
