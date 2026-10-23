# Rabta stack and reliability

Verified from repository source, live health and read-only Render configuration on 22 October 2026 UTC. The database URL was inspected only to identify the provider; credentials were not printed or saved.

| Layer | Current implementation | Role |
|---|---|---|
| Android app | Kotlin, native Android Views, Gradle/AGP | Chat, calls, device integration |
| Phone database | SQLite through SQLiteOpenHelper, separate database per account | Local history, contacts, drafts/metadata and outgoing message state (some preferences live in SharedPreferences) |
| Server | Node.js, Express, ws | HTTP APIs, authentication, delivery, call signaling and voice relay |
| Server database | Neon PostgreSQL via a pooled endpoint and node-postgres | Accounts, pending messages/receipts, groups and feature records |
| Live hosting | Render | Live health reports database enabled; no secrets were read |
| Video | WebRTC | Peer media with STUN/TURN support |
| Voice | PCM frames over authenticated WebSocket | Existing server-relayed voice transport |
| Nearby | Google Nearby Connections | Existing Bluetooth/Wi-Fi device transport |
| Notifications | Firebase Cloud Messaging | Wake-up notifications |

## Changes in this PR

- Profile photos use access-ordered eviction bounded by both 128 entries and 8 MiB of bitmap allocation. Null entries count toward the entry limit. Eviction never recycles a bitmap still referenced by a view. Account/server cache keys and conditional revalidation remain. Concurrent requests for one photo are coalesced. Clearing the cache also invalidates older in-flight callbacks.
- SQLite WAL is enabled so history readers can run concurrently with background writes. This changes journal/concurrency behavior, not schema or accounts. Existing backup export reads rows in a transaction; it does not copy only the raw SQLite database file.
- PostgreSQL indexes cover pending deliveries by recipient and ID, and receipts by recipient and timestamp. These match existing query filters/order. No benchmark or throughput gain is claimed without workload measurements.
- Call-init retries are idempotent for the same caller, recipient, ID and media type. When A calls B while B calls A, the first server-accepted request remains the only session. The later attempt receives a crossed-call reason and the existing incoming call is delivered again. Explicit user acceptance is still required. Voice/video combinations are regression-tested.

## Primary storage, cache, backup and standby are different

SQLite is the phone's durable local store. PostgreSQL is the server's durable store. Cached data can be evicted and fetched again; caches are not backups or substitutes for acknowledged durable writes.

The app already supports user-initiated, password-encrypted backup/restore (AES-GCM, password derivation with PBKDF2). Restore verifies authentication before applying data and uses a database transaction. Existing format/size limits remain; this PR does not add scheduled/cloud backups or alter the backup format.

A PostgreSQL standby/replica, provider backup retention, point-in-time recovery and a tested restore procedure have NOT been provisioned or verified by this PR. Those need an inventory of the actual database provider/plan, retention targets and restore drills before they can be described as operational. A replica alone does not protect against accidental deletion replicated to it.

Do not scale to multiple active app-server instances yet: call state and online socket routing are process-local. Multi-instance operation requires shared coordination/routing, plus reconnect behavior across deploys. This PR does not introduce Redis, a standby server, paid infrastructure or automatic database failover. Server restarts still lose in-memory active calls.

## Validation limits

Automated tests cover cache eviction and server call collisions/recovery behavior. Device migration, backup/restore under WAL, visual behavior and real two-phone network-switch recovery still need device acceptance. Two-minute recovery and precise busy/crossed-call reasons require the new server and clients to be released together. Production has not been changed by this feature branch.

## Early-stage budget constraint

The owner reports $200 of Render credit and no additional provider budget. Keep the existing single Render service and database arrangement. This PR adds no paid services or subscription dependencies. Do not provision paid replicas, Redis, larger instances or new storage without explicit budget approval. Render credits must not be assumed to fund a separate Neon account.

Verify actual Render credit balance/expiry, current service billing, database host/plan, storage and egress before projecting runway. The reported $200 is not a measured current balance. Avoid synthetic keep-alive traffic to a serverless database; current health checks do not query PostgreSQL. Existing cleanup runs every 30 minutes, not continuously. Code optimizations do not guarantee a monthly bill or an unlimited free tier.

The connected Render service DATABASE_URL confirms a Neon pooled endpoint. Neon billing tier/allowance, Render credit balance/expiry and monthly usage remain unverified; the existing connection is not proof of a free plan.
