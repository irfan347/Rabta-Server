# Rabta feature upgrade handoff

2026-10-21. Workspace: `/home/saif/rabta/Rabta-Server`. Branch: `feat/chat-privacy-modes`.

## Scope and authorization

Saif requested messaging gestures/reactions/animated emoji, status reactions, phone collection (explicitly without SMS), Easy/Standard/Developer modes, capture protection, individual chat locking, wallpapers, all-chat and per-chat stars, phone-contact discovery, live username search, unknown-message/call protection, improved voice/video controls, the frozen call-bar timer fix, main-tab swipes, mesh auto-switch preservation, and Render optimization. Latest preference: accumulate meaningful batches, verify them together, and continuously push completed source batches to feature branches.

## Implementation

- Additive server phone_number and phone_discoverable columns. Older clients can omit numbers. Numbers are unverified and private by default. New discovery.js provides authenticated prefix search, opt-in matching, owner-only contact preferences, bounded inputs and shared per-account rate limits. No address-book lookup lists are stored.
- Signup phone input and three experience choices. Easy increases text and simplifies composer. Developer exposes typing/read receipt preferences, animation control, existing custom colors and diagnostics.
- Secure activity windows. Chat lock uses Android biometrics/device credentials, hides locked message previews and notification actions, gates deep-link entry, and rechecks on return. Starred messages and backup export authenticate before exposing locked content.
- Swipe to quote; dedicated React selection action reuses real existing reactions. One-to-three emoji display large with an entry animation honoring Android animation settings. Existing real status emoji replies now animate on acceptance.
- Account/conversation-scoped wallpaper presets. Global or per-chat starred view.
- Contact search debounces requests and ignores stale results; selecting a result asks to add the contact rather than jumping into chat. Phone-contact permission and upload consent are explicit. Unverified phone matches appear as suggestions and require user confirmation.
- Incoming unknown contacts are marked as requests before existing auto-contact-saving runs. Accept/Block/Report gate hides content until accepted. Unknown/pending calls are rejected before ringing. Existing contacts preserved.
- Fixed CallBar: stop canceled ticks, but start previously failed to restart when bar remained visible. CallRefreshLoop now owns restart/cancel behavior and has a regression unit test.
- Voice screen styling and labeled, responsive video-control dock. Main-page horizontal gestures switch Chats/Nearby/Spaces/Calls and preserve vertical scrolling.
- Existing DeliveryCoordinator/Outbox/Nearby protocols preserved. Physical two-phone mesh and calling must still be tested; do not claim those passed from unit tests.

## Render changes already applied and verified

Service `srv-db8bs9e7bikc73b9fe7g`, https://rabta-server-1e2i.onrender.com, repository irfan347/Rabta-Server, main branch.

- healthCheckPath changed from empty to `/health`.
- buildFilter ignoredPaths: `android/**`, `**/*.md`, `tests/**`; paths empty. Backend changes still autodeploy.
- Plan unchanged: 1c-2g; one instance, Oregon. No deployment was manually triggered, no secrets/data changed.
- Live /health returned db:true and push:true. Recent sampled memory ~79–81 MiB; CPU below 0.1 core-percent. No error logs in explicit two-hour window. No justified capacity upgrade.
- API settings verified after update. `/health` currently reports pool presence, not a database SELECT readiness probe.

## Verification and tooling

- Existing baseline backend suite: 77 passed. After discovery tests: 81 passed. Two more registration privacy/validation tests added and targeted server/discovery run: 44 passed. Run full npm test for final total (expected 83).
- Python Android checks: 19 passed before latest UI-only edits.
- First Android compilation caught a typo (`scroll` vs `chatScroll`), fixed.
- Subsequent build compiled app and test sources; new emoji unit test caught skin-tone handling (catalog stores base forms), fixed. Rebuild pending at handoff creation. Logs: /tmp/rabta-build.log.
- Toolchain found in /home/saif/video_maker_app/.tools. SDK 36 added with sdkmanager; Java 17 and existing Gradle 8.13 used. Repository documents Gradle 8.11.1, but 8.13 compiles with its AGP.
- Connected phone: Redmi 10C, adb serial 7a2662bf. Existing production package com.irfan.rabta is installed: DO NOT replace or clear it. Use `.testing` device build only. Initially no com.irfan.rabta.testing package on user 0. Always specify `--user 0` for package queries (work-profile user 10 denies shell access).
- Added `-PrabtaTestBuildType=device` to select isolated instrumentation target. FeatureUpgradeTest covers secure flag, swipe reply, request gating, locked entry, modes/wallpaper and tab gestures. Existing AutoSwitchTest can verify mock-server reconnect/no-duplicate flows.

Build command:

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
ANDROID_HOME=/home/saif/video_maker_app/.tools/android-sdk \
GRADLE_USER_HOME=/home/saif/video_maker_app/.tools/gradle-home \
bash /home/saif/video_maker_app/.tools/gradle-8.13/bin/gradle \
  --project-dir android :app:testDebugUnitTest :app:assembleDevice :app:assembleDeviceAndroidTest \
  -PrabtaTestBuildType=device --no-daemon
```

## Remaining completion steps

1. Finish build and fix only evidenced failures. Read /tmp/rabta-build.log and XML test reports.
2. Run full npm test; git diff --check. Install isolated device APK and its instrumentation APK only if successful. Phone may require its own USB installation confirmation; never bypass it.
3. Run FeatureUpgradeTest and AutoSwitchTest on the isolated package. Report exact successes and unavailable checks. No real-account fixtures.
4. Save a test APK in /home/saif/rabta for Saif. Commit the feature branch and create a draft PR with results and release limits.
5. Do not merge main casually: main both deploys Render and publishes public APK/AAB updates through GitHub Actions. Backend must deploy before relying on new phone/discovery endpoints. Public signing/Play release and two-phone acceptance remain separate from local compilation.

No production app release has been made. No Play Store submission. No repository source rebuild from scratch or account/storage reset.

## Verified checkpoint and expanded feedback

The feature checkpoint now builds successfully: 104 Android unit tests, 83 backend tests, and 19 Python checks passed. Device and device-instrumentation APKs built. Copy: `/home/saif/rabta/Rabta-Test-feature-upgrade.apk`. Physical installation was rejected with INSTALL_FAILED_USER_RESTRICTED; production Rabta was not touched.

Saif subsequently said the usage limit reset and asked to continue with feedback from a ZIP. Two personal screenshots were read outside the repository; never commit these. New work: edit messages, delete for everyone, disappearing messages, view-once media, persistent drafts, hidden locked-chat collection, online/last-seen and profile visibility, status audience shortcuts, blue-tick preference in Privacy, clearer settings categories/profile identity placement. Clarified: “freeze” means hide online/last-seen, not an app hang; left swipe selects and right swipe replies.

## Swipe direction confirmation

Implemented the confirmed physical directions: left selects/highlights a message; right quotes it for reply, including RTL layouts. Selection mode retains tap-to-toggle behavior. Horizontal gesture thresholds and vertical scroll rejection remain in place. Added a left-swipe instrumentation case alongside the existing right-swipe case. Rebuild passed (104 unit tests; device APK and instrumentation APK compiled). Test APK refreshed at `/home/saif/rabta/Rabta-Test-feature-upgrade.apk`; phone instrumentation remains blocked by the previously rejected USB installation. Other screenshot feedback listed above remains pending.

## Phone feedback batch (not built or tested yet)

User requested testing be deferred until a larger batch is ready. Do not install or run tests now. Future test APK destination is the PHONE `/sdcard/Download/rabta/`, not just the computer Downloads directory. That phone folder currently contains the previous `27a4bb7` build, not this batch.

- Emoji picker cells now size with font scale and allow fewer columns on narrow screens; search result cells use the same sizing. Large emoji retain font/fallback padding, and their animation no longer overshoots bounds.
- Locked conversations are excluded from ordinary chats, Spaces, archive/search and unread chat counts. Cached lists are cleared before resume when locks exist. No locked-chat icon/row appears on the main list. Always-available Settings > Privacy & security > Locked chats opens a dedicated device-authenticated screen, hiding identities again on pause. Each chat still enforces its own authentication. Contacts and call history are not hidden by this change.
- App mode moved to the top of Settings. Existing preference signature recreates screens after a mode change.
- Tab swipes translate the page during dragging and settle after release; velocity, boundaries, cancellation, multi-touch, RTL and disabled animations are handled. Floating action button stays stationary. Visual polish is focused on these reported behaviors; broader UI review remains pending.
- This batch has NOT been compiled, run on the phone or tested, per the user’s request. Next testing batch should cover emoji at large text sizes, locked-chat lifecycle/search and swipe interruptions alongside existing regression checks.

## Confirmed phone crash: empty WebSocket close

Read existing adb crash buffer only, without launching apps or running tests. On Oct 21, Rabta Test crashed at 18:48:09, 18:56:35 and 18:56:46; regular Rabta showed the same failure. Exception: `IllegalArgumentException: Code 1005 is reserved and may not be used`, from `WsClient.onClosing` calling `socket.close(code,reason)` on the main thread. An empty peer close frame yields local sentinel 1005, which must not be echoed. Changed that case to reply with 1000 and no reason; existing session rejection handling (1008) and reconnect logic remain intact. The logs identify the client crash, not why the peer closed the socket. No app data changed. Fix committed in source only; APK rebuild/install and regression validation deferred per user. Include an empty-close-frame reconnect regression in the next testing batch.

## Continued batch: drafts and visibility (source only)

- Persistent draft text and quoted reply ID per account/direct-or-group chat; delayed save while composing, save on pause, clear after accepted send. Draft data joins account-deletion cleanup. No draft previews exposed in chat lists.
- Server `GET/POST /api/me/visibility`: boolean hideOnline/hideLastSeen/hidePhoto, defaults false, owner-authenticated, validated, persisted in existing rf_data kind `visibility` before acknowledgment, removed on account deletion. Per-viewer REST lookup and WebSocket presence mask fields. Hiding online also suppresses typing/recording indicators. Actual socket liveness/delivery unchanged. Online watchers receive updated presence on successful changes.
- Android Settings fetches current server settings, offers explicit hide toggles and Rabta freeze shortcut, and only reports success after server acknowledgment. Requests capture the original account credentials. This supports visible-to-everyone versus hidden-from-others; contact-specific visibility is not implemented yet.
- Profile-photo cache scoped by account/server; stale-photo rechecks now notify views when permission removal produces no photo. Existing cached/downloaded copies are not remotely erased; UI explicitly explains that limit.
- Read receipts and typing settings moved out of Developer into Privacy for all modes. Added existing status-audience picker shortcut for FUTURE updates. Screen capture information moved under Privacy; Chats and Appearance are separate sections.
- No build, tests, installation, deployment or push performed for this batch, honoring deferred testing. Phone APK remains the older build with the known close-code crash. Next validation must include persisted visibility reload, failed persistence, anonymous and authenticated lookup, socket watchers, photo owner/other access, account isolation, draft lifecycle, and existing regressions.
- Still pending major screenshot scope: author-validated message edits/deletion for everyone, disappearing messages and view-once media. Do not claim those implemented. Feature branch remains local until review/testing checkpoint; main deploys server and public Android releases.

## Ongoing GitHub sync authorization

Saif explicitly requested pushing the current code and keeping GitHub updated as development continues (24 percent session budget left). Push completed source batches to `feat/chat-privacy-modes`, without merging main or triggering production releases. Testing remains deferred until a larger batch is ready. Keep reporting untested changes separately from the earlier verified checkpoint.

## Oct 22: released baseline and next feature batch

Verified PR #25 and Claude PR #26 were already merged. Main `c34bd3f` successfully published 2.0.341; public update manifest and APK SHA256 matched. Render health reported phone/contact/account-visibility capabilities. User confirms 2.0.341 installed and working; released APK copied to PHONE Downloads/rabta. Do not confuse local test version 2.0.0-test with public version 2.0.341.

New branch `feat/message-edit-delete` starts at c34bd3f. User explicitly asked to remember all progress and preferences; an ad hoc memory note was saved.

### Latest confirmed UI feedback and implementation
- Emoji clipping is in CHAT BUBBLES, not the picker. Bubble bottom sometimes becomes FLAT/CUT OFF (not just a missing tail). Bubble's custom measurement previously reapplied its 86-percent width cap even for the parent's exact second measurement, which can reflow content under an already-fixed height. Respect EXACTLY widths now. Large emoji get wrap-content sizing, measured minimum width and extra glyph-height space. Device regression added; actual visual result still needs phone confirmation.
- Long-press now selects a message and opens a six-emoji reaction popup anchored above it. Removed the dedicated React toolbar/menu action. Left swipe still selects, right replies. Popup dismisses on leaving; reaction icons have fixed visual size with accessible labels.
- Header call icons now larger with tinted button backgrounds. Voice controls 76dp, end control 88x72dp; larger icons. Video controls 64dp with separate rows in portrait and one row in landscape, enlarged icons and labels. No phone calling session run.

### Message editing/deletion implementation
- New message-changes.js records sender, original chat/type and recipient set BEFORE server delivery; record is saved in existing rf_data with no original message body. Only the sender may request changes. Edit window 15 minutes; delete-for-everyone 48 hours, using server acceptance time. Originals before deployment or nearby-only messages are not eligible.
- Durable per-recipient changes remain for up to 30 days. WebSocket only signals availability; authenticated REST fetch/ack stores changes locally before acknowledging. Reconnect/heartbeat fetch catches missed notifications. Latest revision and operation UUID protect retries and stale acknowledgments. Pending edit text removed once everyone acknowledges; account deletion removes related data. Existing online and Nearby protocols preserved.
- SQLite schema 8 adds edited_at and message_changes without dropping data. Author/chat checks, revision order and permanent deletion markers handle changes arriving before originals and prevent late edits from reviving deleted messages. Inbox uses effective edited/deleted content for previews and skips attachment processing for pending deletions.
- Select one own text/reply, then More > Edit message. Edited label added. Single own-message deletion dialog offers Delete for everyone within its window. Local Delete for me remains. Updated apps needed; saved/forwarded copies cannot be erased. Existing backup format unchanged: explicit old backups/copies are not promised remote revocation.
- Backend fixtures updated for new module import; six focused backend tests and four Android database instrumentation tests added. Emoji/bubble device regression added. Core server suite passed 89; Python checks passed 19. Final build/unit-test result pending below. No physical device tests or production deployment for this branch.
- Pending: disappearing messages, view-once media, broader visual acceptance, true two-phone call/mesh testing. No claim those are complete.

## Oct 22: concurrent work inspection

User asked to inspect the friend's latest work before continuing. Remote main is b0b6f7b (merged PR #27), released publicly as v2.0.345 on 2026-10-22. PR #27 keeps latest messages visible when the keyboard, reply bar or sticker panel shrinks the chat; adds StickerBubbleTest and CI coverage. Open PR #28 at ab64026b changes largeEmoji and bubbleSurface measurement, adds animation and tests. It overlaps our local emoji fixes: review and reconcile that PR before merging this feature branch into main. Do not silently overwrite either change or publish a competing emoji fix.

Current local batch: Android unit tests, device APK and instrumentation APK build succeeded (log /tmp/rabta-changes-build.log); backend 89 and Python 19 checks passed. No phone tests or visual confirmation. Preserving batch as a feature-branch checkpoint, then integrating released main. PR #28 remains separate and unmerged.

Released main merged cleanly into feature branch (af7f341). Post-merge unit tests and both device/instrumentation APK builds passed; log /tmp/rabta-post-sync-build.log. Branch pushed to origin. PR #28 head ab64026b has successful build checks but failed interface jobs on API 29, 34 and 36 (Actions run 39732087333); failure cause not established from generic job annotations. Leave it unmerged pending review. No production release, phone installation or physical tests performed.
