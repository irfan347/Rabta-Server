# QR reliability and backend persistence investigation

Date: 2026-10-17 UTC. Work stays on integration/rabta-device-test-20261017; main and production hosting are unchanged.

## Checkpoint and reproduced QR failure

Before this work: 113c3b0dc40252429968dfa08c52012bfd00c255. Remote source checkpoint: checkpoint/rabta-before-qr-fix-20261017.

The initial integration APK built successfully, but API 34 had a ZXing NotFoundException in pairingReviewRendersRealQrAndRequiresUserConfirmation. One unchanged-source rerun failed instead in actualQrImagesRoundTripAndOneTimePairingPersists with the same exception. These shared the ordinary image detector; neither failure was discarded or suppressed.

A new JVM regression test reproduces the failure independently of Android, cameras, live users and accounts. [Red run 39011918567](https://github.com/irfan347/Rabta-Server/actions/runs/39011918567), revision c870f4b16b451ac92922112f31bdc077f3d90bc7, confirms the scanner resolves com.google.zxing:core:3.4.1 and fails decoding a generated 800px signed confirmation image, sample 0. The exact public record is preserved in test resources as qr-regression.txt. It has fixed test identities and expired in 2023; it contains no private key or login token.

## Focused candidate fix and coverage

The dependency-only 3.5.3 candidate failed both the fixed and varied-image regressions in run 39012170116. It was not accepted as a fix; the dependency pin was removed, preserving the original dependency versions.

TRY_HARDER alone also failed in run 39012465191. Test-only phase diagnostics in run 39012674773 isolated the failure: binarization exactly matches the generated raster and an exact-raster check decodes it, while the normal detector mistakes data for the bottom-left finder, samples a 109-module grid instead of the correct 117, and reports FormatException. MultiFormatReader hides that as NotFoundException. Rotating the same raster makes ordinary finder detection succeed. That diagnostic exact-raster mode is never used in production or final pass assertions.

The focused fix supplies InstantQrDecoderFactory to the existing BarcodeView. It first uses the existing normal QR detector. Only after failure with at least two candidate points does it try up to three quarter-turn orientations with normal detection. Points and result metadata are preserved/remapped to the original frame, including non-square frames. Recovery is limited to at most one sequence per 500ms and one-megapixel frames; blank frames keep the fast path. No PURE_BARCODE, supplied finder locations, unbounded retry or alternate authentication path. The original ZXing dependencies and camera lifecycle are retained.

There is no invitation format, signature, expiry, replay-cache, key approval, authentication, encryption, message, database or backend implementation change. NFC still exchanges the same public invitation text.

Coverage added:
- Decode the preserved confirmation using default selection and each of all eight QR masks, at 800px and 900px in all four orientations (66 images including two default-mask renders). This avoids relying on a fortunate default mask.
- Generate 32 real signed offer/reply/confirmation exchanges, with ASCII and Urdu fixture names; decode all three stages at both existing raster sizes (192 images).
- Both use the actual production scanner factory, with its bounded ordinary-detection orientation recovery. No PURE_BARCODE, supplied finder points or test-only success fallback.
- The existing Android confirmation test additionally reads and decodes the actual 800px BitmapDrawable rendered by the production Activity. Existing key-approval assertions remain.
- Tests also exercise non-square rotated frames, finder-point mapping, recovery throttling and blank-frame rejection.
- Existing expiry/replay/mismatched-key/account/history/mesh tests remain in the complete suite.

Build, JVM and API 29/34 outcomes and the actual APK artifact are recorded in testing PR #5 once they complete. This document does not claim a green candidate run in advance. Physical camera scans, lighting, glare and cross-device display sizes still require two-phone checks.

## Read-only persistence diagnosis

A fresh CI health observation at 06:59:47 UTC returned HTTP 200, ok:true, persistent_database:false. It did not authenticate, create an account, query user data or change the host. The configured URL remains https://rabta-server-1e2i.onrender.com.

At the inspected source, index.js creates a PostgreSQL pool only if process.env.DATABASE_URL is truthy. /health returns db:!!pool, not the result of a SQL readiness query. A configured but unreachable pool makes startup initialization reject before listening; db:false is therefore consistent with the memory-mode path, rather than evidence that database contents are corrupt. The current deployed revision cannot be established from that health response, so this source-based explanation is conditional on matching deployed code.

Without a pool, accounts and pending messages use process memory, and features use in-memory state. TOKEN_SECRET also falls back to a per-process random value if absent. A restart or changing configuration before preserving this state could lose access to existing records or invalidate sessions. Do not restart, redeploy, create replacement accounts, rotate a secret or attach a fresh empty database as an assumed repair.

## Actual access blocker and next safe check

No Render management connector or configured hosting credentials are available in this execution environment. The user does not yet know whether PostgreSQL or backups exist. No hosting mutation, migration, database creation, data export/reset or credential rotation was performed.

On Render, open the existing Rabta-Server service matching the configured URL, then Environment:
1. Check whether the names DATABASE_URL and TOKEN_SECRET exist and are nonempty. Do not share their values.
2. Check whether an existing PostgreSQL service is associated, whether it contains the current Rabta tables/accounts, and whether a current backup exists. Use authorized read-only checks; do not create a new database to mask missing records.
3. Confirm the deployed branch/revision before attributing the health response to this source.
4. With management access and a verified preservation/backup plan, investigate missing/wrong service variables or a deployment mismatch. If runtime records are memory-only, preserving them must precede any restart or switch to PostgreSQL.

The only information needed in chat is variable-name presence, database/backup status and deployment revision. Never send a database URL, account token, PIN, secret or private key.

Live TURN provisioning and physical call/mesh tests remain separate, unverified work. No main merge or production release is part of this fix.
