#!/usr/bin/env bash
# Copies the existing Rabta database (accounts, waiting messages, groups, statuses) from the old host into this
# server's database. The old database is only read, never changed; a copy of it is kept in /var/backups/rabta.
#
#   sudo bash /opt/rabta/deploy/oracle/migrate-db.sh
#
# It asks for the old database's URL: the DATABASE_URL the old server uses (Neon), or for a Render database its
# *External* Database URL. The URL is not printed or stored. Suspend the old server first, so nothing new arrives
# there while the copy is made.
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "Run it with sudo."; exit 1; }
. /etc/rabta.env

read -r -s -p "Old database URL: " OLD; echo
[ -n "$OLD" ] || { echo "No URL given."; exit 1; }
case "$OLD" in *sslmode=*) ;; *\?*) OLD="$OLD&sslmode=require" ;; *) OLD="$OLD?sslmode=require" ;; esac
# Neon: pg_dump needs the direct connection, not the pooled one ("-pooler" in the host name).
OLD="${OLD/-pooler./.}"

# Exact row count of every table in a database (0 when it has none).
rows() {
  psql "$1" -tAc "SELECT coalesce(sum((xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', schemaname, tablename), false, true, '')))[1]::text::bigint), 0) FROM pg_tables WHERE schemaname = 'public'"
}

# Never write over records that are already here.
if [ "$(rows "$DATABASE_URL")" != "0" ]; then
  echo "This server's database already holds data; nothing was changed."
  exit 1
fi

systemctl stop rabta 2>/dev/null || true
install -d -m 700 /var/backups/rabta
copy="/var/backups/rabta/old-host-$(date +%F-%H%M).dump"
echo "Reading the old database…"
pg_dump -Fc --no-owner --no-acl "$OLD" -f "$copy"

# Any tables here are empty (checked above); the copy takes their place.
psql "$DATABASE_URL" -qc "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
echo "Writing it here…"
pg_restore --no-owner --no-acl -d "$DATABASE_URL" "$copy" || echo "(Notices above are checked below.)"

# Every table must hold exactly as many rows here as in the old database.
same=1
for table in $(psql "$OLD" -tAc "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY 1"); do
  old=$(psql "$OLD" -tAc "SELECT count(*) FROM public.\"$table\"")
  new=$(psql "$DATABASE_URL" -tAc "SELECT count(*) FROM public.\"$table\"" 2>/dev/null || echo missing)
  echo "  $table: $old there, $new here"
  [ "$old" = "$new" ] || same=0
done
if [ "$same" != 1 ]; then
  echo "The copy does not match, so the server was not started. Nothing at the old host was changed; send this output for help."
  exit 1
fi

echo "Copied and checked. The old database is unchanged; its copy is $copy."
if grep -q '^TOKEN_SECRET=..*' /etc/rabta.env; then
  systemctl start rabta && echo "Rabta started."
else
  echo "Next: sudo nano /etc/rabta.env (paste TOKEN_SECRET from the old host), then: sudo systemctl start rabta"
fi
