# Rabta Web implementation brief

Requested: use owned rabta.tech domain for a browser companion linked from Android.
Proposed app address: web.rabta.tech. No DNS or hosting changes performed.

## Verified current constraints
- Node/Express/ws on Render; Neon PostgreSQL; Android Kotlin with local SQLite.
- index.js holds one WebSocket per account and closes the previous connection. A browser cannot safely reuse this path unchanged.
- Trusted phone approval and device revocation already exist in account.js. QR browser pairing is not implemented.
- Delivered messages are not a complete server chat archive. Existing phone history needs explicit sync.
- Current DNS lookup returned no A/CNAME answer for the apex or web subdomain; registrar ownership and authoritative configuration not verified.

## Required implementation
1. Short-lived, single-use browser pairing challenge; QR contains pairing reference, never a phone session token. Phone shows browser details and explicitly approves linking.
2. Separate browser device credentials, expiry and phone-controlled revocation. Protect browser session endpoints against cross-site requests; enforce origin checks and secure transport.
3. Account/device-aware socket routing; browser login cannot evict the phone. Message delivery and acknowledgments track device sync independently from recipient read/delivery receipts.
4. Browser conversation UI, text messaging, search, reconnect state and logout. Sync the user's outgoing messages across their own devices too.
5. Explicit bounded history sync; respect locked chats and privacy settings, avoid copying hidden chat content without approval. No bulk upload of all phone history by default.
6. Define attachments, groups, calls, reactions and offline behavior as separately testable acceptance items. Do not present visual placeholders as working features.
7. Serve the first version using current infrastructure where feasible; no new paid service, replica or plan increase. Validate current hosting terms before promising zero extra hosting costs.
8. Configure domain and TLS only once a working, tested deployment target exists. Preserve existing DNS records, mail configuration and Android API endpoint.

## Confirmed first-release behavior
User selected phone-connected operation. Android remains authoritative for history and sending. Browser commands relay to the approved phone through a separate companion channel; the existing phone socket must not be replaced. Browser shows Phone disconnected and disables sending when the phone cannot respond. Do not report a message sent until the phone has durably queued it. Use request IDs and phone-side deduplication to prevent retry duplicates. No offline browser outbox in version one.

Use a separate browser companion protocol rather than introducing independent device delivery into the existing message protocol for this release. Phone approval, revocation and bounded history snapshots remain required. Sessions should expire and must not grant account recovery or phone-management privileges.

## Required acceptance
Pair expiry/replay, approval identity, revoke active browser, cross-account isolation, phone+browser simultaneous use, offline catch-up, duplicate suppression, correct receipts, outgoing sync, locked-chat protection, logout/local cleanup, reconnect and existing Android call/message regression checks.

This is a design brief, not a completed web client or a deployment.

## Implementation checkpoint

A standalone CompanionLinks state component now exists in web-companion.js, covered by five Node tests. It creates a short-lived single-use pairing code separate from a browser secret, binds approval to one authenticated account and phone, expires links, supports revocation, and limits memory. Secrets are stored as hashes. HTTP routes, browser UI, QR presentation, Android approval, command relay, history privacy filtering and durable send deduplication are NOT implemented yet. The component is intentionally not imported by index.js; no new production endpoints are exposed. Deployment/restart requires re-pairing for this initial in-memory design. HTTP integration must add request throttling, origin checks, secure cookies and explicit phone confirmation before exposing it.

Production release is separate: user authorized PR #29 merge and Render/public Android release. Merge commit 2576249; Render confirmed live. Public release workflow verification still ongoing at this checkpoint. Rabta Web remains a development branch, not part of that deployment.
