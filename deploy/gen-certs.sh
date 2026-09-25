#!/usr/bin/env bash
# Generate a local Certificate Authority + HTTPS server certificate for
# Vision Call. Use mkcert when available, openssl otherwise.
#
# Usage:
#   ./gen-certs.sh [output-dir] [SAN ...]
#
# SAN examples:  ./gen-certs.sh ../data/certs visioncall.lan IP:192.168.1.50
# Without SANs the script defaults to this machine's hostname, localhost,
# and every detected LAN IP.
set -euo pipefail

OUT="${1:-./certs}"
shift || true

SANS=("$@")
if [ ${#SANS[@]} -eq 0 ]; then
  SANS+=("$(hostname)" "localhost")
  ips=""
  if command -v ip >/dev/null 2>&1; then
    ips=$(ip -4 -o addr show 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | grep -v '^127\.' || true)
  elif command -v ifconfig >/dev/null 2>&1; then
    ips=$(ifconfig 2>/dev/null | grep -o 'inet [0-9.]*' | awk '{print $2}' | grep -v '^127\.' || true)
  fi
  for ip in $ips; do SANS+=("IP:$ip"); done
fi

mkdir -p "$OUT"
cd "$OUT"

if command -v mkcert >/dev/null 2>&1; then
  echo "==> mkcert found; generating + installing a local CA"
  mkcert -install
  mkcert -cert-file server.crt -key-file server.key "${SANS[@]}"
  echo "==> Done. mkcert's root CA is already trusted on this machine."
  echo "    For other devices, copy the rootCA.pem (mkcert -CAROOT shows its"
  echo "    location) and trust it — see the wiki page 'HTTPS and Certificates'."
  exit 0
fi

echo "==> openssl fallback: creating local CA"
PRIMARY="${SANS[0]}"
PRIMARY="${PRIMARY#IP:}"

openssl genrsa -out ca.key 2048
openssl req -x509 -new -nodes -key ca.key -sha256 -days 3650 \
  -out ca.crt -subj "/CN=Vision Call Local CA"

echo "==> creating server key + cert for: ${SANS[*]}"
openssl genrsa -out server.key 2048
openssl req -new -key server.key -out server.csr -subj "/CN=$PRIMARY"

SAN_CONF="[req]\ndistinguished_name=req\n[SAN]\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName="
first=1
for s in "${SANS[@]}"; do
  if [ $first -eq 1 ]; then first=0; else SAN_CONF+=","; fi
  case "$s" in
    IP:*) SAN_CONF+="IP:${s#IP:}" ;;
    DNS:*) SAN_CONF+="DNS:${s#DNS:}" ;;
    *) SAN_CONF+="DNS:$s" ;;
  esac
done

printf '%b' "$SAN_CONF" > san.ext
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server.crt -days 825 -sha256 -extfile san.ext -extensions SAN
rm -f server.csr san.ext ca.srl

echo "==> Done. Files in $OUT:"
echo "    server.crt / server.key  -> TLS_CERT / TLS_KEY"
echo "    ca.crt                   -> install on every client device"
