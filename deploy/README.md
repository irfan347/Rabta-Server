# Rabta 1.9 backend and TURN setup

Deploy the matching `index.js`, `features.js`, and `package.json` together. Start with `npm install && npm start`. Use HTTPS. The app currently points at `https://rabta-server-1e2i.onrender.com`; update that existing service from this branch after reviewing the changes. No service has been deployed by this change.

Set `DATABASE_URL` to PostgreSQL and a persistent random `TOKEN_SECRET` in the host's secret settings. Without PostgreSQL, statuses, uploads, blocks and reports use memory and are lost on restart. Database initialization must succeed before the server listens. Schema creation is additive. The new `rf_data` table stores feature records. Back up the database before rollout.

File limits: 8 MB each, 128 KB chunks, 64 MB total upload storage per account. Incomplete uploads expire after 24 hours. Completed chat files are retained; this first implementation stores base64 chunks in PostgreSQL and memory, so use a single server instance and monitor memory/storage. Object storage and larger media need a subsequent storage migration. Status visibility and its file access expire after 24 hours; saved/downloaded copies cannot be remotely erased. Reports are stored for an administrator in `rf_data` (`kind='report'`); there is no automatic moderation service.

## TURN (not provisioned)

The user has no TURN service yet. A relay needs a public-IP VM or a managed coturn-compatible TURN REST service. Normal HTTP-only hosting cannot expose coturn's UDP relay range. Do not put permanent credentials in the APK or GitHub.

1. On your own Linux relay VM, install the maintained coturn package and copy `turnserver.conf.example` to `/etc/turnserver.conf`. Replace the domain, public/private IP mapping if needed, and secret. Generate a new secret locally with `openssl rand -hex 32`; keep it out of logs and source control. Protect the config file with owner-only permissions.
2. Allow TCP/UDP 3478 and UDP 49160–49200 through the VM and provider firewall. For TLS, configure a valid domain certificate, enable port 5349 and allow TCP 5349. Configure your DNS to point to the relay's public IP. Enable/start coturn using the operating system's service manager.
3. In Rabta server secret settings, set `TURN_URL` to `turn:YOUR_DOMAIN:3478?transport=udp,turn:YOUR_DOMAIN:3478?transport=tcp` (and `turns:YOUR_DOMAIN:5349?transport=tcp` only after TLS works). Set `TURN_SECRET` to the same secret used by coturn. Restart the backend.
4. The authenticated `/api/ice` endpoint issues one-hour HMAC credentials. The app fetches these when starting video calls. Settings → Video relay status shows whether credentials are configured, not a relay connectivity test.
5. Test a real call between two phones on different networks; verify a `relay` ICE candidate is selected using WebRTC diagnostics. Test UDP-blocked networks, answer/decline, timeout and reconnection. A successful APK build does not verify TURN connectivity.

Until step 3 is complete, `/api/ice` returns STUN only and `relayConfigured: false`. Video can work on permissive networks but is not reliable across all NAT/firewall configurations.
