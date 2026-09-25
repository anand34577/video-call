#!/bin/sh
# Daily sqlite backup for Vision Call. Add to cron, e.g.:
#   0 3 * * * /opt/videocall/deploy/backup.sh
#
# Only needed when running the default embedded sqlite database. If you set
# DATABASE_URL to Postgres or MySQL, use pg_dump or mysqldump instead (see the
# wiki page "Backups and Upgrades").
set -eu

# Defaults match the layout scripts/install-linux.sh creates.
BIN="${VIDEOCALL_BIN:-/opt/videocall/videocall}"
DATA_DIR="${DATA_DIR:-/opt/videocall/data}"
BACKUP_DIR="${BACKUP_DIR:-$DATA_DIR/backups}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"

mkdir -p "$BACKUP_DIR"
DEST="$BACKUP_DIR/videocall-$(date +%Y%m%d-%H%M%S).db"

"$BIN" -backup "$DEST"
echo "backup written: $DEST"

# prune anything older than KEEP_DAYS
find "$BACKUP_DIR" -name 'videocall-*.db' -mtime "+$KEEP_DAYS" -delete
