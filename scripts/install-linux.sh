#!/usr/bin/env bash
# Install Vision Call as a systemd service under /opt/visioncall.
#
# Most people should use the one-line installer instead, which downloads the
# latest release and runs this script for you:
#   curl -fsSL https://raw.githubusercontent.com/anand34577/vision-call/main/scripts/get.sh | sudo sh
#
# From an extracted release archive (visioncall, .env.example and
# visioncall.service sit next to this script):
#   sudo ./install-linux.sh
# From a source checkout, pass the binary you built:
#   sudo ./scripts/install-linux.sh dist/visioncall-server-linux-amd64
#
# Running it again upgrades the binary in place and keeps your settings and data.
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "error: run as root (sudo $0)" >&2; exit 1; }
command -v systemctl >/dev/null || { echo "error: systemd is required" >&2; exit 1; }

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
pick() { for f in "$@"; do [ -f "$f" ] && { echo "$f"; return; }; done; echo "error: none of: $*" >&2; exit 1; }
bin="$(pick "${1:-$here/visioncall}")"
unit="$(pick "$here/visioncall.service" "$here/../deploy/visioncall.service")"
envex="$(pick "$here/.env.example" "$here/../.env.example")"
backup="$(pick "$here/backup.sh" "$here/../deploy/backup.sh")"
backup_svc="$(pick "$here/visioncall-backup.service" "$here/../deploy/visioncall-backup.service")"
backup_timer="$(pick "$here/visioncall-backup.timer" "$here/../deploy/visioncall-backup.timer")"

dir=/opt/visioncall
id visioncall >/dev/null 2>&1 || useradd --system --home-dir "$dir" --no-create-home --shell /usr/sbin/nologin visioncall

# Earlier releases were called "videocall". Move that install over so its
# settings and data carry on under the new name.
if [ -d /opt/videocall ] && [ ! -e "$dir" ]; then
  echo "==> moving the previous install from /opt/videocall to $dir"
  systemctl disable --now videocall videocall-backup.timer 2>/dev/null || true
  rm -f /etc/systemd/system/videocall.service /etc/systemd/system/videocall-backup.service /etc/systemd/system/videocall-backup.timer
  mv /opt/videocall "$dir"
  rm -f "$dir/videocall" "$dir/deploy/backup.sh"
  userdel videocall 2>/dev/null || true
fi
mkdir -p "$dir/data"

systemctl stop visioncall 2>/dev/null || true
install -m 0755 "$bin" "$dir/visioncall"
[ -f "$dir/.env" ] || install -m 0640 "$envex" "$dir/.env"
chown root:visioncall "$dir/.env"
chown -R visioncall:visioncall "$dir/data"

install -m 0644 "$unit" /etc/systemd/system/visioncall.service
# Daily database backup at 03:00, kept for 14 days.
install -D -m 0755 "$backup" "$dir/deploy/backup.sh"
install -m 0644 "$backup_svc" "$backup_timer" /etc/systemd/system/
systemctl daemon-reload
started="$(date '+%Y-%m-%d %H:%M:%S')"
systemctl enable --now visioncall visioncall-backup.timer

# Open the ports if a host firewall is running.
if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q "Status: active"; then
  ufw allow 8443/tcp >/dev/null && ufw allow 8080/tcp >/dev/null && ufw allow 7882/udp >/dev/null
  echo "==> opened ports 8443/tcp, 8080/tcp and 7882/udp in ufw"
elif command -v firewall-cmd >/dev/null && firewall-cmd --state >/dev/null 2>&1; then
  firewall-cmd --permanent --add-port=8443/tcp --add-port=8080/tcp --add-port=7882/udp >/dev/null
  firewall-cmd --reload >/dev/null
  echo "==> opened ports 8443/tcp, 8080/tcp and 7882/udp in firewalld"
fi

# The very first start prints a random admin password; show it here so nobody
# has to dig through the logs. Only this start's logs count, so an upgrade
# never shows an old password.
password=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  password="$(journalctl -u visioncall --since "$started" --no-pager 2>/dev/null | sed -n 's/.*Bootstrap admin password: \([^" ]*\).*/\1/p' | tail -1)"
  [ -n "$password" ] && break
  sleep 1
done
ip="$(hostname -I 2>/dev/null | awk '{print $1}')"

echo
echo "Vision Call $("$dir/visioncall" -version) is running."
echo "  Open:      https://${ip:-<this-server-ip>}:8443"
if [ -n "$password" ]; then
  echo "  Sign in:   admin / $password   (change it after signing in)"
else
  echo "  Sign in:   use your existing admin account"
fi
echo "  Settings:  $dir/.env, then: sudo systemctl restart visioncall"
echo "  Logs:      journalctl -u visioncall -f"
echo "  Backups:   $dir/data/backups (daily at 03:00)"
echo "  Uninstall: sudo systemctl disable --now visioncall visioncall-backup.timer"
echo "             sudo rm -rf $dir /etc/systemd/system/visioncall*.service /etc/systemd/system/visioncall-backup.timer"
