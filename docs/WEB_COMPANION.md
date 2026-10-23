# Rabta Web: phone-connected first version

User selected a browser companion that requires the phone to remain connected. Worktree: `/home/saif/rabta/Rabta-Web`, branch `feat/web-companion`. Production 2.0.371 and Render commit 2576249 are separate and do not include this feature.

## Implemented scope

- `/web/` responsive browser UI; QR and code-based linking, explicit approval in Android Profile → Connected Devices → Linked browsers.
- Existing one-to-one chat list (up to 100), search, recent history (up to 80 messages, bounded to 180 KB), text sending through the phone. Media placeholders direct the user to the phone; no media is uploaded by this feature. Very long text is visibly truncated at 4000 characters in snapshots.
- Phone stays authoritative. Browser requests use a separate HTTP companion protocol, never the phone's WebSocket login. No browser can replace the phone connection.
- Phone-side privacy checks exclude locked/archived chats, blocked users and message requests. Groups are excluded from this first version. Snapshots are checked again before replying; already viewed data cannot be remotely erased or made screenshot-proof.
- Text is durably saved into the existing phone outbox before confirmation. Retries reuse one message ID and reject conflicting content. Browser distinguishes saved/queued from delivered. No offline browser outbox.
- Browser traffic uses same-origin POST APIs, a custom request header, HttpOnly/SameSite=Strict cookies (Secure on HTTPS), no-store responses, CSP and frame protection. It has no phone account token or account-management permission.
- Pairing expires in two minutes. Codes are single-use. Links expire after eight hours, are bound to the approving account AND device, and can be revoked from that phone. The phone maintains its own allow-list as well. Phone trust revocation invalidates browser access.
- Session state is deliberately bounded and in memory. A Render restart requires pairing again. No database archive, additional service or paid plan is introduced.
- Only visible browser tabs poll, every four seconds after the previous request finishes. One in-flight phone command per link, bounded global pending requests, and eight-second command timeouts. Pairing/approval/session request limits prevent unbounded memory. The pairing IP limit may be shared by clients behind Render's proxy; no global proxy trust setting has been changed.

## Enablement (not performed)

The web routes are disabled unless `RABTA_WEB_ORIGIN` is set to the exact HTTPS origin, e.g. `https://web.rabta.tech`. Local development permits `http://localhost:PORT`. Use one existing server instance; sessions and pending requests are process-local.

Both the server from this branch and updated Android APK are necessary. Test with a disposable account first, without opening Rabta Test under the regular app's account. The phone can link browsers only after its trusted-device login is established.

Add the exact custom hostname in Render and configure Get.tech DNS `CNAME web` to the existing Render hostname, then verify TLS. Existing API `onrender.com` address must remain enabled for installed apps. No nameserver change or domain transfer is required for a normal subdomain record. Check existing Render custom-domain allowance before adding any charged domain. No DNS or custom domain changes have been made.

## Validation and remaining acceptance

- Node suite: 96 tests passed, including 10 new pairing/HTTP cases for expiry, replay, wrong origin, wrong device/account, revocation during a request, unsupported operations, offline phone and session isolation.
- Headless Chrome smoke covers QR generation, phone approval (simulated phone), history, text send, literal rendering of script-like content, 390px mobile layout, disconnected/reconnected phone and unlink. Screenshots contain disposable fixture content only.
- Android regressions added for privacy exclusion, expiry/revocation and durable idempotent web sends. Build/device CI status is recorded in HANDOFF.md.
- Physical Android-to-browser acceptance, actual QR camera scan, background/locked phone behavior and simultaneous calling still require validation. Browser tests use a simulated phone, not proof of Android integration.
- No browser calls, attachments, groups, old-history pagination, persistent browser history or independent operation with the phone offline in this release.
- No end-to-end encryption claim: the existing server can see routed payloads; HTTPS protects transport. No chat content is added to server logging.
