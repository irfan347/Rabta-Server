# Rabta feature upgrade handoff

2026-10-21. Workspace: `/home/saif/rabta/Rabta-Server`. Branch: `feat/chat-privacy-modes`.

## Scope and authorization

Saif requested messaging gestures/reactions/animated emoji, status reactions, phone collection (explicitly without SMS), Easy/Standard/Developer modes, capture protection, individual chat locking, wallpapers, all-chat and per-chat stars, phone-contact discovery, live username search, unknown-message/call protection, improved voice/video controls, the frozen call-bar timer fix, main-tab swipes, mesh auto-switch preservation, and Render optimization. User has warned only 8% of the session usage remains: finish essential verification, avoid optional expansion.

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

## Emoji-first hotfix after phone feedback

User prioritizes clipped chat emoji before all other features. Created fix/emoji-clipping from released main b0b6f7b (v2.0.345). All message-edit/delete and call UI work remains preserved on feat/message-edit-delete. Adopted only PR #28's layout fix: largeEmoji WRAP_CONTENT and mixed full-width/wrap-content bubble children measured by widest content. Added EXACTLY guard to avoid shrinking a second measurement. No floating-animation changes included. Existing panel scroll fix retained. EmojiClippingTest is the glyph containment and panel visibility regression from PR #28, without its animation test or screenshot capture.

Phone reconnected as 7a2662bf; installed production version verified 2.0.345. No com.irfan.rabta.testing package found by dumpsys. Copy the isolated Rabta Test APK to phone Downloads/rabta; do not replace production or clear any data. Physical visual confirmation remains pending. Python checks: 19 passed; Android build in /tmp/rabta-emoji-fix-build.log. Do not resume other feature work before addressing emoji acceptance.

Emoji fix validation completed: 104 JVM tests and 19 Python checks passed; device and instrumentation APK builds succeeded. Copied `Rabta-Test-emoji-fix-98b9e27.apk` to phone `/sdcard/Download/rabta/` and computer `/home/saif/Downloads/rabta/`; both SHA256 `9d1daa3779c04749ca30aef7723382676a93b10e8dfa60356ceaa46e43946fdc`. APK copied only, not installed. Device regression compiled but was not executed. User must install Rabta Test and confirm whole emoji; production stays 2.0.345. Source pushed on fix/emoji-clipping; no main merge or public release.

## Oct 22: chat and voice-call polish after emoji acceptance

Saif said the emoji fix was done and requested continued work using the new WhatsApp reference ZIP. Seven reference screenshots were inspected outside the repository (feedback-2026-10-22); these contain private conversation/account information and must not be committed. Branch feat/chat-call-polish starts from the isolated emoji fix; feat/message-edit-delete remains separate.

- Long-press opens an anchored six-emoji reaction strip plus a full-picker button; removed dedicated React toolbar/menu actions. Selection/swipe behavior retained. Subtle 160ms strip entrance, no particle effects.
- Emoji motion reduced to a short, contained 220ms scale entrance; old-history redraws do not animate; tapping an unselected emoji replays it. Picker has a drag handle, larger readable glyphs, tighter headings and rounded segmented tabs. System emoji artwork remains.
- Added a 44dp bottom-right Jump to latest message button in the chat viewport, shown only away from the bottom; stays above composer/keyboard. Added isolated device regression. Larger voice call controls and header call actions carried over without the unfinished edit/delete database/API changes.
- Voice calls distinguish accepted signaling from first audio receipt. Accepted calls survive local connection loss and missing media, show Reconnecting, and wait up to 120 seconds; only resumed media clears recovery, not merely WebSocket reconnection. Timer remains based on elapsed realtime. Unanswered calls retain their existing timeout. Outdated audio callbacks cannot change a later call.
- Server preserves answered calls for 120 seconds (was 30), avoids extending an existing away deadline repeatedly, and returns a separate busy reason for an active Rabta call. Old clients still receive state busy. Blocked/unavailable calls do not expose that reason. New client shows Already on another call; voice screen gives a clear dialog. Does not detect calls inside other applications.
- Video signaling grace matches 120 seconds, but WebRTC ICE-failure recovery remains unchanged; do not claim video transport recovery is solved.
- Server health advertises call-recovery-120s. Both new server and clients are required for the full recovery window; existing production server can still end calls at its older cutoff. No deployment or main merge performed.
- Full backend suite: 84 passed; Python checks: 19 passed. Initial Android build/unit checks passed; final incremental build log /tmp/rabta-polish-final-build.log. No two-phone call test or device instrumentation run. Copy APK to phone Downloads/rabta after final build, without installing or changing production data.

Final polish build succeeded: 104 JVM tests, device APK and instrumentation APK. Phone and computer copy: Rabta-Test-chat-call-polish-c6efc07.apk, under their respective Downloads/rabta folders. No install or device test performed. Draft PR #29 contains this batch and the emoji fix: https://github.com/irfan347/Rabta-Server/pull/29. No public release; two-minute recovery and precise busy reason need the new server deployed. User should first confirm UI in the isolated test APK, then validate calls on two updated phones after server rollout.

## Storage optimizations and simultaneous calling

User requested engineering/reliability improvements, stack/database explanation, simultaneous-call handling and a complete PR description. Verified Kotlin/native Views, Node/Express/ws, per-account phone SQLite, server PostgreSQL. Live health returned db=true; no database credentials accessed.

Added access-ordered profile cache capped at 128 entries and 8 MiB, preserving photo revalidation/request coalescing and invalidating stale callbacks after clear. Enabled SQLite WAL; no schema bump. Added PostgreSQL pending-message and receipt indexes matching current reads. Existing encrypted manual backup remains; no standby, backup-retention guarantee or automatic failover was provisioned. See docs/ARCHITECTURE_AND_RELIABILITY.md for the actual architecture and multi-instance limits.

Crossed calls: first server-accepted call wins; later outgoing attempt ends with a crossed-call reason and the existing incoming call is redelivered for explicit answer. Works across voice/video combinations; no automatic answering. Duplicate identical call initiation preserves the existing session instead of returning busy. New backend tests pass; two cache unit tests added. Full backend suite now 86, Python 19; final Android log /tmp/rabta-optimization-build.log. PR #29 to be rewritten around full combined scope.

Verified after budget clarification: DATABASE_URL points to Neon PostgreSQL pooled endpoint; inspected in memory without logging/storing credentials. User has $200 Render credits and no other budget (reported, not account-balance verified); explicitly saved this constraint to memory. No paid services/plans changed. Final optimization validation: 86 backend, 106 JVM, 19 Python passed; both Android APK builds succeeded. Main advanced through PR #30 merging the emoji hotfix; merged origin/main into this branch cleanly with no source delta. PR #29 rewritten with full UI/call/cache/storage scope, test evidence, budget and rollout limitations. Neon plan, credit expiry/balance and provider backups remain unverified.

PR #29 latest CI at 8039739: build passed; interface jobs on APIs 29/34/36 each had the same two failures (104/106 device cases passed). ConversationTest caught a real regression: long-press cleared existing selection. Fix preserves/toggles multi-selection and opens reactions only when entering selection. CallsScreenTest expected recovery after signaling alone; mock now explicitly verifies Reconnecting after call_alive, then resumes audio before asserting Connected. Full CI must be rerun on the fix before claiming green. Render settings read-only verified: one 1c-2g service, Oregon; no plan modification.

## Call history mode filter

User reported Voice, Video and Walkie-Talkie all showing the same history. The mode selector only controlled starting calls. History and search now filter existing records by selected voice/video mode (including missed video outcomes); Walkie-Talkie shows its existing coming-soon state with no internet call records. Switching modes resets pagination. No stored history or schema changed. Updated search/navigation regression and added mode-switch/history-preservation device coverage. Validation is recorded below after the build.

Validation: Android JVM tests and device/instrumentation APK builds succeeded (log /tmp/rabta-call-filter-build.log). New device regression compiled but not run locally; USB phone unavailable. CI will rerun on the pushed commit. No production release.

## Oct 23: PR failure versus manual deployment

Read-only investigation: PR #29 head 6f12fa0 builds successfully; API 29 and 34 device jobs pass. API 36 ran 107 cases with one failure in CallsScreenTest.searchFiltersRealHistoryIncludingOlderRecordsAndSurvivesNavigation at line 117 (initial recent missed-call row). The call-mode regression and call-media cases did not fail. SQLite call queries ordered only by timestamps, leaving tied rows unspecified; a local SQLite reproduction shows newest records absent from the first eight under equal timestamps. Add ID tie-breakers (descending for recents, ascending for chat timeline), and force timestamp ties in the existing regression instead of extending waits or removing assertions.

Fetched main remains a779960 (PR #30); the subsequent fetched commits are our feature work. Tag v2.0.361 points to a779960. Render lists manual deployment of 6f88485 as live at 2026-10-23 10:50 UTC, replacing a779960. Compared index.js/account.js/features.js/package.json between those commits: no diff. This deployment occurred after the failing CI run and did not cause it. No infrastructure mutation, rollback, merge or release performed during investigation.

Follow-up validation: 106 JVM tests and 19 Python checks passed; device and instrumentation APKs built successfully. Equal-timestamp SQLite reproduction confirms the query change; updated emulator regression awaits GitHub CI. No USB device available. Build log: /tmp/rabta-call-order-build.log.

## Oct 23: release authorization and web continuation

PR #29 final head 218acf8 passed build and all API 29/34/36 interface jobs. The user's real voice/video calls on 2.0.361 rang then ended. Both regular and test apps were running; after force-stopping ONLY com.irfan.rabta.testing (no data cleared), the user confirmed regular calls work. This strongly implicates competing sessions but account equality was not independently verified. Use a separate account for test app sessions; do not restart Rabta Test with the production account.

User explicitly authorized merging PR #29 and deploying/releasing its changes. Merged as 2576249 on main. Render autoDeploy is off; one manual API deployment dep-dbdpidcs728c7382u4p0 was started and verified live at that commit. Live health now advertises call-recovery-120s and returns ok=true. Public Android release run 39995231379 is still being monitored. No instance/plan/database changes.

Rabta Web uses a separate worktree /home/saif/rabta/Rabta-Web, branch feat/web-companion. First standalone pairing state and five tests committed as 0f51c82, pushed. Not mounted in index.js; no production web interface. User confirmed phone-connected first version. Detailed requirements and remaining work: docs/WEB_COMPANION.md. rabta.tech currently delegates to Orderbox/Get.tech nameservers. No DNS change or domain transfer performed. A web subdomain CNAME can be configured when the actual frontend is ready.

## Rabta Web first functional development batch

Production release run 39995231379 completed successfully. Public Rabta 2.0.371 APK/AAB/update JSON are published in irfan347/rabta-app; latest update JSON verified versionCode 1371, correct APK URL and matching published asset size 61,154,377 bytes. Public release-note edit returned 404 through this account; source-repo mirror notes were updated successfully. No release rerun. Render remains live on 2576249, health ok and call-recovery-120s present.

Web branch now implements opt-in server routes, browser UI and Android linked-browser UI/command handling. See docs/WEB_COMPANION.md for scope and limitations. Server route mounting is disabled unless RABTA_WEB_ORIGIN is configured; no environment/DNS changes made. The browser gets a separate HttpOnly cookie; it never uses a phone token or occupies the phone socket. Phone authorization is bound to account/device and locally approved link ID. Explicit preview includes the configured web origin. Send confirmation follows a durable SQLite transaction; repeat IDs cannot create duplicate or conflicting messages.

Validation: 96 backend tests and 19 Python checks passed. Headless Chrome fixture test passed pairing/approval/history/send/XSS-as-text/mobile/disconnect/reconnect/unlink. 106 JVM tests passed and initial plus final device/instrumentation APK builds passed; final origin-confirmation wording compilation also passed. Two new Android device cases compile but have not been executed locally. Phone is disconnected; no installation, data reset or restarting Rabta Test occurred. Preview PNGs use fixture chats only and live outside git in /home/saif/Downloads/rabta/web-preview/.

Next: finish final APK compile, push completed batch and create draft web PR for emulator checks. Physical phone-to-browser acceptance still required before requesting web deployment. Keep regular Rabta and Rabta Test on separate accounts. Calls, media transfer, groups, old-history pagination and offline browser operation are deliberately outside this first web version.

## PR #31 Android 14 account-deletion failure

PR #31 is open as a draft on feat/web-companion. Run 40003913895 passed builds and API 29/36 device jobs; API 34 failed AccountDeletionTest (deleted chat database still existed). Background workers can retain SQLiteOpenHelper objects across singleton/account changes and reopen a deleted file. Db now tracks live helpers weakly, retires all helpers for the deleted account under the acquisition lock, and rejects subsequent read/write opens. Session identity is read inside that lock. The regression retains two helpers across account switches and asserts neither can reopen deleted history, while preserving the other account. No schema, production data, server or deployment changes.

Validation: 106 JVM tests, 19 Python checks and device/instrumentation APK builds passed locally (/tmp/rabta-deletion-fix-build.log). Device regression execution awaits pushed GitHub CI; no USB phone is currently attached. Web batch already pushed and draft PR created; supersedes the earlier next-step note. Physical phone-to-browser acceptance remains outstanding before web deployment.

## Oct 24: phone feedback and isolated preview

User clarified Rabta Test does NOT crash; do not continue treating it as a crash report. They want the empty space beside a message to accept long-press/reply gestures too. MessageRow now accepts presses and forwards long-press to its bubble action; existing child buttons retain their handlers. Existing conversation regression now exercises the row long-press path. Linked devices is accessible from the main header More options menu.

Signup rejection previously switched automatically to sign-in and overwrote the entered username when the server reported an existing phone account. It now explicitly says Account not created and offers a deliberate sign-in action. Isolated live HTTP checks confirmed successful signup/login and rejection of a second account for the same phone identity. This does not prove the precise cause of the user's earlier attempts; no production PINs were tried or accounts modified.

Cloudflare connector LIST_ACCOUNTS returned 400 invalid X-Auth-Key format. Used a free temporary Quick Tunnel instead, forwarding to a local disposable Node server with no production credentials. New staging variant installs separately as com.irfan.rabta.staging (Rabta Preview); no existing application storage is changed. See WEB_COMPANION.md for build/run lifecycle. Cloudflare is providing the HTTPS tunnel, not persistent server hosting.

Earlier APK handoff correction: app-debug.apk was mistakenly labeled Rabta Test and rejected by Android. Rebuilt assembleDevice, verified com.irfan.rabta.testing and APK signature, replaced Downloads/rabta file. ADB installation was canceled on phone; user installed manually. Regular Rabta remained 2.0.371. Always verify artifact package before installing, not its filename.

Preview process lifecycle: interactive processes ended during turn interruption, so moved to transient user services `rabta-preview-tunnel` and `rabta-preview-server`. Inspect with `systemctl --user status` / `journalctl --user -u`; stop both after testing with `systemctl --user stop rabta-preview-server rabta-preview-tunnel`. Current URL is recorded in the tunnel journal and the local APK build log, not committed as a permanent address. A fresh tunnel requires rebuilding staging with its new origin. Transient units are not enabled for boot. Server restart loses disposable users and links.

Batch validation: 96 backend tests, 106 JVM tests and 19 Python checks passed. Browser fixture smoke passed; final device and instrumentation APK compilation passed (/tmp/rabta-preview-final-build.log). Updated row-interaction regression compiled and awaits GitHub emulator execution. An initial public-browser pairing probe timed out, while direct HTTPS pairing returned 200; investigating public-browser loading before claiming live web acceptance. No production merge, deploy, database or DNS changes.
