# Rabta AutoSwitch

One conversation, whichever transport can carry the message right now. Users never switch chats or modes.

## Transports (existing, unchanged on the wire)

| Transport | Carries | Custody / proof signal |
| --- | --- | --- |
| Server WebSocket | all message types, 1-to-1 and groups | `ack` `stored` / `sent` / `delivered` = the server holds it or wrote it to the recipient's socket |
| Nearby store-carry-forward (`Router`) | 1-to-1 text and validated stickers, RSA-OAEP + AES-GCM sealed, signed | recipient-signed `receipt` envelope |

Relays carry ciphertext only. The unsigned hop field means relay paths are **not verifiable**, so AutoSwitch never claims "Relayed" for Nearby; a message in the Nearby carry queue is Queued until the recipient's signed receipt arrives.

## States

| State | Stored value | Set only when |
| --- | --- | --- |
| Queued | `queued` (legacy `mesh`, `mesh_wait`) | saved on the phone; no transport has accepted it |
| Sending | `sending` | written to the server connection, ack pending |
| Relayed | `relayed` (legacy `sent`) | server ack `stored`/`sent`/`delivered` — the server has it; not recipient proof |
| Direct Delivered | `direct_delivered` | recipient-signed Nearby receipt received from the recipient's own key-verified direct link |
| Delivered | `delivered` | recipient app's receipt via the server, or a recipient-signed Nearby receipt that arrived through other phones |
| Failed | `failed` | permanent server error with no Nearby copy, or still queued after 24 hours |

Existing `read`, `paused`, `canceled` and `expired` are kept. States only move forward (Queued/Sending → Relayed → Delivered → Read); late or out-of-order acks never downgrade. History rows are not rewritten: legacy `sent`/`mesh` values are read and labelled with the new names. Earlier versions labelled a server socket write "Delivered"; those stored rows keep the value they were saved with.

## Delivery receipts (additive protocol)

- After `Inbox` stores an incoming online message — or finds an earlier copy with the same ID — the app sends `{"type":"receipt","to":<sender>,"id":<msgId>}`.
- The server forwards `{"type":"receipt","id","by"}` to the sender, or keeps it in `ru_receipts` (memory without PostgreSQL) until the sender reconnects; it is removed only after the frame is written.
- The sender accepts a receipt only from the conversation's own peer (not for groups).
- `ready` now includes `"receipts": true`. Older apps ignore the new frame and keep receiving the same `ack` values; older servers ignore `receipt`, so new apps against an old server stop at Relayed rather than overstating delivery.

## Coordinator (`DeliveryCoordinator`)

- Every attempt reuses the message's original ID; the server's offline store, the Nearby store and the recipient's inbox all drop repeats.
- Online whenever connected. Nearby for 1-to-1 text/stickers when the server is unreachable, and in parallel when the recipient is directly connected over a key-verified Nearby link.
- Connection lost before the ack: back to Queued and offered to Nearby at once. Server ack missing after 30 s: back to Queued and retried on every transport after the backoff.
- Retries: immediately on reconnection or a new Nearby key exchange, otherwise backoff 5 s, 10 s, 20 s … capped at 5 min. Sends interrupted by the app stopping are queued again on next start.
- Media and group messages wait for the server (Nearby has no media/group protocol).

`Outbox` connects the coordinator to `Db`, `WsClient` and `MeshBridge` on one worker thread.

## Verification

- `DeliveryCoordinatorTest` / `MessageDeliveryTest` (JVM, fake transports and clock): offline queueing, reconnection, disconnection before ack, server→Nearby switching, parallel direct Nearby, ack timeout and backoff, out-of-order evidence, forged receipts, deduplication, media/group limits, max age, legacy rows.
- `AutoSwitchTest` (device, real `Outbox`/`WsClient`/`Inbox`, localhost server): Queued → Relayed → Delivered with no resend after reconnecting; connection lost before ack resends the same ID once; forged and late evidence never overstates; duplicate incoming copies stored once and each confirmed.
- `tests/server.test.js`: receipt relay, offline receipt storage and single delivery, invalid/blocked receipts, database persistence.

Not verified here: real phone-to-phone Bluetooth/Wi-Fi switching, multi-hop relay, background delivery with the app killed. Emulator Nearby uses simulated transport.
