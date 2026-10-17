# Rabta NOVA

The NOVA redesign changes how Rabta looks, not how it works. Accounts, sign-in, message storage and delivery, Nearby, calls, the package ID and signing are unchanged.

## Brand
- **Launcher icon:** the official NOVA icon kit as vectors. The lime R sits on a navy radial background (adaptive foreground and background), with a single-colour themed icon for Android 13+.
- **Other brand surfaces:** a white R notification icon. The dark splash screen shows the same R.
- **App name:** still "Rabta".
- **Palette:** tokens live in `AppTheme.kt`, and `DesignSystemTest` checks AA contrast in both modes.

## Screens
- **Sign in:**
  - Orbit hero, "Stay close. Beyond boundaries.", and a show/hide control inside the PIN field.
  - **Create account** asks for a display name, checks the username live against the server, and confirms the PIN. The server still decides.
  - When the server can't be reached, the message says why: no internet, a slow or waking server, or a date/time problem.
- **Chats:**
  - A compact header.
  - A **Moments** row built only from real 24-hour statuses on the server. If there are none, it shows just *Add moment*.
  - Lime **All / Unread / Groups** filters, lime unread badges and one compose button.
- **Nearby (Signal Orbit):**
  - Discovered people sit on rings by connection stage: connected, waiting for approval, or discovered. A person's position on the ring comes from their ID, so it never shows distance, direction or signal strength.
  - Discovery stays off until the user turns on **Discoverable**.
  - **Quick tools** open the existing Rabta Drop, Instant Connect QR, Network Vision and NFC pairing. The NFC tile says so when the phone has no NFC.
- **Calls:** one **Start a call** action, with **Voice** and **Video** options and the real call history.
- **Spaces:** existing groups.
- **Profile:**
  - Centred avatar with a lime ring, plus **Change photo** and **My QR** (Instant Connect).
  - The display name, username and Rabta ID editors are unchanged.
  - Rows link to privacy, notifications, data & backup, phones and the offline security code.

## Coming soon (shown in the app, not built)
- **Walkie-Talkie:** push-to-talk (Calls).
- **AirSpace:** temporary nearby rooms (Spaces).
- **Availability:** status (Profile).
- **Storage usage:** (Profile).

Rabta never claims multi-hop, long-range or end-to-end encryption beyond what the code verifies.

## Still needs real phones
- How the icon looks on launchers, recents and the splash screen.
- Two-phone Signal Orbit discovery, NFC pairing, and voice and video calls.
