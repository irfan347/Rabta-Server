# Focused Calls screen fix

## Root causes

- The shared home search hid only its EditText on Calls, leaving the empty decorated row visible. Its TextWatcher only invoked conversation queries; calls had no query/filter path.
- The call-history empty state reused a large generic card and offered New chat.
- Call rows opened a conversation; their trailing icon had no callback listener. Video detection tested equality with `video`, although the current logger writes `outgoing video`, `incoming video`, and outcome strings ending in ` · video`.

## Changes

- Calls-only 44dp search, 20dp horizontal gutters, 12dp corners, 20dp icon, 14sp text; dark fill #171D2C and border #2A3142. Light appearance retains existing readable theme tokens. The entire shared conversation search row is hidden on Calls; other destinations keep their existing layout.
- Search uses all saved `Db.CallRecord` rows, not the old 100-row history limit. Case-insensitive literal matching covers saved names and peer IDs (including a number if present). Rabta has no separate phone-number/address-book field; no numbers are synthesized or fetched. Records render in batches of 100 with a real Show more calls action. Input rendering is debounced; database loading remains off the UI thread.
- Search is hidden only for an empty history, remains available for zero matches, supports clear/IME Search, does not acquire focus on entry, and retains its query across tab navigation and Activity recreation. Back clears focus first, then a nonempty query, then returns to Chats.
- Compact 24sp heading, small phone empty state with No calls yet and requested secondary text, circular avatars, accurate date/time and durations, stored call direction/outcome and media type. Existing records without separate media/direction fields are not assigned invented metadata.
- Start a call lists existing direct conversations and call contacts, excludes groups/self/blocked contacts, and reuses the existing username/ID lookup input. Callback enters existing ChatActivity voice/video methods with a consumed one-shot request. Existing microphone, connectivity and protocol checks remain in force; blocked peers cannot be called from these actions. Offline calls are still unsupported, with existing connection guidance rather than fake success.
- No backend, database schema, session, message, encryption, or offline-networking changes. Chats, Nearby, Spaces, Calls, and Status navigation remain available.

## Verification

`CallsScreenTest` uses a unique disposable database, restoring the singleton afterward; it never clears existing call/message history. It covers empty history/search visibility and real contact picker, case-insensitive Unicode names and stored number strings, a match beyond the newest 100 calls, no-results/clear, exact search height, tab/back behavior, and callback sending actual `call_init` through a localhost MockWebServer while retaining original history and session token.

Local Python tests: 17 passed. Local Android execution is unavailable (`gradle: command not found`); APK/JVM tests and API 29/34 instrumented verification run in the existing GitHub Actions workflow. The first emulator run passed the real callback signaling test but two other new tests timed out during Activity launch. Test setup now grants the permissions requested by the home Activity before launching it, and waits for lifecycle cleanup before closing its disposable database. Production phone-to-phone call/audio quality remains a physical-device check, not implied by emulator signaling tests.
