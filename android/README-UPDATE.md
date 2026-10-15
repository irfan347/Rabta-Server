# Rabta 1.7 source update

This is an updated Android source project, not a compiled APK.

## Build

Android source lives in `android/` in the GitHub repository. The root workflow at
`.github/workflows/main.yml` locates the Gradle project explicitly and publishes
`Rabta-debug-apk` after a successful run. This fixes the previous filename mismatch:
the old workflow looked for `rabta.zip`, while the uploaded file was `rabta-fixed.zip`.
The locator also supports a root-level project or a single source ZIP.

Use Java 17, Gradle 8.7, Android API 34 and build-tools 34.0.0. SDK setup installs
`platform-tools`, avoiding the retired `tools` package.

From the repository root:

```sh
gradle --project-dir android :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

For the standalone source ZIP, run the command from its extracted root without
`--project-dir android`. GitHub does not discover workflows inside a ZIP: its
workflow must be placed in the repository's root `.github/workflows/` directory.

Changes and CI results: https://github.com/irfan347/Rabta-Server/pull/1

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

GitHub Actions successfully ran five Kotlin receipt-state unit tests and built the
debug APK at commit 1bed2e392e819a33781a79a08bc65f92b0590b3e. The 14 Python
checks also passed in CI. Build: https://github.com/irfan347/Rabta-Server/actions/runs/38781051213
The latest CI result is linked from PR #1. Local Android compilation was not
available; Gradle/Android SDK were absent and the network proxy was unreachable.
Real-device behavior still needs the checks below.

Delivery/read states use the existing backend receipt events. The server's `stored`
receipt maps to sent (accepted for offline storage), and `error` maps to failed.
The inspected server currently lacks video_offer/video_answer/ice_candidate/video_end
relay handlers: the video controls are implemented, but end-to-end video calls
still require backend signaling support.

Delivery/read states use the existing backend receipt events. An accepted WebSocket
send is not treated as delivery. No server acknowledgement in 30 seconds marks a
send failed so it can be retried. Retries retain the message ID, and the Android inbox deduplicates it. The current
server can still keep duplicate pending entries if an acknowledgement was lost. Nearby text remains labelled queued because the
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
