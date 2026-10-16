# Offline mesh audit and validation

Audited 2026-10-16 on `codex/rabta-2-design-system`. Checkpoint: `checkpoint/pre-mesh-audit` (source tree `0eb6f92c181a31a0d5da0af512e43955808ba1f7`). Supplements the original findings in `RABTA_ROADMAP.md`.

## Findings and focused changes

| Area | Existing behavior and audit result |
| --- | --- |
| Transport | Google Nearby Connections 19.3, `P2P_CLUSTER`, service `com.irfan.rabta.v1`. Nearby manages Bluetooth/Wi-Fi. Its API does not expose the actual radio for each link. No separate active raw Wi-Fi Direct transport. |
| Discovery | Advertisements carry name and Rabta ID; smaller ID initiates. Continuous discovery while the service is active. Valid advertised IDs auto-accept, followed by signed key exchange. Advertised names/IDs are not verified identities. Found endpoint tracking is now capped at 200. |
| Permissions | Existing fine/coarse location, Bluetooth scan/advertise/connect on API 31+, Nearby Wi-Fi on API 33+, Play services, Bluetooth and Location checks retained. Wi-Fi off is reported without adding a blocker to possible Bluetooth delivery. Revoked permissions pause the existing transport. |
| Lifecycle | Service retries every 15 seconds. Old-generation discovery, connection and payload callbacks now cannot mutate a restarted transport. Monotonic per-peer retry tracking prevents overlap within a 30-second unresolved-request window, with capped 15/30/60/120-second failure backoff. Radio/SDK recovery may take longer. |
| Sending/receiving | Existing offline direct text/bundled stickers preserved. Inbox deduplicates by ID and acknowledges only after durable persistence. Transport send completion never means recipient delivery. Synchronous/asynchronous send failures now retain encrypted packets and report retry status. |
| Queues | Existing account SQLite `mesh_v2_<id>.db`, schema v1; 100 pending messages, 500 carry packets, 8 MiB packet quota, 10,000 replay entries, 24-hour lifetime. At capacity, existing-ID retries now succeed without replacing payloads or extending original lifetime. Pending-to-encrypted promotion is transactional; capacity failure retains pending data. |
| Recovery/battery | Existing 30-second carry cycle retained. Replay is bounded to 32 packets per invocation, including reconnects, with a rotating cursor. No carry DB scan without peers. Repeated pause calls avoid redundant transport shutdown. Executor shutdown no longer races callback submissions. Discovery remains continuous; no measured battery improvement or Doze guarantee is claimed. |
| Encryption | Existing AES-256-GCM, RSA-2048 OAEP SHA-256/MGF1 SHA-1 and SHA256withRSA unchanged. Private keys remain in Android Keystore; hardware backing depends on device. Contact public keys stay pinned in account preferences, with changed keys rejected. First contact is trust on first use; trusted fingerprint comparison remains required. |
| Local storage | Pending messages awaiting keys and ordinary chat history remain plaintext in app-private SQLite. No claim of new encryption at rest. Diagnostics show no bodies, credentials or key material. No storage migration. |
| Relay | Existing store/carry/forward with signed encrypted envelopes and reverse recipient receipts, eight-hop budget and expiry. Content/identities/lifetime are signed; the decrementing hop field is intentionally unsigned. A hostile relay can alter it, so it proves neither route nor physical hop count. Expiry/replay limits do not solve every flooding attack. |

`LongRange.kt` remains inactive (`longRangeOn=false`). Legacy file/stream/audio methods remain but Router does not implement their offline delivery. Offline groups, media, calls and status are unsupported. Existing online messaging, accounts, IDs, schemas, backend and wire protocol are preserved. Repeated valid recipient receipts no longer repeatedly acknowledge a saved outgoing message.

## Connectivity diagnostics

Open **Nearby → Connectivity diagnostics** or the connection-help sheet. Reuses the native Rabta theme, showing:

- Actual Bluetooth/Wi-Fi switch states, missing permissions and Nearby prerequisites.
- WebSocket connection state separately from Android's current network/internet validation.
- Discovery/connecting counts, actual peers and whether each signed key matches its pinned identity.
- Current account's persisted pending, outgoing, relay and receipt counts/bytes and queued message IDs/states; saved outgoing delivery states across transports are separate.
- Signature-checked relay envelopes and valid recipient receipts accepted during this service session. These observations verify packet processing, not a physical route. Underlying link radio and physical multi-hop are explicitly unverified.

Refresh/retry/permission/Nearby tools/Android settings actions use real APIs. Opening diagnostics alone does not start discovery. Refresh occurs every five seconds only while visible. Counts are snapshots; service counters reset on restart while queues survive.

## Automated coverage

New JVM tests cover unresolved request overlap, capped backoff/reset and inbound requests. New Android tests use real Router, SQLite and Android Keystore with **simulated links**, covering:

1. Key exchange, encrypted direct delivery, no receipt before persistence, reconnect retry and duplicates.
2. Three simulated nodes: relay restart persistence, subsequent delivery, reverse receipt, ciphertext tamper and changed-key rejection.
3. Full-queue idempotency, unchanged lifetime/payload and atomic pending promotion after capacity becomes available.
4. Actual Inbox duplicate persistence and stored callbacks.
5. Stale Nearby disconnect callback isolation.

The interface test opens diagnostics from Nearby, checks rendered states and explicit radio/multi-hop limitations, and captures a screenshot. Fixtures are confined to disposable emulator accounts/databases.

Local Gradle remains blocked: `gradle: command not found`; no local Android toolchain. CI provides Java 17, Gradle 8.7, SDK 34 and API 29/34 emulators. Current-run results are recorded in the PR after execution. Simulated links do not validate radios.

## Required physical-device tests

Use dedicated test accounts and compatible builds on phones with Google Play services. Preserve existing installed data and signing identity; do not clear production storage/recreate accounts. Record OS/vendor, app commit, permissions/radios, timestamps, message IDs and diagnostics before/after. Redact identities from shared reports.

| Scenario | Procedure and required evidence |
| --- | --- |
| Two phones | Disable internet on A/B, grant permissions, open Nearby, compare security codes. Send text/stickers both ways; verify exact bodies once per ID and receipt-driven Delivered. Repeat with queued messages and initially unknown contact keys. |
| Permissions/radios | Cover Android 10, 12, 13/14+ and two vendors. Deny/revoke precise location/Nearby permissions, toggle Bluetooth/Wi-Fi/Location individually. Require truthful diagnostics, retained queues, no false Delivered and recovery after regrant. Do not infer Wi-Fi Direct from a successful Nearby connection. |
| Reconnection | Move B out of range while sending, enqueue messages, restore range. Check eventual delivery exactly once. Rapidly toggle radios/revoke permissions while connecting; record delays or stuck endpoints. |
| Persistence/capacity | Dedicated fixtures: stop/relaunch sender/receiver without deleting data, fill queues and free capacity, check IDs/timestamps and pending retention. Verify 24-hour expiry with fixtures, not production phone clock manipulation. |
| True carry/forward | A/B/C, no internet. Pre-exchange A/C keys through trusted QR/fingerprint comparison. A/C must be physically unable to discover each other throughout. Connect only A/B, send A→C, disconnect A/B, restart B, connect only B/C. Verify C persists once. Disconnect B/C, connect only A/B; verify recipient receipt clears A's queue. Repeat reverse direction. Capture evidence of link separation; mutable hop counts are insufficient. |
| Background | Screen off, lock, swipe away, process kill, Doze and force-stop separately. Record foreground-service/notification settings and reopening recovery. No automatic force-stop/reboot recovery is promised. |
| Security | Reject changed keys and tampered/replayed envelopes using isolated fixtures. Verify trusted first-contact fingerprints, relay ciphertext confidentiality and key continuity after signed APK update. Check logs/diagnostics for secret exposure. |
| Battery | Equal-duration idle baseline, discovery without peers, connected idle and defined relay workload on physical phones. Record battery/thermal state, scan time, wakeups and drain. No efficiency claim from emulator timings. |

Physical two-phone messaging, radio selection, true three-phone multi-hop and battery measurements are **not run** here. Automated tests establish application logic only.
