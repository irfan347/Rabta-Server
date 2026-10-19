# Moving Rabta's server to Oracle Cloud

Render's free plan puts the server to sleep after 15 minutes without requests and needs about a minute to wake it.
While it sleeps no message or call can arrive. Oracle Cloud's Always Free VM stays on. These steps move the server
there. Accounts, waiting messages and sign-ins come along: the database is copied table by table and checked, and
`TOKEN_SECRET` stays the same, so nobody signs in again. Nothing at Render is deleted.

## 1. Oracle account and VM

1. Sign up at cloud.oracle.com. Choose the home region **India West (Mumbai)**; it cannot be changed later.
2. Compute → Instances → **Create instance**:
   - Image: **Canonical Ubuntu 24.04** (22.04 also works).
   - Shape: **Ampere VM.Standard.A1.Flex**, 2 OCPUs and 12 GB memory. This stays within the Always Free allowance,
     which Oracle lowered in 2026. If Oracle reports "out of capacity", try again later or pick another availability
     domain.
   - Networking: keep **Assign a public IPv4 address** on.
   - SSH keys: **Generate a key pair** and download both keys. Keep them private.
3. Note the instance's **public IP address**.
4. Networking → Virtual cloud networks → your VCN → Security Lists → Default security list → **Add Ingress Rules**:
   source `0.0.0.0/0`, protocol TCP, destination port `80`; add a second rule for port `443`.
5. Oracle reclaims Always Free VMs that stay idle for 7 days (CPU, network and memory under 20%). A small Rabta server
   looks idle, so upgrade the account to **Pay As You Go** (Billing & Cost Management → Upgrade and Manage Payment).
   The VM is then kept, and usage within the Always Free limits stays free.

## 2. Domain

Use a domain you control, so a later move never needs an app update. Buy one (about $10 a year) or use a free
DuckDNS name. Add an **A record** such as `api.your-domain.com` pointing to the VM's public IP.

## 3. Install

Open a terminal on the VM: Oracle's **Cloud Shell** (the terminal icon at the top of the console), or Termux on a
phone, then `ssh -i <private key> ubuntu@<public IP>`. Run:

```sh
curl -fsSL https://raw.githubusercontent.com/irfan347/Rabta-Server/main/deploy/oracle/setup.sh -o setup.sh
sudo bash setup.sh api.your-domain.com
```

It installs Node.js 20, PostgreSQL 17 and Caddy (automatic HTTPS), creates the database, the `rabta` service (it
restarts itself, also after a reboot) and a daily database backup kept for 14 days in `/var/backups/rabta`, and opens
ports 80 and 443 in the VM's firewall. The repository must still be public for this download and clone.

## 4. Move the data (about 10 minutes offline)

1. Render → the Rabta-Server service → Settings → **Suspend**, so nothing new arrives there.
2. On the VM: `sudo bash /opt/rabta/deploy/oracle/migrate-db.sh`. Paste the `DATABASE_URL` from Render's
   Environment (Rabta's database is on Neon; the script switches to Neon's direct connection itself). It is not shown
   or saved. The script refuses to write over data, copies every table and compares each table's row count with the
   old database. Neon keeps its copy untouched.
3. `sudo nano /etc/rabta.env`: paste `TOKEN_SECRET` exactly as Render has it, plus `ADMIN_KEY`, `TURN_URL` and
   `TURN_SECRET` if Render has them. Save, then `sudo systemctl start rabta`.
4. Open `https://api.your-domain.com/health`. It should show `"db":true` and the number of accounts.

## 5. Keep older apps working

Render → the Rabta-Server service:

- Settings → **Start Command**: `node deploy/forward.js`
- Environment → add `FORWARD_TO` = `https://api.your-domain.com`
- **Resume** the service.

Apps that still use the Render address now reach the new server through it. Nothing is stored at Render.

## 6. App update

The app is then updated to use `https://api.your-domain.com`; signed-in phones switch to it by themselves and stay
signed in. Once everyone has updated, the Render service can be deleted.

## Checks and help

- Service: `sudo systemctl status rabta`; logs: `sudo journalctl -u rabta -n 100`.
- Update the server code later: `sudo bash setup.sh api.your-domain.com` again (settings and data are kept).
- Backups: `ls /var/backups/rabta`.

## As Rabta grows

1. Give the VM more CPUs and memory (a paid shape): same server, same data.
2. Run the TURN relay on this VM's public IP (see `deploy/README.md`) for video calls across strict networks.
3. Add push notifications (Firebase) for delivery while phones sleep deeply.
4. Send voice calls peer to peer (WebRTC) instead of through the server.
5. Keep photos and videos in object storage.
6. With many servers: a load balancer and a shared message bus.
