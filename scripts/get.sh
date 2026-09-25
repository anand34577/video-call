#!/bin/sh
# Vision Call one-line installer for Linux and macOS.
#
#   curl -fsSL https://raw.githubusercontent.com/anand34577/video-call/main/scripts/get.sh | sudo sh
#
# Linux: downloads the latest release for your CPU, checks its checksum and
# installs it as a systemd service (via the bundled install-linux.sh).
# macOS: installs the videocall command to /usr/local/bin.
#
# Install a specific version instead of the latest:
#   curl -fsSL .../get.sh | sudo VIDEOCALL_VERSION=v1.0.1 sh
set -eu

REPO="anand34577/video-call"

say() { printf '%s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null 2>&1 || die "curl is required"
command -v tar >/dev/null 2>&1 || die "tar is required"
[ "$(id -u)" = "0" ] || die "please run as root, e.g.: curl -fsSL https://raw.githubusercontent.com/$REPO/main/scripts/get.sh | sudo sh"

case "$(uname -s)" in
  Linux) os=linux ;;
  Darwin) os=darwin ;;
  *) die "unsupported system $(uname -s). On Windows use get.ps1, see the README" ;;
esac
case "$(uname -m)" in
  x86_64 | amd64) arch=amd64 ;;
  aarch64 | arm64) arch=arm64 ;;
  *) die "unsupported CPU $(uname -m). Releases cover amd64 and arm64, or try Docker" ;;
esac
[ "$os/$arch" = "darwin/amd64" ] && die "only Apple Silicon Macs have a prebuilt binary; use Docker on Intel Macs"

version="${VIDEOCALL_VERSION:-}"
if [ -z "$version" ]; then
  version="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" |
    sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -n 1)"
  [ -n "$version" ] || die "could not find the latest release; set VIDEOCALL_VERSION=vX.Y.Z"
fi

name="videocall_${version}_${os}-${arch}"
base="https://github.com/$REPO/releases/download/$version"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT INT TERM

say "==> Downloading Vision Call $version for $os/$arch"
curl -fsSL -o "$tmp/$name.tar.gz" "$base/$name.tar.gz" || die "download failed: $base/$name.tar.gz"

say "==> Checking the download"
curl -fsSL -o "$tmp/checksums.txt" "$base/checksums.txt" || die "could not download checksums.txt"
expected="$(grep " $name.tar.gz\$" "$tmp/checksums.txt" | cut -d' ' -f1)"
[ -n "$expected" ] || die "$name.tar.gz is not listed in checksums.txt"
if command -v sha256sum >/dev/null 2>&1; then
  actual="$(sha256sum "$tmp/$name.tar.gz" | cut -d' ' -f1)"
else
  actual="$(shasum -a 256 "$tmp/$name.tar.gz" | cut -d' ' -f1)"
fi
[ "$expected" = "$actual" ] || die "checksum mismatch, the download may be corrupted"

tar -xzf "$tmp/$name.tar.gz" -C "$tmp"

if [ "$os" = "linux" ]; then
  bash "$tmp/$name/install-linux.sh"
else
  install -m 0755 "$tmp/$name/videocall" /usr/local/bin/videocall
  say ""
  say "Vision Call $version is installed. Start it with:"
  say "  mkdir -p ~/videocall && cd ~/videocall && videocall"
  say "Then open https://localhost:8443 and sign in as admin with the password"
  say "printed on first start."
fi
