# Rabta 1.9.1

Android source is in `android/`; the root `.github/workflows/main.yml` builds it with
Java 17, Gradle 8.7, SDK 34 and build-tools 34.0.0. APK artifacts come from Actions.

## Interface and attachments

- Purple/slate palette, persistent System/Light/Dark setting (header settings or Profile).
- Density-correct initials, rounded cards and bubbles, an overlay compose button,
  hidden-by-default chat search, and no automatic search keyboard.
- Full call screen, selected mute/speaker controls and a connected timer.
- Share photos, videos and documents. Video/file limit: 8 MiB. Private attachment
  files are kept outside SQLite; temporary URI grants let a chosen app open them.
- A picker of 12 original Rabta expression stickers; import PNG, WebP or JPEG
  stickers up to 512 KiB, plus Recent/Favorites tabs and saving received stickers.
  These are not WhatsApp-owned sticker packs.
- Online attachment transport preserves full bodies and checks file SHA-256 on
  receive. Failed messages can be retried with the same ID.

## Calling and ringtones

Caller starts at Calling. The receiver app acknowledges an incoming call before
Ringing appears and local ringback starts. Offline recipients can reconnect during
the 45-second calling window. Answer stops ringback, and the connected duration
starts only when media connects. Decline, timeout, busy, disconnect and hang-up stop
sounds. Calls carry an ID; unrelated users and stale calls cannot inject call audio
or ringing/end events. Audio relay requires acceptance first.

Incoming calls use Android's default ringtone URI in a dedicated call notification
channel, with a looping notification and a system-enforced timeout. The device's
selected ringtone, ring volume, silent mode, DND and notification permissions apply.
The user can override the app's call sound in Android notification settings.
Both callers and the server must run this updated call protocol. The old backend
does not support call IDs or recipient-confirmed ringing.

## Offline store-carry-forward

Nearby Connections selects Bluetooth/Wi-Fi transport automatically using P2P_CLUSTER.
There is no separate user-selected Wi-Fi Direct mode. Google Play services and
Nearby/Location permissions are required. Android can suspend discovery in the
background; radio range and background behavior need real-phone testing.

Direct text and built-in stickers are supported offline. Videos, photos, documents,
imported stickers, voice notes, groups and live calls need internet. There is no
resumable multi-hop transfer for large media in this release.

The V2 protocol persists pending messages, relay packets and replay IDs in an
account-scoped SQLite database. Relays store only encrypted text/sticker payloads.
The queue allows 500 envelopes / 8 MiB, and 100 messages awaiting a contact key;
new sends fail visibly when capacity is exhausted. Packets expire after 24 hours
and have an eight-hop forwarding budget. Messages at the hop limit cannot be
reintroduced via a later connection. Expired own messages are marked retryable.
The sender sees delivered only after the receiver persists the message and a
signed receipt returns over the mesh. Receipts may arrive much later than data.

Before sending encrypted offline messages, exchange contact keys once while
nearby, or use Profile -> Offline security code -> Share contact key and import
it through the chat attachment menu -> Offline security. New chat accepts a
12-character Rabta ID without an internet lookup. If a key is missing, the message
stays in the local queue until a key is available.

AES-256-GCM protects content; RSA-2048 OAEP wraps the content key and RSA-SHA256
signs the envelope and receipts. Private identity keys stay in Android Keystore.
First-seen keys are pinned (TOFU); nearby names/IDs are not independently verified.
Compare security codes using a trusted channel. Changed keys are blocked and
cannot silently replace a pinned key. There is no automatic key recovery or
key-change migration yet. Routing addresses/time are visible to relays. A malicious
relay can drop messages; hop counts do not prevent malicious re-broadcast.
Encryption here covers offline V2 text/stickers, not all online messaging.

Both ends and relay phones need V1.8 or newer. Legacy plaintext V1 packets and the old
unencrypted BLE long-range advertising protocol are not forwarded. The previous
standalone nearby voice class was unused by the app and has been removed; live
calls still use the online transport.

## Backend update

This PR also changes root `index.js`:

- Preserve full attachments in PostgreSQL instead of truncating to 65,535 chars.
- Bound message sizes, reject unsupported types/unknown recipients, and report
  storage failure instead of falsely acknowledging success.
- Avoid repeated pending entries on sequential retries; keep group identity for
  offline group messages and reload saved groups on server startup.
- Add scoped video offer/answer/ICE/end signaling between call participants.

The backend must be deployed to the existing service for offline online-storage
fixes and video signaling to take effect. This branch does not deploy the server.
A canonical root `package.json` is supplied for `npm install` / `npm start`; the
old `package (1).json` is retained. New Status, privacy and resumable-transfer endpoints
are in root `features.js`; deploy that file together with `index.js`.

## Status and chat tools

- Open Status from the chat list. Post text (700 characters), a photo or a video
  (8 MB). Select the contacts allowed to view each update; no contacts are selected
  by default. Server-enforced visibility expires after 24 hours. Owners see viewers
  and can delete updates. Status needs internet and the 1.9 backend.
- Long press a message to reply, react, forward, copy, star/unstar or delete locally.
  Reply and reaction messages require 1.9 clients and internet. Delete for me does
  not unsend a message already delivered to another phone.
- The header menu opens Starred messages, Nearby and Settings. Nearby displays
  connected phones and queued messages with expiry. Show/scan a contact QR to add
  an ID and public key without an internet lookup; verify the fingerprint first.
- Video, file and imported-sticker uploads use resumable 128 KB chunks, SHA-256
  integrity checks and authenticated downloads. Long press during upload to cancel;
  tap paused/failed to retry. Network failures resume from the saved server offset.
  Resumption is online only; media is not forwarded through the offline mesh.

## Privacy, backup and accessibility

Tap a chat's name for block/unblock, report and offline security. Blocking is
applied on the device immediately and synced to the server. Reports are stored for
an administrator; no automatic moderation or administrator dashboard is included.
Settings can hide notification previews and enable fingerprint/device-screen-lock
protection after 30 seconds away. Set up a phone lock before enabling app lock.

Settings provides encrypted backup and restore using Android's file picker. Backups
use AES-256-GCM and PBKDF2-HMAC-SHA256 (210,000 iterations), a random salt/nonce and
a password of at least 10 characters. Restore authenticates the complete file before
merging rows and verifies the account ID. Limits: 24 MB chat JSON and 100 MB saved
attachments. Unsaved remote media is not downloaded automatically into a backup.
Keep the password safely; it cannot be recovered. Offline private identity keys are
non-exportable and are not included. New phones must exchange keys again.

English/Urdu core navigation labels, right-to-left layout and four text sizes are
available in Settings. Some detailed dialogs and server errors remain English.

## TURN deployment

The Android client requests temporary ICE credentials from authenticated `/api/ice`.
The permanent TURN secret stays on the backend. Without `TURN_URL` and `TURN_SECRET`,
the API explicitly reports no configured relay and returns STUN only. Settings →
Video relay status shows configuration, not a connectivity test. Calls may fail on
restrictive networks until a real relay is provisioned.

See root `deploy/README.md` and `deploy/turnserver.conf.example`. No TURN service has
been provisioned and no live relay connectivity has been verified. PostgreSQL is
required for durable server Status/uploads/blocks/reports. Upload storage is limited
to 64 MB per account; this version uses a single server process and database chunk
storage rather than an object storage service.

## Validation

- Python tests use production project-locator and SQL queries, including durable
  carry queue persistence, replay IDs and expiry selection.
- JVM tests cover monotonic receipts, expiry/hop boundaries, hybrid crypto round
  trips, wrong keys, modified routing metadata and tampered signatures.
- Node tests exercise actual server handlers with in-memory transport/DB stubs:
  large attachments, sequential dedup, limits, video participants and group metadata.
- An Android emulator test captures real light/dark chat/list screens, attachments,
  stickers, incoming calls, Settings, Nearby, Status and Urdu navigation; it checks
  avatar sizing, startup focus, starred items and authenticated backup restoration.

CI status: https://github.com/irfan347/Rabta-Server/pull/1
Real Bluetooth/Wi-Fi relay delivery, process death, expiry over time, large-file
sending and calls must also be tested on physical phones before release.

## Connection recovery (1.9.1)

Online is shown only after an authenticated `ready` response. The client handles
server-initiated closing, rejects stale callbacks from earlier sockets, retries
ordinary disconnections and stops retrying rejected/expired credentials. Tap the
connection line → Sign in again to renew the same account without deleting local
history. The server must retain its `TOKEN_SECRET` and PostgreSQL data across
restarts; a successful build cannot establish the live host's configuration.

The updated backend advertises call protocol 2. An older server can still support
basic online text, but the app explicitly reports that calls require a backend
update instead of starting calls that cannot negotiate successfully.

Nearby displays permission, radio, Location and Play-services problems instead of
claiming discovery is running. It retries failed startup/peer requests, rechecks
radio readiness every 15 seconds, and provides manual Retry and settings controls.
Both phones need compatible Rabta versions and distinct accounts. Enable Bluetooth,
Wi-Fi, Location and Nearby permissions and keep Rabta open for the first test.
Bluetooth system pairing alone does not establish a Rabta Nearby connection.
Physical two-phone discovery and delivery still need device testing.
