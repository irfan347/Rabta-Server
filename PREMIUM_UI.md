# Rabta premium UI implementation

## Baseline and boundaries

The inspected source is the full 136-file integration tree at `8a3883fb8511b6ff0b877143da57fbb1f3c4ed15`. All downloaded blobs matched their Git hashes. The unchanged source has a local checkpoint and remote branch `checkpoint/rabta-before-premium-20261017`. Changes are on `redesign/rabta-premium-20261017`; neither main nor the integration branch is merged or deployed by this work.

The app uses native Kotlin Android Views, programmatic Activities, `ThemedActivity`, shared `RabtaUi` components, local SQLite, singleton services and `RabtaEvents`. Android uses AGP 8.5.2, Kotlin 1.9.24, Gradle 8.7 and SDK 34. No UI framework or app dependency has been added. The existing four destinations are Chats, Nearby, Spaces (real groups) and Calls; Status stays reachable from the header.

REST/WebSocket contracts remain unchanged. Authentication, account IDs, SQLite v5, mesh storage, Android Keystore identities, message wire formats, outbox/inbox, Nearby approvals and server schemas are preserved. No production account, data or configuration was modified.

## Changes

- Theme tokens: `#0B1020` background, `#171D30` surface, `#8F74F8` primary and `#35C9BC` highlight. Dark primary buttons use graphite labels for readable contrast. Light/System choices and existing saved choices remain available; unspecified appearance defaults to Dark. Native typography, 8dp rhythm, compact borderless cards, smaller empty states and shared dialogs remain in the current framework.
- The persistent connection-service notification uses a dedicated LOW channel, so opening Rabta does not show a startup heads-up banner over the interface. Incoming message/call channels and their existing user settings remain separate.
- Chats/Spaces/Calls: compact header with real connection state, concise lists, working search clear action, reduced navigation height and shared theme. The formerly visible empty conversation-search container on Nearby is removed. Existing call search, call picker, callback, groups and message interactions are retained.
- Nearby: compact radar, inline case-insensitive filtering of actual discovered display names/IDs, clear/no-results/back behavior and restored query state. Opt-in and explicit approval/key checks are retained. Secondary QR/radio tools move into an overflow menu; diagnostics stay accessible through tools and Settings. Programmatic opt-in synchronization no longer restarts discovery. Radar paints at most 6.25 frames/second and stops offscreen/in power-saving mode.
- Profile: circular 72dp avatar, compact account sections, copyable actual ID and local photo picker. Rounded clipping is preserved when falling back to initials. No request is made to the nonexistent profile-photo upload route.
- Status: actual owners in the story strip, real text/media previews, bounded lazy thumbnail work and memory cache, explicit private audience selection, contextual errors/retry, existing own-status management and an in-app viewer. The viewer fetches the audience-filtered feed and acknowledges viewing before showing content, rechecks on resume, clears media on pause, and removes content at its server-provided expiry. No expired record becomes a preview or selectable update. Video uses native playback controls. The existing external media-opening flow is retained as a fallback for formats the phone cannot decode, with fresh audience/expiry authorization before opening. The audience picker no longer combines a message with a multi-choice list, which can hide the choices in native AlertDialog.
- Network Vision: live observations only, 12-node pages for large networks, bounded pinch/button zoom and panning, keyboard selection, accessible peer rows, theme-aware graph and concise actual counters/events. Remote relay topology and distance are never invented. Signature-checked relay counters are not presented as proof of a route.

## Verification

Local checks: 17 Python database/project tests passed; backend test files passed; `git diff --check` passed. Local Android tooling is unavailable (`gradle: command not found`); full Android compilation, JVM tests, lint and API 29/34 instrumentation run in the existing GitHub workflow. Current CI results are recorded in the pull request after completion, not inferred from source review.

Initial emulator feedback also exposed an outdated navigation expectation for the QR action (now in the Nearby overflow), an API 29 service-fixture lifecycle race, and scoped-storage screenshot export. Tests now follow the redesigned navigation, wait for the real lifecycle before attaching an isolated transport fixture, and export screenshots through instrumentation without adding production storage permissions.

New tests exercise Nearby filtering against observed fixture peers, clear/back behavior, copyable profile identity and circular clipping, authenticated image previews, private status creation, cached-media denial after audience revocation, retry/expiry, and graph paging/keyboard selection. Existing call, conversation, authentication, QR/NFC, mesh and accessibility tests are retained. Fixtures and local test servers are isolated from production. Captures are actual emulator renders, labeled test data; they are not product mockups.

Initial validation found existing lint errors in raw Toast/font constants, sticker tab IDs, a reserved notification importance, and implicit debug loopback domain matching. These receive focused fixes: named constants/resource IDs, supported HIGH call-channel importance with the existing channel ID, and explicit `includeSubdomains="false"`. No notification channel is deleted or recreated. Lint now fails CI on errors; findings are printed and reports uploaded.

## Existing blockers and device verification

- The current server has no display-name/username edit API or profile-photo sync API. Name/username are shown as read-only with an explanation; changing the photo is explicitly local. Implementing server-wide editing would need a separate backend rollout and persistence check.
- Last observed live health reported `db:false`; the user reports missing database configuration. Existing backend RAM accounts must be preserved before any restart, storage migration or deployment. This UI change does not fix or conceal that operational blocker.
- No live TURN relay is confirmed. Physical two-phone Bluetooth/Wi-Fi, approval, background delivery, audio/video calling and NFC still require actual devices. Emulator transport fixtures do not prove radio range, quality or multi-hop delivery.
- Status remains an online feature with existing 8MB attachment limits. The server remains authoritative for audience and 24-hour expiration. This change adds no offline status queue or fake presence.
- Debug signing certificates from different ephemeral CI runners may differ. Updating an installed APK requires the matching private signing key; never uninstall/clear the original app to work around an incompatible signature.

## Modified files

- `.github/workflows/main.yml`
- `PREMIUM_UI.md`
- `android/app/src/androidTest/java/com/irfan/rabta/DesignSystemTest.kt`
- `android/app/src/androidTest/java/com/irfan/rabta/InterfaceTest.kt`
- `android/app/src/androidTest/java/com/irfan/rabta/PremiumUiTest.kt`
- `android/app/src/debug/res/xml/debug_network_security.xml`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/java/com/irfan/rabta/AppLock.kt`
- `android/app/src/main/java/com/irfan/rabta/AppTheme.kt`
- `android/app/src/main/java/com/irfan/rabta/ChatActivity.kt`
- `android/app/src/main/java/com/irfan/rabta/CompactSearchView.kt`
- `android/app/src/main/java/com/irfan/rabta/ConversationListActivity.kt`
- `android/app/src/main/java/com/irfan/rabta/FeatureApi.kt`
- `android/app/src/main/java/com/irfan/rabta/FeatureUi.kt`
- `android/app/src/main/java/com/irfan/rabta/IncomingCallActivity.kt`
- `android/app/src/main/java/com/irfan/rabta/MessageActions.kt`
- `android/app/src/main/java/com/irfan/rabta/MessageService.kt`
- `android/app/src/main/java/com/irfan/rabta/NearbyActivity.kt`
- `android/app/src/main/java/com/irfan/rabta/NearbyPanel.kt`
- `android/app/src/main/java/com/irfan/rabta/NearbySearch.kt`
- `android/app/src/main/java/com/irfan/rabta/NetworkVisionActivity.kt`
- `android/app/src/main/java/com/irfan/rabta/NetworkVisionView.kt`
- `android/app/src/main/java/com/irfan/rabta/ProfileActivity.kt`
- `android/app/src/main/java/com/irfan/rabta/RabtaUi.kt`
- `android/app/src/main/java/com/irfan/rabta/RadarView.kt`
- `android/app/src/main/java/com/irfan/rabta/SettingsActivity.kt`
- `android/app/src/main/java/com/irfan/rabta/StatusActivity.kt`
- `android/app/src/main/java/com/irfan/rabta/StatusMedia.kt`
- `android/app/src/main/java/com/irfan/rabta/StatusViewerActivity.kt`
- `android/app/src/main/java/com/irfan/rabta/Stickers.kt`
- `android/app/src/main/java/com/irfan/rabta/UiIcons.kt`
- `android/app/src/main/java/com/irfan/rabta/VideoCallActivity.kt`
- `android/app/src/main/res/drawable/ic_launcher_bg.xml`
- `android/app/src/main/res/drawable/rabta_dialog_dark.xml`
- `android/app/src/main/res/values/colors.xml`
- `android/app/src/main/res/values/ids.xml`
- `android/app/src/main/res/values/styles.xml`
- `android/app/src/test/java/com/irfan/rabta/NearbySearchTest.kt`
