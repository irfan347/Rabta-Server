# Rabta Drop

Offline file sharing between two phones that are directly connected in Nearby and whose Rabta identity keys are verified. Photos, videos, PDFs and common documents (Word, Excel, PowerPoint, OpenDocument, RTF, CSV, plain text), up to 4 GB.

## Flow

1. **Sender** picks a file with Android's document picker (`ACTION_OPEN_DOCUMENT`; persistable read permission, no storage permission). Rabta streams it once to compute SHA-256.
2. A **signed offer** (name, type, size, SHA-256, a fresh 256-bit file key sealed with the recipient's pinned RSA key) goes to the recipient as a Nearby `D` control payload.
3. The **receiver sees an approval card and notification**. Nothing is written until the user taps Accept. Offers expire after 2 minutes.
4. On Accept, the sender opens a single Nearby **STREAM payload** of framed chunks. Each 64 KB chunk is AES-256-GCM encrypted with its own nonce and authenticated data (transfer ID, chunk index, last-chunk flag).
5. The receiver authenticates every chunk while writing it to an app-private, no-backup partial file. Then it checks the whole file against the signed SHA-256, and only then saves it to **Downloads/Rabta**. Android 10+ uses MediaStore. Android 8–9 uses the app's own Downloads folder, opened through FileProvider.
6. The receiver replies `done`, so the sender shows **Delivered · verified**.

## Guarantees and limits

- **Who:** only to and from phones connected directly over a link whose identity key matches the pinned key. Offers with a bad signature, a wrong recipient, an unpinned sender or a disallowed type are ignored.
- **In transit:** Nearby's encrypted connection, plus Rabta's own per-chunk AES-GCM. Only the intended recipient can unwrap the file key.
- **Integrity:** any modified, reordered or truncated chunk fails authentication. A whole-file SHA-256 mismatch saves nothing.
- **Memory:** one 64 KB chunk at a time on each side, for any file size.
- **Interruption:** the receiver keeps its verified prefix (whole chunks). The sender's **Retry** sends the same transfer again and resumes from that point. Rabta does not ask again only when this user already accepted the same file from the same sender. A changed source file or a rejected (corrupted) transfer starts from zero.
- **Cancel:** works on either side. It stops the stream and deletes the partial file.
- **Speed:** measured from bytes actually moved over the last 3 seconds. A slow-link hint appears for large files below 256 KB/s.
- **Transport:** files never use the message envelope or the server. Nearby Connections chooses the medium and upgrades a connection to Wi-Fi when the existing strategy and both phones allow it. Rabta keeps the existing `P2P_CLUSTER` strategy so mesh compatibility is unchanged. Rabta reports measured speed and does not claim which radio is in use.
- **History:** stored per account in `drop_<account>.db`, separate from the chat database. Transfers that were active when Rabta stopped are shown as interrupted.

## Verification

- JVM: `DropProtocolTest`, `DropEngineTest`. These cover approval before any bytes, decline, tampering, resume from verified chunks without asking again, sender and receiver cancel, unverified or forged offers, and refused types.
- Device: `DropIntegrationTest`. It uses two real Routers, Keystore identities, FileProvider source and the real Drop screen over a simulated link. It covers approval, byte-identical verified save, and decline.
- Not verified here: real Bluetooth/Wi-Fi throughput, medium upgrade, and physical two-phone transfers.
