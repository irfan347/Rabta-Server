# Rabta project rules

These rules apply to the entire repository and all future work on the existing Rabta application.

- Never rebuild Rabta from scratch.
- Preserve authentication and user accounts.
- Never reset, delete or overwrite production data.
- Preserve existing online and offline messaging.
- Do not remove existing features.
- Reuse current architecture and dependencies.
- Make focused changes.
- Use real data instead of fake UI functionality.
- Run relevant tests after code changes.
- Give short completion reports.

Before changing code, read `RABTA_ROADMAP.md`, inspect the affected implementation and check Git status. Keep unrelated user changes intact and establish a safe checkpoint before risky work. Document existing failures separately from regressions introduced by a change. Test fixtures belong only in isolated test environments.

The Android application is in `android/`; the Node.js backend is at the repository root. Preserve the existing SQLite schemas, session identity, Android Keystore keys, backend accounts, message protocols and stored messages. Use additive, data-preserving migrations when a separately authorized task requires a schema change. Never solve an authentication, connection or migration problem by clearing app storage or recreating accounts.

Relevant checks, depending on the affected files:

```sh
python3 -m unittest discover -s android/tests -v
npm test
node --check index.js
node --check features.js
gradle --project-dir android :app:testDebugUnitTest :app:assembleDebug --no-daemon --stacktrace
gradle --project-dir android :app:connectedDebugAndroidTest --no-daemon
```

Android commands require the existing Java 17 / Gradle 8.11.1 / Android SDK 36 toolchain (AGP 8.9.1; the app targets API 36, as Google Play requires); device tests additionally require an emulator or test phone. Run backend tests against isolated fixtures and disposable databases, never production accounts or production storage. Report unavailable checks honestly; a successful APK build does not prove phone-to-phone messaging, calling or mesh delivery.
