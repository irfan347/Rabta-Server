# Profile editing and phone security

## Username and display name
- **Username:**
  - 3–20 characters: letters, numbers and `_`. Case does not matter.
  - Availability is checked live while typing ("Username already used").
  - The first change is allowed right away. After that, it can change once every **30 days**.
  - The old username stays **reserved for its owner for 30 days**. Nobody else can take it, and the owner can take it back.
  - The account ID never changes, so chats, contacts and Nearby keys are unaffected.
- **Display name:** 1–30 characters, and it can change at any time. Contacts see the new name with the next message, or when they look the user up.

## A stolen PIN cannot sign in on another phone
- Each phone has its own **Android Keystore key** (EC P-256, never leaves the phone). Signing in sends a single-use, time-limited, purpose-bound signature with this key. Tokens name the phone they were issued to.
- A correct PIN on a **new phone** only creates a 5-minute sign-in request. The account's trusted phone gets a notification and can **Allow** or **Deny** it under Profile → Phones & recovery.
- **Recovery code** (`XXXX-XXXX-XXXX`):
  - Shown once, when the account gets its first trusted phone. The server stores only a hash.
  - Using it on a new phone signs that phone in, **signs out every other phone**, and issues a new code.
  - A new code can be created at any time, after confirming the PIN.
- **Removing a phone** signs it out immediately.
- **Existing accounts:**
  - Older app versions keep working until the account's first phone with this version opens Rabta. That phone becomes trusted and shows the recovery code.
  - After that, older apps are asked to update, and old sessions stop.
- **Reinstalling the app** removes its key, so the phone counts as new: it needs approval or the recovery code.
- **Lost every phone and the code:** the server owner can reset a user's phones. This only works when `ADMIN_KEY` (32+ characters) is set on the server: `POST /api/admin/reset-devices` with header `x-admin-key` and `{ "username": … }`. The next phone that signs in becomes trusted again.

Storage is additive: new `ru_users` columns (`username_changed_at`, `recovery_hash`), plus the `ru_devices` and `ru_reserved` tables.

## Tests
- `tests/account.test.js`: cooldown, reservation, live check, display-name limit, approval/deny, recovery, replay and tamper protection, existing-account upgrade, phone removal, admin reset, persistence.
- `AccountSecurityTest` (device): the Keystore proof verifies the way the server checks it; the username editor's live check and save; the display-name limit and save.
