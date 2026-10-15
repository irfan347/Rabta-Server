# Rabta 1.8

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
  stickers up to 512 KiB. These are not WhatsApp-owned sticker packs.
- Online attachment transport preserves full bodies and checks file SHA-256 on
  receive. Failed messages can be retried with the same ID.

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

Both ends and relay phones need V1.8. Legacy plaintext V1 packets and the old
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
Existing `package (1).json` / hosting start configuration is unchanged. Video calls
still need a working network path; the existing WebRTC client has no configured
TURN relay, so some NAT/carrier combinations will not connect.

## Validation

- Python tests use production project-locator and SQL queries, including durable
  carry queue persistence, replay IDs and expiry selection.
- JVM tests cover monotonic receipts, expiry/hop boundaries, hybrid crypto round
  trips, wrong keys, modified routing metadata and tampered signatures.
- Node tests exercise actual server handlers with in-memory transport/DB stubs:
  large attachments, sequential dedup, limits, video participants and group metadata.
- An Android emulator test captures real light/dark chat/list screens, attachments,
  stickers and incoming calls; it checks avatar sizing and startup focus.

CI status: https://github.com/irfan347/Rabta-Server/pull/1
Real Bluetooth/Wi-Fi relay delivery, process death, expiry over time, large-file
sending and calls must also be tested on physical phones before release.
