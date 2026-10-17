# Rabta Radar

Radar reuses the existing Nearby destination, Google Nearby Connections `P2P_CLUSTER`, account IDs, Android Keystore identities, pinned contact keys, Router and private ChatActivity. No new transport, dependency, database schema, account or backend API.

## Privacy and connection behavior

- Discoverability defaults **off**, including after upgrading an installation with no Radar preference. It is an explicit, account-scoped preference in `radar_<id>` and stays on until switched off. Starting the messaging service, visiting Nearby, granting permissions or tapping retry never opts in.
- While on, the existing advertisement shares only the Rabta display name and ID. Radar does not read phone numbers, contacts or GPS coordinates, and never calculates or fabricates distance. Android discovery permissions are still required by Nearby.
- Discovery populates real peers without automatically requesting a connection. A user presses Request connection; the other phone sees an incoming request and must Accept. Nearby's real connection code is shown for comparison. Payloads are ignored until approved connection success. An incoming success callback alone cannot grant consent.
- After approval, the existing signed key exchange and key pinning run. Chat opens only when the connected peer's signed key matches the pinned identity. Changed keys do not silently replace old keys. First contact still uses trust on first use; compare security codes with the person.
- Reject/cancel/timeout cannot authorize a later success callback. Switching off stops advertising/discovery and disconnects Nearby while preserving durable queues. Each subsequent connection requires a fresh request; saved chats and advertised IDs do not imply consent. Both phones should use the updated build for the intended consent experience.
- Block uses the existing account block list and block-sync logic, disconnects current/pending endpoints and hides/rejects future requests from that ID. Existing Privacy settings manage unblocking.

## Spam and resource limits

Requests expire after 60 seconds and are cleaned on the next service/UI maintenance pass. A peer ID has a 60-second request cooldown; rejection/cancellation extends it to 120 seconds. Duplicate endpoint requests and parallel requests for the same ID are refused. The receiver accepts at most five new incoming requests per minute across IDs, with at most eight requests pending and 200 peer entries. The 500-entry cooldown budget refuses new requests rather than evicting unexpired limits. Cooldowns survive transport retry/toggle cycles in the same service instance, but are not persisted after process death. Blocking is persistent. Advertising IDs are untrusted before key exchange; rate limits are not a complete defense against adversarial radio traffic.

The animated sweep is decorative activity only: no fake user dots, coordinates or distance. It stops when paused, detached, hidden, power saving or system animations are disabled. Peer cards update on state changes rather than rebuilding every animation frame. The UI shows up to 50 users while retaining the transport cap. Existing QR exchange, permissions tools and Connectivity Diagnostics remain accessible.

The ordinary messaging service and outgoing queue remain available while Radar is off. Pending offline messages can be encrypted/carried after a later approved connection. Existing offline text/sticker delivery, receipts and relay processing remain in Router. Turning discoverability off necessarily prevents new offline links; reconnect after enabling requires approval again.

## Verification

- JVM tests cover consent, requested identity, expiry, rejection, cooldown across transport reset, blocked identities, global request budget, endpoint/list bounds and duplicate-ID request visibility.
- Android transport tests exercise real Mesh discovery/lifecycle/payload callbacks with a simulated Nearby SDK client. They verify opt-out makes no SDK call, discovery never auto-connects, accept gates payloads, reject/block prevent late connection and old callbacks cannot reconnect after stop.
- Android screen test checks default privacy, account-scoped opt-in, rendered privacy guidance and switching off. Existing interface tests capture Radar and open Connectivity Diagnostics; existing mesh integration tests retain encrypted direct/relay/receipt coverage.
- Local Gradle/SDK are unavailable; APK/JVM and API 29/34 device checks run through GitHub Actions. Actual results are recorded in the PR.

These are application/emulator tests, not physical radio tests. On two dedicated phones with the same build and test accounts, verify: both default off and undiscoverable; both explicitly enable; peers appear without connecting; sender requests; receiver sees matching code and accepts/rejects; accepted users open existing chat after key exchange and exchange encrypted offline text/stickers; rejection/cancel/expiry/rapid repeat requests never connect; block survives app restart; permission denial/regrant and radio toggles give truthful status and successful retry; off disconnects and preserves queued messages. Cover Android 10, 12, 13/14+ across two vendors, locked/background phones, rotation and process restart. Do not clear production data. See `MESH_AUDIT.md` for the separate three-phone carry/forward and battery plan. Physical discovery, latency/range, multi-hop and battery behavior remain unverified here.
