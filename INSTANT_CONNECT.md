# Rabta Instant Connect

Instant Connect uses existing Kotlin/Views, ZXing, Android Keystore identities, pinned contact keys and private chats. It does not change authentication, message IDs, schemas, backend APIs or the encrypted offline protocol. Opening it does not enable Radar discovery. Source checkpoint: `checkpoint/pre-instant-connect`.

## Offline QR pairing

1. A selects **New temporary invitation**. The signed offer contains only the public Rabta ID, display name, public identity key, random 256-bit nonce, timestamps and protocol purpose/version. It expires in three minutes.
2. B scans and reviews A's ID/fingerprint, then explicitly confirms to generate a signed reply bound to that exact offer. B shows the reply to A. B has not saved A's contact key yet.
3. A scans the reply, compares the displayed pairing code with B and explicitly approves. A durably consumes the invitation before pinning B's key and signing the final confirmation. Only one reply can be accepted by the issuer, including after restart.
4. B scans A's confirmation and explicitly confirms the identity. It must match B's locally pending signed reply. B consumes the pairing before pinning A's key, then opens the existing private chat. A's confirmation screen also offers the existing private chat, while leaving the final code visible for B.

The extra confirmation is intentional: a signature on a copied QR alone cannot establish that an offline invitation is still unused. The issuer enforces one-time use and signs the chosen reply. Another scanner cannot use that confirmation for its own different reply. Neither side treats merely scanning a QR as pairing or radio connectivity.

## Security boundaries

- All fields are signed with the existing RSA identity key, with an Instant Connect signing domain distinct from mesh envelopes. Canonical bounded binary encoding prevents extra fields/trailing data/alternate Base64 representations from changing interpretation. RSA public keys must be 2048 bits, signatures 256 bytes, codes at most 4096 characters.
- Reply and confirmation hashes bind both identities, nonces and the exact signed offer/reply. The human comparison code is derived from both signed records. No password, private key, phone number or persistent authentication token is serialized.
- First contact still requires trusted human verification. A valid self-signature proves control of the included key, not a server-certified account identity. Previously pinned keys must match; changed pins and blocked users are rejected. Pin writes are now serialized across helper instances so two concurrent first-contact flows cannot overwrite each other.
- Account-specific private preferences retain the active offer/pending reply, consumed-offer hashes and the last local pairing summary. Expired replay entries may be pruned only after the persisted clock floor has advanced past their expiry. Rolling the phone clock backward cannot revive already-observed expiry. Correct device time remains required; there is no new trusted-time server.
- Creating a fresh invitation revokes an outstanding unconsumed offer. Cancelling does not delete chats, keys, accounts or replay records. A signed confirmation already issued to a chosen peer remains valid for that pairing until expiry; offline copies cannot be remotely withdrawn.
- A failed save consumes or rejects the flow safely and never reports pairing success. The UI hides a confirmation if its key/paired state was not durably saved; a new invitation may be required. There is no destructive account/key reset.
- Once paired, messages use existing online or explicitly approved Nearby transport. Pairing does not assert a radio connection, TURN availability or online end-to-end encryption. Existing online transport protection and encrypted mesh are unchanged.

The existing permanent contact QR/import remains available and is explicitly labeled legacy. New Instant Connect codes encountered by the old scanner route to the new review flow.

## QR verification

JVM tests cover signed three-step records, Unicode names, exact expiry/future-time bounds, tampering, canonical encoding, cross-invitation/cross-recipient rejection and nonce uniqueness. Android tests use real Android Keystore and persistent preferences, encode/decode actual ZXing QR images, reopen consumed invitations, test competing replies/revocation, reject changed keys/blocked users, enforce the clock floor, race pin writes, require UI confirmation and verify existing history/session survives opening chat. The current PR records actual CI outcomes. Local Gradle/SDK are unavailable; CI supplies the existing toolchain and API 29/34 emulator jobs.

Physical tests remain required: two phones in airplane mode, camera focus under different lighting, screen rotation/process restart at each step, expired/replaced/copied codes, comparison-code mismatch, cancel/reject, changed keys, and preserved messages after pairing. Use disposable test accounts; do not clear production storage. Pairing can succeed offline without a current messaging link; validate later message delivery separately with an approved Radar connection.

NFC is a subsequent optional phase after the QR build/tests. Modern Android has no general Android Beam phone-to-phone exchange; support must use actual reader/card-emulation capabilities and transfer only these bounded public pairing records.
