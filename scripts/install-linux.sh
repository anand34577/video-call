#!/usr/bin/env bash
# Install Vision Call as a systemd service under /opt/videocall.
#
# From an extracted release archive (binary, .env.example and
# videocall.service sit next to this script):
#   sudo ./install-linux.sh
# From a source checkout, pass the binary you built:
#   sudo ./scripts/install-linux.sh dist/videocall-server-linux-amd64
#
# Re-running upgrades the binary in place; an existing .env is kept.
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "error: run as root (sudo $0)" >&2; exit 1; }

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
pick() { for f in "$@"; do [ -f "$f" ] && { echo "$f"; return; }; done; echo "error: none of: $*" >&2; exit 1; }
bin="$(pick "${1:-$here/videocall}")"
unit="$(pick "$here/videocall.service" "$here/../deploy/videocall.service")"
envex="$(pick "$here/.env.example" "$here/../.env.example")"

dir=/opt/videocall
id videocall >/dev/null 2>&1 || useradd --system --home-dir "$dir" --no-create-home --shell /usr/sbin/nologin videocall
mkdir -p "$dir/data"

systemctl stop videocall 2>/dev/null || true
install -m 0755 "$bin" "$dir/videocall"

if [ ! -f "$dir/.env" ]; then
  secret="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  sed "s/^JWT_SECRET=\$/JWT_SECRET=$secret/" "$envex" > "$dir/.env"
  echo "==> wrote $dir/.env (random JWT_SECRET) - set EXTERNAL_IP in it"
fi
chown root:videocall "$dir/.env" && chmod 0640 "$dir/.env"
chown -R videocall:videocall "$dir/data"

install -m 0644 "$unit" /etc/systemd/system/videocall.service
systemctl daemon-reload
systemctl enable --now videocall

echo "==> installed $("$dir/videocall" -version). Logs: journalctl -u videocall -f"
echo "    first-boot admin password: journalctl -u videocall | grep -A4 bootstrap"
echo "    uninstall: systemctl disable --now videocall && rm -rf $dir /etc/systemd/system/videocall.service"
