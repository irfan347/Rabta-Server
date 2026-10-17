# Rabta integration/testing audit — 2026-10-17

Initial integration checkpoint: 113c3b0dc40252429968dfa08c52012bfd00c255. The subsequent authorized QR reliability fix and read-only backend findings are recorded in [QR_PERSISTENCE_DIAGNOSTICS.md](QR_PERSISTENCE_DIAGNOSTICS.md); the audit below describes the initial integration snapshot.

This branch is for device testing only. No merge into main, production deployment, data reset, redesign or new application feature is part of this integration.

## PR inventory and dependency resolution

All repository PRs (open and closed, all authors) were searched; four exist at this audit checkpoint.

| PR | State at audit | Head | Integration decision |
| --- | --- | --- | --- |
| #1 — Rabta 1.9 | Merged, closed | cd97a64b789abbe3642caa2a2f52ef3554d5cd18 | Already in main d7c480a8e169013935435995141de5ba36277aa6; do not reapply. |
| #2 — Repository audit | Open, not draft; GitHub mergeable | ff9092a5d52cf13a9bbee0bc41627258dff5d63c | Only AGENTS.md and RABTA_ROADMAP.md. Both contents already preserved in #4. Join this parent history while retaining the expanded roadmap. |
| #3 — Mesh branding | Open, draft; GitHub mergeable | 378266076b850471731c73614ad9dfa5fd16b71c | Exact ancestor of #4, 26 commits behind. Included once, in original order. |
| #4 — Cumulative UI/connectivity | Open, draft; GitHub mergeable | a1bfd2fa439428f9b4d9335321c3eb20a657812e | 28 commits ahead of main, zero behind. Contains the subsequent development steps and Network Vision. Preserve complete source tree and ancestry. |

There are not ten separate PRs. The ten requested steps map to: repository audit (#2, copied into #4); branding (#3, ancestor of #4); design system, home, conversation, remaining screens, mesh audit, Radar, Instant Connect and Calls search (all in #4). Network Vision is also included in #4.

PR #2 diverges by one documentation commit from #4. AGENTS.md has identical blob SHA 6a086c0fc4e3f764edb92e4518fdeaf510cea05c in both. Every nonempty line of #2's roadmap is retained in #4, with three later follow-up links added. Resolve the duplicate additions by retaining #4's documents. No runtime code conflict exists. No PR changes are discarded.

Order: existing merged #1 baseline → #3 branding → cumulative #4 development history; reconcile the already copied #2 documents with an explicit additional merge parent. No redundant cherry-picks or out-of-order code replacements.

## Safety

- Remote checkpoint: checkpoint/rabta-before-integration-20261017 at a1bfd2fa439428f9b4d9335321c3eb20a657812e.
- Integration branch: integration/rabta-device-test-20261017.
- Main baseline: d7c480a8e169013935435995141de5ba36277aa6.
- Existing application files are retained byte-for-byte from #4; this integration changes only this report and build-artifact verification in CI.
- The local workspace is a selected-source editing cache, not a full checkout. Remote tree construction uses the complete upstream commit as its base. A full checkout is built by GitHub Actions.
- Source checkpoints are not production-data backups. No production accounts, databases, sessions, keys, servers or app storage are accessed or changed.
- Original PRs and their branches remain unchanged and open as applicable. The separate testing PR must not be merged into main automatically.

## Build system and validation

Native Kotlin / programmatic Android Views; android/ is the Gradle root, single :app module. AGP 8.5.2, Kotlin 1.9.24, Gradle 8.7, Java 17, minSdk 26, compile/target SDK 34. No committed Gradle wrapper. Application ID com.irfan.rabta; version 1.9.1 (33), unchanged. Existing dependencies, authentication, SQLite versions and message protocols are reused.

Existing CI runs 17 Python checks, backend tests, JVM tests and :app:assembleDebug. PR validation additionally runs the complete instrumentation suite on API 29 and 34. This testing branch adds APK signature/package verification, byte size, SHA-256, signing-certificate evidence and source-ancestry assertions. Reports are saved beside the installable Debug APK in Rabta-debug-apk.

Expected APK path in the CI checkout: android/app/build/outputs/apk/debug/app-debug.apk. Actual existence and test outcomes must be verified from this branch's run and artifact; this document alone is not a success claim.

## Existing limits, not integration regressions

- Local Gradle/SDK unavailable and shell clone could not complete; complete-source build uses CI.
- Physical two-phone Bluetooth/Wi-Fi discovery, offline delivery, calls/ringtones, camera QR, NFC and three-phone carry/relay still require device validation. Emulator simulated transports do not certify physical radios or live TURN.
- TURN templates/configuration exist, but no live TURN service has been provisioned. Current deployed backend revision/persistence is not established by a successful APK build.
- Profile name/username editing lacks APIs; photo is local. Status media opens externally. Pre-Android-12 splash is not explicitly branded. Some call controls retain older styles. These are not silently implemented or removed here.
- Offline text/bundled stickers are supported; offline media/groups/status/calls and an independent raw Wi-Fi Direct transport are not implemented.
- Verified remote relay topology, per-peer radio RSSI/RTT and distance are unavailable, and must not be inferred from the Network Vision animation.
- Historical baseline connection-test setup failures were fixed in #4. A randomized QR image-decode test previously flaked once; unchanged-head rerun passed. Report fresh failures honestly.
- CI debug signing may differ from an APK already on a phone. Verify certificate compatibility before updating an existing installation; do not uninstall or clear storage to work around a mismatch.

## Device testing

Download Rabta-debug-apk, extract app-debug.apk and compare its SHA-256 with APK-VERIFICATION.txt. Test an in-place update only if Android accepts its existing signing identity. Keep existing accounts and data intact. Validate navigation/Status, actual search records, chat/retry/attachments, calls on separate networks and explicit opt-in nearby pairing on two compatible phones. Use a third phone for relay/carry verification. Record Android versions, permissions, connection states and failures without exposing private messages or tokens.
