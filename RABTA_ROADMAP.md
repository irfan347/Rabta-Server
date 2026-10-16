# Rabta repository findings and roadmap

Audit date: 2026-10-16 UTC. This is an inspection and documentation checkpoint, not a feature implementation or production rollout.

## Baseline and safe checkpoint

- Repository: `https://github.com/irfan347/Rabta-Server`.
- Audited `main`: `d7c480a8e169013935435995141de5ba36277aa6`, the merge of PR #1.
- Source tree: `93ff3f677e09b5cb05a43f15a6631f4b11d3a7d3`.
- Application version: **1.9.1**, Android version code **33**; backend package version: **1.9.0**.
- Scope: all 79 tracked files, including 52 Kotlin files, four JavaScript files, five Python files, seven XML files, build configuration, workflow and deployment instructions. The baseline contains 5,937 lines.
- The supplied workspace initially contained source copies, not a usable Git checkout. Normal cloning failed because the configured network proxy refused the connection. An isolated shallow checkout was reconstructed at `/workspace/Rabta-Server` using the GitHub connector, verifying all 79 blob hashes, the tree and the exact signed upstream commit. Earlier source copies were preserved. Parent history is not present locally.
- Initial checkout status: clean. Audit branch: `audit/rabta-existing-project`. Local backup branch: `checkpoint/pre-audit-2026-10-16`, pointing to the audited commit.
- Pre-edit source and Git metadata archive: `/workspace/rabta-audit-checkpoint/Rabta-Server-main-d7c480a8-before-audit.tar.gz`.
- Archive SHA-256: `5b63b418a373cf1ee94f554c25b628b81abfa39f0192d3e057c6377b117d2221`.
- This archive is a source checkpoint, not a production database or device-data backup. No production data was exported or changed.

## Language, framework and architecture

| Area | Existing implementation |
| --- | --- |
| Android | Native Kotlin, single `:app` module, package/application ID `com.irfan.rabta`; min SDK 26, compile/target SDK 34. |
| UI | Android Views created programmatically in Activities; shared `ThemedActivity` extends AndroidX `FragmentActivity`. No Compose, Flutter or React Native. |
| Build | Kotlin 1.9.24, Android Gradle Plugin 8.5.2, Java/JVM 17; CI installs Gradle 8.7. No committed Gradle wrapper. |
| Organization | Activity-driven architecture with singleton services/helpers and a main-thread `RabtaEvents` event bus. No explicit MVVM, dependency-injection container or repository abstraction. |
| Background work | `MessageService` foreground service, handlers, raw threads and executors. No WorkManager or push provider configured. |
| Online networking | OkHttp 4.12.0 for REST and WebSocket; `WsClient` owns connection/authentication/retry state, `FeatureApi` serves newer REST features. |
| Offline transport | Google Play services Nearby Connections 19.3.0, `P2P_CLUSTER`; Nearby selects Bluetooth/Wi-Fi transport. `Router` owns application-level store/carry/forward. No separate active raw Wi-Fi Direct connection manager. |
| Calls | Voice uses 8 kHz mono PCM/base64 frames over the authenticated WebSocket. Video uses WebRTC SDK 114.5735.07 with WebSocket signaling and STUN/optional TURN. |
| Other dependencies | AndroidX core 1.13.1, biometric 1.1.0, ZXing embedded 4.3.0, JUnit 4.13.2, AndroidX test runner 1.5.2/ext JUnit 1.1.5 and MockWebServer 4.12.0. |
| Backend | Node.js >=18, CommonJS JavaScript, Express ^4.19.2, `ws` ^8.18.0 and PostgreSQL client `pg` ^8.12.0. One HTTP/WebSocket process with in-memory maps and optional PostgreSQL persistence. |

### Existing data flows

Online chat: `ChatActivity` -> `Outbox` -> local `Db` -> `WsClient` -> backend -> recipient `WsClient` -> `Inbox` -> local `Db` -> `RabtaEvents` -> UI/notifications. `Session.init` initializes both inbox and outbox, including after service restart.

Offline direct chat: `Outbox` falls back to `MeshBridge` -> `Router` -> account-specific `MeshStore` -> `Mesh`/Nearby. Relay phones store encrypted envelopes and forward later. The recipient decrypts and persists through `Inbox`; the persistence callback creates a signed delivery receipt.

File/video/custom sticker: private local file -> `Transfers` -> authenticated chunk-upload REST endpoints -> metadata message -> authorized recipient download and SHA-256 verification. Chat photos and voice notes retain their older inline-base64 paths.

Status: `StatusActivity` -> `FeatureApi`/`Transfers` -> server audience-filtered status records. Text opens in a dialog; media opens through an Android file-viewing application.

## Database, authentication and backend

### Android storage

- `Db.kt`: SQLiteOpenHelper, `rabta.db`, schema version 5. Tables: `conversations`, `messages`, `message_meta`, `groups`, `calls`. Current upgrades add missing columns/tables rather than dropping chat tables.
- `MeshStore.kt`: per-account SQLite database `mesh_v2_<id>.db`, version 1; tables `packets`, `seen`, `pending`.
- `Session.kt`: private SharedPreferences `rabta_session` hold ID, username, name, bearer token, server URL and local profile image. Tokens are not stored through an encrypted-preferences layer.
- Other preferences hold appearance, privacy/block sync, upload/download resume metadata, sticker recents/favorites and pinned mesh public keys. Privacy and transfer preferences are account-scoped; appearance/stickers are shared.
- Attachments live in private `files/attachments`; FileProvider grants temporary viewing access. Old photos and voice notes can still be base64 message bodies in SQLite.
- Android Keystore retains RSA mesh identity keys. Account-specific preferences pin contact public keys. Backups do not export those private keys.
- `ChatBackup` encrypts a ZIP of chat tables and saved attachments using PBKDF2-HMAC-SHA256 (210,000 iterations) and AES-256-GCM. Restore validates authentication/account/schema and merges with conflict-ignore. Android automatic backup is disabled.

### Accounts and server persistence

- Usernames are normalized to lowercase, 3–20 letters/numbers/underscore; PINs are 4–8 digits. Server account IDs are 12 uppercase hexadecimal characters.
- PINs use salted scrypt. Login has an in-process per-username attempt limit. Tokens are custom HMAC-SHA256 signed `id.expiry.signature` values with 30-day expiry, not JWT/OAuth/Firebase authentication.
- The Android reauthentication path requires the same account ID and retains local chats. WebSocket becomes online only after matching `ready.id`; code 1008 or HTTP 401/403 requests reauthentication. Retry backs off to 30 seconds.
- `DATABASE_URL` enables PostgreSQL tables `ru_users`, `ru_messages`, `rg_groups`, plus `rf_data` for status/upload/grant/block/report records. Initialization is additive and finishes before listening.
- Without `DATABASE_URL`, accounts, queued server messages and newer feature state use process memory. `TOKEN_SECRET` also defaults to a new random value per process if unset, invalidating previous tokens after restart.
- Backend maps/online presence are single-process state; startup loads at most 10,000 users. The existing deployment guide recommends one instance. There is no distributed queue/presence layer.
- `Config.SERVER` points to `https://rabta-server-1e2i.onrender.com`. A repository URL or successful CI build does not establish which revision is currently deployed there.
- Fresh CI health observation at **15:14:30 UTC**: HTTP 200, `ok: true`, **`db: false`**. This confirms the responding instance reports no PostgreSQL pool; it does not prove data has already been lost. No account was created or authenticated by the probe. `/health` reports pool presence, not an actual database query/readiness test.

### API inventory

| Method / endpoint | Function and authentication |
| --- | --- |
| `POST /api/register`, `POST /api/login` | Public account creation/login. |
| `GET /api/user/:username` | Public username lookup, ID/name/presence/last seen. |
| `GET /api/me` | Bearer-authenticated current account. |
| `POST /api/groups` | Authenticated group creation, up to 50 members including creator. |
| `GET /health` | Public process/pool/presence counts. |
| `GET /ws` upgrade | Bearer header or token query; authenticated WebSocket session. |
| `GET /api/ice` | Authenticated STUN and optional one-hour HMAC TURN credentials. |
| `GET /api/privacy`, `POST /api/block`, `POST /api/report` | Authenticated privacy/report operations. Reports have no moderation UI/service. |
| `POST /api/uploads` | Authenticated upload initiation/deduplication. |
| `GET /api/uploads/:id` | Owner-only resume offset/state. |
| `POST /api/uploads/:id/chunks` | Owner-only ordered/idempotent base64 chunks. |
| `POST /api/uploads/:id/complete` | Owner-only SHA-256 verification/finalization. |
| `DELETE /api/uploads/:id` | Owner-only cancellation of unfinished upload. |
| `GET /api/files/:id` | Authenticated owner/recipient/active-status audience access. |
| `POST /api/status`, `GET /api/status` | Authenticated publish and audience-filtered feed. |
| `POST /api/status/:id/view`, `DELETE /api/status/:id` | Authorized view recording and owner deletion. |

WebSocket messages include `ready` (protocol 2), `msg`, `group_msg`, `ack`, `typing`, `read`, `group_created`, `ping`/`pong`, `call_init`, `incoming_call`, `call_ringing`, `call_state`, `call_accept`, `call_audio`, `call_end`, `video_offer`, `video_answer`, `ice_candidate`, `video_end`.

The client also calls **`POST /api/me/photo` and `GET /api/user/photo/:id`**, but neither route exists in this backend. These are unfinished integration points.

## Implemented functionality versus limits

“Implemented” below means a real code path exists; it does not claim every physical-phone or production scenario has passed.

| Area | Implemented | Unfinished, limited or unverified |
| --- | --- | --- |
| Authentication | Registration, PIN login, saved session, same-account reauthentication and connection diagnostics. | No PIN recovery/reset, token refresh/revocation or verified multi-account isolation. |
| Chat | Persistent direct/group history, send/retry, unread counts, receipts, typing, text search and date separators. | Server delivery is socket-based, not durable recipient acknowledgement; history UI caps at 200 messages without older-page loading; search covers `text`, not rich replies. |
| Chat actions | Reply quote, six reactions, forward, copy, star/unstar, starred screen, delete-for-me. | Reply stores a quoted preview rather than a navigable original-message reference. Rich replies/reactions require internet; deletion is a local tombstone. |
| Media | Compressed photo preview/send, voice-note record/play, video/files up to 8 MB, upload progress/cancel/resume and checksum validation. | Resumable uploads apply to files/video/custom stickers, not older photo/voice-note paths. Downloads restart rather than range-resume. Completed server uploads have no normal user deletion path. |
| Stickers | Twelve original bundled stickers, recent/favorite lists and PNG/WebP/JPEG import up to 512 KB. | No WhatsApp catalog integration, animated sticker format or remote pack service. |
| Voice calls | Participant-checked signaling, accept/end/busy/timeout, PCM transport, mute/speaker and local call history. | No offline/group calls; no real two-phone quality/background validation. PCM over WebSocket has no dedicated codec/jitter adaptation beyond a bounded playback queue. |
| Video calls | Camera capture, remote rendering, offer/answer/ICE, camera switch/mute/speaker and local history. | TURN support/config template exists, but no relay has been provisioned or proven working in this audit. Settings checks configuration, not relay connectivity. |
| Ringing | Caller starts at Calling; receiver's `call_ringing` acknowledgement changes to Ringing and starts caller tone. Incoming notification channel uses the phone's default ringtone. | Online presence alone does not set Ringing. Notification permission/channel/DND and background restrictions can prevent audible ringing despite the notification acknowledgement. |
| Status | Text/photo/video, explicit audience, 24-hour expiry, unique views and owner deletion backed by real API records. | No offline feed/cache/post queue, auto-advancing story viewer or status replies. |
| Contacts/groups | Username lookup, direct ID entry, trusted QR exchange and conversation-based contacts; online group creation/messaging. | No phone address-book sync or separate contact directory. No group administration/member-editing/leave APIs or group metadata resync endpoint. Offline recipients can miss `group_created` membership metadata. |
| Nearby/mesh | Nearby discovery/connect/retry, visible permission/radio errors, encrypted direct text/bundled stickers, durable relay queue/replay cache and signed delivery receipts. | Requires prior account login, Google Play services, compatible app versions, permissions and enabled radios. No offline media/groups/calls/status. Physical multi-hop/radio tests remain outstanding. |
| Carry/forward | Eight-hop budget, 24-hour lifetime, 30-second carry cycle, RSA-OAEP + AES-GCM envelopes and RSA signatures; pinned keys/fingerprints/import. | Trust-on-first-use requires trusted fingerprint comparison. Recipient keys must already be known/imported or learned directly. Pending plaintext before encryption remains in the local database. |
| Long range | Legacy `LongRange.kt` Bluetooth LE Coded PHY code is retained. | Not connected to the active Router; `longRangeOn=false`. Legacy file/stream/audio transport methods exist but Router callbacks intentionally do nothing. These are not working end-to-end offline file/call features. |
| Appearance/privacy | System/light/dark modes, vector controls, text scaling, partial Urdu/RTL, biometric/device-credential lock, preview hiding and block/report. | Many strings remain hardcoded/untranslated. Voice-note waveform bars are decorative fixed values and duration is estimated from bytes, not decoded audio metadata. |
| Background delivery | Sticky foreground service maintains online/Nearby paths and sends message/call notifications. | No FCM/push wake-up or registered boot receiver; `RECEIVE_BOOT_COMPLETED` permission alone does not restart the app. Process-killed/force-stopped/reboot behavior is unverified. |

## Existing failures and risks

### Confirmed test/environment failures

1. **Android connection instrumentation tests fail:** fresh job [117044583379](https://github.com/irfan347/Rabta-Server/actions/runs/38886157366/job/117044583379) reproduced the historical failure: **5 tests ran, 3 passed, 2 failed**. Failing methods: `serverCloseReconnectsAndUpgradeAloneIsNotOnline` and `rejectedSessionDoesNotClaimOnlineAndCanReauthenticate`. Both report `android.os.NetworkOnMainThreadException`. `ConnectionTest.configure` evaluates MockWebServer's URL inside `runOnMainSync`; this is a test-setup investigation lead, not a proven production WebSocket defect. No test or application fix is included here. The workflow then skips `adb pull` because Gradle exits unsuccessfully, so no screenshot artifact is uploaded even though the UI test completed. The device report is available as artifact [12055726955](https://github.com/irfan347/Rabta-Server/actions/runs/38886157366/artifacts/12055726955).
2. **Local Android build blocked:** `gradle: command not found` (exit 127). This environment has Java 21, no Gradle/Android SDK tools, and the repository has no Gradle wrapper. CI supplies the required toolchain.
3. **Local backend startup blocked:** isolated `DATABASE_URL= TOKEN_SECRET=local-audit-only PORT=0 npm start` fails with `Cannot find module 'express'`. Runtime npm dependencies are not installed. Handler tests use stubs and therefore do not verify a real Express/ws/pg server startup.
4. **Shell network access blocked by unavailable proxy:** normal `git clone` fails connecting to proxy port 8080. GitHub connector reads and CI remain available. No network-policy bypass was attempted.

### Source and operational findings requiring future focused work

| Priority | Finding / evidence | Consequence and next verification |
| --- | --- | --- |
| P0 | Fresh live `/health` reports `db:false`; `index.js` uses process memory and a random token secret fallback. | Verify existing host secrets/persistence through authorized hosting access and obtain a real data backup before deployment/storage changes. Do not recreate users to mask failures. Persistent `TOKEN_SECRET` configuration is not observable from this audit. |
| P1 | `index.js:deliverPending` marks rows delivered before writing sockets; online sends acknowledge immediately after `send`. PostgreSQL reads only 200 pending records once per connection. | Disconnect/inbox-write failure can lose delivery, and larger backlogs can remain until another reconnect. Verify acknowledgement-driven delivery and backlog draining with isolated failure tests. |
| P1 | Registration/group creation update memory before database writes and swallow insert errors; user startup load has `LIMIT 10000`. | UI can report successful persistence that disappears after restart; users beyond the startup cap can become unavailable. Preserve existing IDs and data while investigating. |
| P1 | `Db` uses one shared `rabta.db`; logout only clears session preferences. `Outbox` and `Inbox` are process-wide singletons. | Switching to a different account on the same installation can expose/mix prior local history or pending state. Same-account reauthentication guard does not solve general account isolation. |
| P1 | `WsClient` profile-photo routes are absent in `index.js`/`features.js`; `ProfileActivity` saves locally and shows success without checking upload result. | Photo appears changed on the sender while peers cannot fetch it. Verify against an isolated real HTTP server before claiming profile sync. |
| P1 | No push-wake path; calls depend on a connected service. TURN is optional and not proven deployed. | Validate foreground/background/locked-screen/notification-denied calls on separate networks and Android versions. A successful APK build cannot certify calls. |
| P2 | Completed upload chunks and all `rf_data` are retained/loaded in server memory; completed uploads count toward a 64 MB/account quota indefinitely. PostgreSQL SSL certificate verification is disabled in code unless SSL itself is disabled. | Establish retention/capacity and TLS verification requirements before a separately authorized storage/configuration change. No cleanup or migration was performed. |
| P2 | Group metadata is only pushed when created; typing/read handlers do not apply the block checks used for messages/calls/status. | Inspect offline membership recovery and block behavior for auxiliary events. Existing messages and membership must remain intact. |
| P2 | Debug APK artifacts only, no release signing setup; no npm lockfile; duplicate legacy `package (1).json`; some outdated 1.8/1.9 wording remains in existing docs. | Reproducibility/release identity needs review. Preserve the signing identity needed to update installed APKs. Do not delete retained files as part of this audit. |

Online chat, uploads and PCM voice audio are readable by the relay server; TLS is transport protection, not application-level end-to-end encryption. Encrypted mesh envelopes and encrypted backups are separate implemented protections. Do not label every transport end-to-end encrypted.

## Build and test verification

| Check | Audit result |
| --- | --- |
| `python3 -m unittest discover -s android/tests -v` | **PASS: 17 tests** locally and in fresh CI. Covers SQL queries, carry-store persistence/expiry and source-project detection/safe ZIP extraction. |
| `npm test` | **PASS** locally; this local Node 24 runtime reports two passing test files. Running `node --test --test-isolation=none --test-reporter=tap tests/*.test.js` exposes **15 individual passing tests**. Fresh CI also reports 15 passing tests. |
| `node --check index.js`, `node --check features.js` | **PASS**. Syntax checks only. |
| Local Gradle build | **BLOCKED** by missing Gradle/toolchain, not a demonstrated source compile failure. |
| Fresh main APK/JVM build | **PASS**, rerun [38893913044, job 117044580053](https://github.com/irfan347/Rabta-Server/actions/runs/38893913044/job/117044580053), exact audited `main` commit. Executed `:app:testDebugUnitTest :app:assembleDebug`; 13 JUnit methods are declared across the three JVM test classes. APK assembly completed successfully. |
| Main device-test coverage | **SKIPPED by workflow condition**: interface job runs only for `pull_request`, not push/workflow_dispatch. Main's green workflow does not certify instrumentation success. |
| Fresh Android device tests | **FAIL: 3 passed, 2 failed out of 5**, on Android 10/API 29 emulator. Rerun [38886157366, job 117044583379](https://github.com/irfan347/Rabta-Server/actions/runs/38886157366/job/117044583379) uses PR commit `cd97a64b789abbe3642caa2a2f52ef3554d5cd18`, exactly the same source tree as audited main. The two connection tests fail as described above. The keystore, permission-message and UI/backup tests pass. |
| Actual backend startup/PostgreSQL integration | Local startup blocked by missing dependencies; existing Node tests use fake transport/DB adapters. No real PostgreSQL integration suite exists in this checkout. |
| Live read-only probe | HTTP health 200 / `db:false`; WebSocket HTTP 101 followed by socket end before an authentication frame. No authenticated chat/call was attempted; this does not verify current backend protocol compatibility. Probe failures are printed, not asserted, so a green probe step alone is not an availability test. |
| Physical phones | Not run: Bluetooth/Wi-Fi discovery, mesh A-B-C carry-after-disconnect, locked-screen ringing, cross-network media calls, reinstall/update data retention and account switching. |

CI warnings include deprecated Android APIs, Kotlin callback parameter-name warnings and action Node-runtime deprecations. The APK/unit-test job succeeds despite them. The earlier screenshots' unresolved Kotlin reference / missing Gradle-root failures are historical; they were not reproduced by the current main APK build. Render's earlier root `package.json` error predates the user's merge; current main contains canonical `package.json`.

## Roadmap for subsequent authorized work

No item below is implemented by this audit.

1. **Protect existing data and establish deployment identity:** verify current backend revision, PostgreSQL persistence, stable token signing secret, backups and installed APK signing identity. Record observations without exposing credentials or changing user IDs.
2. **Restore complete test visibility:** investigate the two connection test failures; arrange device-test execution for the intended branch and screenshot retrieval after test failure. Keep existing functional tests intact. Add real HTTP/WebSocket/PostgreSQL coverage only when tackling the relevant defect.
3. **Harden existing delivery/account behavior:** address durable acknowledgements/backlog draining, registration persistence errors and local account isolation through small compatible changes with data-preserving tests.
4. **Finish existing integrations:** repair profile-photo client/server agreement and offline group metadata recovery. Verify local block behavior and realistic media metadata. Retain current UI and dependencies.
5. **Validate calls and mesh on hardware:** two phones on different networks for voice/video/ringtone; Android permission/notification/background cases; three-phone store/carry/forward, process restart, duplicate/expired packet handling and changed-key rejection. Provision/verify TURN only as a separate authorized infrastructure task.
6. **Then select improvements:** storage retention/capacity, remaining localization/accessibility and media presentation. Prioritize measured failures; no rewrite, feature removal, replacement authentication or fake success UI.

## Repository navigation

- Root backend: `index.js`, `features.js`, canonical `package.json`, retained legacy `package (1).json`; handler tests in `tests/server.test.js` and `tests/features.test.js`.
- Android build/config: three `*.gradle.kts` files, `gradle.properties`, `.gitignore`, main/debug manifests, launcher resources and FileProvider/network-security XML.
- Screens: `AuthActivity`, `ConversationListActivity`, `ChatActivity`, `ProfileActivity`, `SettingsActivity`, `StatusActivity`, `NearbyActivity`, `StarredActivity`, `IncomingCallActivity`, `VideoCallActivity`.
- Shared presentation: `AppTheme`/`ThemedActivity`, `FeatureUi`, `UiIcons`, `L`, `ProfileHelper`, `Stickers`, `ConnectionHelp`, `ConnectionStatus`.
- Identity/data/network: `Config`, `Session`, `Db`, `Inbox`, `Outbox`, `MessageDelivery`, `MessageActions`, `RabtaEvents`, `WsClient`, `FeatureApi`, `MessageService`, `Privacy`, `AppLock`.
- Media/backup/calls: `Attachments`, `Transfers`, `MediaHelper`, `ChatBackup`, `BackupCrypto`, `InternetVoiceCall`, `CallSounds`.
- Offline stack: `Mesh`, `MeshBridge`, `NearbySetup`, `Router`, `MeshStore`, `MeshSecurity`, `MeshCrypto`, `MeshPolicy`; retained inactive `LongRange`.
- Verification: three JVM test classes, two Android instrumentation classes, three Python test files and scripts `locate_android_project.py` / `check_live_server.py`.
- CI/deployment: `.github/workflows/main.yml`, `deploy/README.md`, `deploy/turnserver.conf.example`, existing `android/README-UPDATE.md`.

Only `AGENTS.md` and `RABTA_ROADMAP.md` are intended project changes in this task. Source, dependencies, workflows, schemas, authentication and production configuration retain the audited baseline behavior.
