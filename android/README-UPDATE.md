# Rabta 1.7 source update

This is an updated Android source project, not a compiled APK.

## GitHub build error

The screenshot says the current working directory does not contain a Gradle build.
The replacement `.github/workflows/build-apk.yml` locates the Android project and
passes its directory explicitly to Gradle. It accepts source at the repository
root, in a subfolder, or in a single source ZIP. It fails clearly if multiple ZIPs
or multiple extracted Android projects make the choice ambiguous.

### If your repository stores the Android project as a ZIP

1. Replace the existing Android source ZIP with this updated ZIP. Keep only one
   Android source ZIP; do not keep the old and new versions side by side.
2. Open the **existing APK workflow** under `.github/workflows/` and replace its
   entire contents with the supplied `build-apk.yml`. Do not just re-run the old
   workflow, and do not leave both APK workflows enabled.
3. Commit both changes. Open Actions -> Build Rabta APK. After a successful run,
   download the `Rabta-debug-apk` artifact and extract the APK.

GitHub does not discover workflows stored inside a ZIP: step 2 is required.

### If your repository stores extracted Android source

Replace the Android project files with the contents of this ZIP. Keep any separate
server/backend code. Put the replacement workflow in the repository's top-level
`.github/workflows/` directory and replace the existing APK workflow there.

The build uses Java 17, Gradle 8.7, Android API 34 and build-tools 34.0.0.
Local command with those tools installed:

```sh
gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

## Changes

- Visible Online / Connecting / Nearby mode / Offline connection status.
  Chat subtitles describe this phone's connection, not unverified peer presence.
- Search conversations by name or any stored text message; search inside a chat.
  Existing New chat / New group actions remain available from the compose button.
- Sending, server-acknowledged sent, delivered, read, nearby queued and failed states.
  Failed bubbles retry using the same message ID. Receipts persist without an open
  chat. Opening your own chat no longer marks your outgoing messages read.
- Bundled vector controls for phone, video, microphone, speaker, attachments,
  compose, send, back, playback and camera switching; accessible control labels.
- Compact login header, scrolling form, PIN show/hide, 4–8 digit validation and
  keyboard resize support.
- Today / Yesterday / date separators; latest 200 messages instead of oldest 200;
  photo confirmation preview; visible recording timer, slide-left cancellation,
  2 minute recording limit and Android 8–11 compatible recording initialization.
- Voice mute actually silences outgoing microphone samples. Mute/speaker selected
  states, connected call timer, ringing timeout, restored audio routing after calls,
  and corrected microphone permission flow.
- Video call duration, speaker control, selected mute state and corrected SDP
  completion callbacks. Audio packets alone never auto-answer voice calls.
- Images and voice notes are no longer discarded by the incoming text-frame filter.
  Media uses the online typed-message protocol; the existing nearby protocol carries
  text only. Photos are sampled before decoding to reduce memory use.
- Original ChatActivity `muteBtn` / `muteBtnLayout` compile error corrected.

## Checks and limits

Passed locally: 14 Python checks exercising project discovery/ZIP extraction and
production SQLite queries. Workflow YAML, embedded project locator, project-root
resolution and Android resource XML were also validated.

Five Kotlin unit tests cover receipt ordering, timeouts and retry transitions; they
are included in the GitHub build step. **They and the full APK build have not been
run locally:** Gradle/Android SDK are absent and the network proxy was unreachable.
This source update still needs a successful GitHub build and real-device checks.
GitHub repository read access was confirmed. The write request was rejected with
HTTP 403 (Resource not accessible by integration), so no remote commit, branch,
PR or CI run was created.

Delivery/read states use the existing backend receipt events. The server's `stored`
receipt maps to sent (accepted for offline storage), and `error` maps to failed.
The inspected server currently lacks video_offer/video_answer/ice_candidate/video_end
relay handlers: the video controls are implemented, but end-to-end video calls
still require backend signaling support.

Delivery/read states use the existing backend receipt events. An accepted WebSocket
send is not treated as delivery. No server acknowledgement in 30 seconds marks a
send failed so it can be retried. Retry assumes the backend deduplicates message IDs
(the Android inbox already does). Nearby text remains labelled queued because the
existing mesh protocol has no end-to-end receipt. Queued mesh messages lost on a
process restart become retryable rather than being displayed as delivered.

Suggested device checks before release:

- Login with the keyboard visible; show/hide PIN; reject invalid PIN length.
- Search a name and an older message, clear search, and check date separators.
- Send/read messages on two phones; disconnect, fail and retry a message.
- Cancel/send a photo preview; record, slide to cancel and send/play a voice note.
- Make voice and video calls; check mute on the other phone, speaker routing,
  connection timer, decline/end and restoration of audio after hanging up.
- Disable internet on two nearby phones; verify nearby text routing and honest
  queued status. Test media again after internet reconnects.
