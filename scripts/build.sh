#!/usr/bin/env bash
# Build the single self-contained server binary for every supported platform:
# React UI -> go:embed -> one binary per GOOS/GOARCH, written to ./dist.
#
# Default targets (all five):
#   linux/amd64 linux/arm64 darwin/arm64 (Apple Silicon)
#   windows/amd64 windows/arm64
# Build a subset:  ./scripts/build.sh linux/arm64 windows/amd64
set -euo pipefail
cd "$(dirname "$0")/.."

targets=("$@")
if [ ${#targets[@]} -eq 0 ]; then
  targets=(linux/amd64 linux/arm64 darwin/arm64 windows/amd64 windows/arm64)
fi

command -v npm >/dev/null || { echo "error: npm is required on PATH" >&2; exit 1; }
command -v go >/dev/null || { echo "error: go is required on PATH" >&2; exit 1; }

echo "==> building web"
(cd web && npm ci && npm run build)

echo "==> embedding into server"
rm -rf server/static/dist
cp -r web/dist server/static/dist

mkdir -p dist
# VERSION env wins (CI passes the tag); else git describe; else "dev".
version="${VERSION:-$(git describe --tags --always --dirty 2>/dev/null || echo dev)}"
# Pure-Go toolchain (modernc.org/sqlite, no cgo): every target cross-compiles.
# go build -o never appends .exe, so windows names must carry it themselves.
for target in "${targets[@]}"; do
  GOOS="${target%/*}"
  GOARCH="${target#*/}"
  if [ "$GOOS" = "$target" ] || [ -z "$GOARCH" ]; then
    echo "error: invalid target '$target' (expected os/arch)" >&2
    exit 1
  fi
  name="videocall-server-$GOOS-$GOARCH"
  if [ "$GOOS" = "windows" ]; then
    name="$name.exe"
  fi
  echo "==> compiling $GOOS/$GOARCH -> dist/$name"
  (cd server && CGO_ENABLED=0 GOOS="$GOOS" GOARCH="$GOARCH" \
    go build -trimpath -ldflags="-s -w -X videocall/internal/config.Version=$version" -o "../dist/$name" ./cmd/server)
done

echo "==> done: binaries in ./dist"
