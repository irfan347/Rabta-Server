#!/usr/bin/env bash
# Sets up the Rabta server on a fresh Ubuntu VM (made for Oracle Cloud's Always Free VM; any Ubuntu 22.04/24.04 works):
# Node.js, PostgreSQL, Caddy for HTTPS, a systemd service that restarts itself, the VM firewall opened for web traffic,
# and a daily database backup. Running it again updates the code and keeps every setting and all data.
#
#   sudo bash setup.sh api.your-domain.com
#
# The service starts once TOKEN_SECRET is filled in /etc/rabta.env (copy it from the old host so everyone stays signed in).
set -euo pipefail

DOMAIN="${1:-}"
[ -n "$DOMAIN" ] || { echo "Usage: sudo bash setup.sh api.your-domain.com"; exit 1; }
[ "$(id -u)" = 0 ] || { echo "Run it with sudo."; exit 1; }
REPO="${RABTA_REPO:-https://github.com/irfan347/Rabta-Server.git}"
BRANCH="${RABTA_BRANCH:-main}"
APP=/opt/rabta
ENV_FILE=/etc/rabta.env
export DEBIAN_FRONTEND=noninteractive

echo "== Packages"
apt-get update -y
apt-get install -y curl git ca-certificates gnupg openssl debian-keyring debian-archive-keyring apt-transport-https iptables-persistent netfilter-persistent

# Small VMs (1 GB) need swap for npm and PostgreSQL; the larger Ampere VMs skip this.
if [ "$(awk '/MemTotal/ {print $2}' /proc/meminfo)" -lt 2000000 ] && [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

if ! command -v node >/dev/null || [ "$(node -p 'process.versions.node.split(".")[0]')" -lt 20 ]; then
  echo "== Node.js 20"
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
fi

# PostgreSQL from the PostgreSQL project, new enough to copy the old database whatever its version.
if ! command -v pg_dump >/dev/null || ! dpkg -s postgresql-17 >/dev/null 2>&1; then
  echo "== PostgreSQL 17"
  install -d /usr/share/postgresql-common/pgdg
  curl -fsSL -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc https://www.postgresql.org/media/keys/ACCC4CF8.asc
  echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt $(. /etc/os-release; echo "$VERSION_CODENAME")-pgdg main" > /etc/apt/sources.list.d/pgdg.list
  apt-get update -y
  apt-get install -y postgresql-17
fi

if ! command -v caddy >/dev/null; then
  echo "== Caddy (HTTPS)"
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -y
  apt-get install -y caddy
fi

echo "== Rabta code"
id rabta >/dev/null 2>&1 || useradd --system --home-dir "$APP" --shell /usr/sbin/nologin rabta
# The code belongs to the rabta user; root still runs the updates below.
git config --system --get-all safe.directory 2>/dev/null | grep -qx "$APP" || git config --system --add safe.directory "$APP"
if [ -d "$APP/.git" ]; then
  git -C "$APP" fetch --depth 1 origin "$BRANCH"
  git -C "$APP" checkout -q --force FETCH_HEAD
else
  git clone --depth 1 --branch "$BRANCH" "$REPO" "$APP"
fi
(cd "$APP" && npm install --omit=dev --no-audit --no-fund)
chown -R rabta:rabta "$APP"

echo "== Database"
systemctl enable --now postgresql
if [ ! -f "$ENV_FILE" ]; then
  DB_PASS="$(openssl rand -hex 24)"
  if sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='rabta'" | grep -q 1; then
    sudo -u postgres psql -qc "ALTER ROLE rabta LOGIN PASSWORD '$DB_PASS'"
  else
    sudo -u postgres psql -qc "CREATE ROLE rabta LOGIN PASSWORD '$DB_PASS'"
  fi
  sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='rabta'" | grep -q 1 || sudo -u postgres psql -qc "CREATE DATABASE rabta OWNER rabta"
  umask 077
  cat > "$ENV_FILE" <<EOF
# Rabta server settings. Only root can read this file; never share it.
PORT=3000
DATABASE_URL=postgres://rabta:$DB_PASS@127.0.0.1:5432/rabta
PGSSL=disable
# Copy TOKEN_SECRET exactly from the old host (Render: Environment), so everyone stays signed in.
TOKEN_SECRET=
# Copy these too if the old host has them:
#ADMIN_KEY=
#TURN_URL=
#TURN_SECRET=
EOF
  chmod 600 "$ENV_FILE"
fi

echo "== Service"
cat > /etc/systemd/system/rabta.service <<EOF
[Unit]
Description=Rabta server
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
User=rabta
WorkingDirectory=$APP
EnvironmentFile=$ENV_FILE
ExecStart=/usr/bin/node index.js
Restart=always
RestartSec=3
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable rabta >/dev/null

echo "== HTTPS for $DOMAIN"
cat > /etc/caddy/Caddyfile <<EOF
$DOMAIN {
	encode gzip
	reverse_proxy 127.0.0.1:3000
}
EOF
systemctl reload caddy 2>/dev/null || systemctl restart caddy

# Oracle's Ubuntu images reject web traffic in the VM's own firewall; the VCN security list needs ports 80/443 too.
for port in 80 443; do
  iptables -C INPUT -p tcp --dport "$port" -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport "$port" -j ACCEPT
done
netfilter-persistent save >/dev/null

echo "== Daily backup (kept 14 days in /var/backups/rabta)"
install -d -m 700 /var/backups/rabta
cat > /etc/cron.daily/rabta-backup <<'EOF'
#!/bin/sh
set -e
. /etc/rabta.env
pg_dump -Fc "$DATABASE_URL" -f "/var/backups/rabta/rabta-$(date +%F).dump"
find /var/backups/rabta -name 'rabta-*.dump' -mtime +14 -delete
EOF
chmod 755 /etc/cron.daily/rabta-backup

if grep -q '^TOKEN_SECRET=..*' "$ENV_FILE"; then
  systemctl restart rabta
  sleep 3
  curl -fsS http://127.0.0.1:3000/health && echo
  echo "Done: https://$DOMAIN/health"
else
  echo
  echo "Almost done. Next:"
  echo "  1. sudo bash $APP/deploy/oracle/migrate-db.sh   (copies the old database here)"
  echo "  2. sudo nano $ENV_FILE                           (paste TOKEN_SECRET from the old host)"
  echo "  3. sudo systemctl restart rabta && curl https://$DOMAIN/health"
fi
