# Vision Call — Self-Hosted LAN/VPN Video Calling

A complete video calling + messaging platform that runs **entirely on your local
network**. No internet dependency, no cloud services, no third-party servers:
one Go binary (web UI, API, WebSocket signaling, and a pion-based SFU) plus an
embedded SQLite database. Docker + coturn (TURN relay) are available for
deployments that need them.

## Features

- **Admin-provisioned users** (no self-signup), JWT sessions in httpOnly
  cookies, argon2id password hashing, login rate limiting
- **Presence** — online / away (auto-away after 5 min idle) / Do Not Disturb
  (manual, silences notifications) / offline
- **1:1 direct messaging** with history, delivery/read receipts, typing
  indicators, offline delivery, file & image sharing
- **Group chats** (also used as conference rooms)
- **1:1 calls** — direct peer-to-peer WebRTC (DTLS-SRTP encrypted), ring /
  accept / decline, mute, camera toggle, screen share, device switching,
  automatic ICE restart on brief network drops
- **Conference calls** — built-in SFU (Selective Forwarding Unit) written with
  [pion/webrtc](https://github.com/pion/webrtc): participant grid, host
  mute-others, screen sharing, rejoin on reconnect. Default soft cap: 8
  participants (`MAX_CALL_PARTICIPANTS`)
- **Call history** — answered/missed, participants, duration
- **Admin dashboard** — user management, online/active-call counts, storage,
  full audit log (logins, user/group management, password changes)
- **SQLite by default, Postgres/MySQL if you want them** — set `DATABASE_URL`
  to point at either; no code changes, same migrations run automatically
- **Browser notifications** for calls and messages, dark/light theme,
  responsive layout for phone browsers, installable as an app (PWA —
  "Add to Home Screen" gives it its own icon and window)
- **Per-conversation message drafts** — switch chats mid-sentence and the
  unsent text is still there when you come back
- **HTTPS with a local CA** — camera/mic require a secure context; scripts are
  included to generate and distribute certificates

## Architecture

```
┌──────────────────────── one Go process ────────────────────────┐
│  HTTP/HTTPS (:8443)                                            │
│  ├── /api/*      REST: auth, users, messages, groups, files,   │
│  │               calls, ICE/TURN config, admin stats           │
│  ├── /ws         WebSocket: presence, chat, call signaling,    │
│  │               SFU offer/answer + ICE relay                  │
│  └── /*          React app (go:embed, served as static files)  │
│                                                                │
│  internal packages: auth · db (SQLite) · signaling hub · sfu   │
└────────────────────────────────────────────────────────────────┘
        optional companion: coturn (TURN relay for VPN/NAT cases)
```

- 1:1 calls are **mesh P2P** — media flows directly between browsers.
- 3+ participant calls go through the **SFU**: each participant keeps two
  PeerConnections to the server (publisher: client→SFU, subscriber: SFU→
  client). No simulcast; on a LAN/VPN this is fine up to the configured cap.
- SQLite (pure-Go driver, no cgo) stores users, sessions, messages, groups,
  files, and call records by default — zero setup, lives under `DATA_DIR` and
  survives restarts. Set `DATABASE_URL` to use Postgres or MySQL instead; any
  call left open by a restart is finalized at boot regardless of backend.

## Quick start — Docker (Linux server recommended)

```bash
cp .env.example .env
# edit .env: set JWT_SECRET (openssl rand -hex 32), EXTERNAL_IP,
#            TURN_SECRET (another long random string)

# 1. generate HTTPS certificates into ./data/certs
cd deploy && ./gen-certs.sh ../data/certs <hostname> IP:<EXTERNAL_IP> && cd ..

# 2. the container runs as uid 10001 and must be able to write ./data
#    (a bind mount keeps the host directory's owner)
sudo chown -R 10001:10001 data

# 3. start the stack (app + coturn)
docker compose up -d --build

# 4. read the generated admin password (first boot only)
docker compose logs app | grep -A4 "bootstrap"
```

Open `https://<EXTERNAL_IP>:8443` on any device on the network. See
**Trusting the CA** below for the one-time per-device step.

> WebRTC needs wide UDP access, so the app and coturn containers use
> `network_mode: host`. That works on Linux servers (the typical self-host
> target). On Windows/macOS Docker Desktop, host networking is not available —
> run the **single binary** natively instead (below).

Both `linux/amd64` and `linux/arm64` Docker hosts work out of the box — all
base images in the Dockerfile are multi-arch, and `docker compose up -d --build`
compiles for the machine it runs on. To cross-build an ARM image from an x86
host (or vice versa):

```bash
docker buildx build --platform linux/arm64 -t videocall:arm64 --load .
```

## Quick start — single binary

The binary embeds the web UI; SQLite and uploads live in `DATA_DIR`.

```bash
# build all five supported platforms into ./dist
./scripts/build.sh                 # Linux/macOS
.\scripts\build.ps1                # Windows
# subset only:  .\scripts\build.ps1 -Targets windows/amd64,linux/arm64

# certificates (one-time)
cd deploy
./gen-certs.sh ../data/certs myserver.local IP:192.168.1.50   # or gen-certs.ps1
cd ..

# run
JWT_SECRET=$(openssl rand -hex 32) \
EXTERNAL_IP=192.168.1.50 \
TLS_CERT=data/certs/server.crt TLS_KEY=data/certs/server.key \
DATA_DIR=data \
./dist/videocall-server-linux-amd64
```

Build targets: `linux/amd64`, `linux/arm64`, `darwin/arm64` (Apple Silicon),
`windows/amd64`, `windows/arm64` — pure Go with no cgo, so every target
cross-compiles from any machine with Go + npm installed.

A random admin password is printed to the console on first boot; control it
with `BOOTSTRAP_ADMIN_USER` / `BOOTSTRAP_ADMIN_PASSWORD`. Open
`https://<EXTERNAL_IP>:8080` — or set `LISTEN_ADDR=:8443` for the usual port.

The build scripts above already cross-compile every target, so e.g. build on a
Windows machine and deploy `videocall-server-linux-arm64` to an ARM server. To
compile one extra target by hand:
`CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -o videocall-server ./cmd/server`
(after the web UI has been embedded into `server/static/dist` by any script
run).

## HTTP vs HTTPS — four ways to run it

Browsers only allow camera/microphone on secure origins (HTTPS, or plain HTTP
from `localhost`), so how you handle TLS determines whether calling actually
works for your users. Pick the scenario that matches your setup:

**1. Plain HTTP only — simplest, calling won't work off localhost.**
Set `DISABLE_TLS=true`. One port (`LISTEN_ADDR`, default `:8080`), no
certificate of any kind. Chat, files, and admin all work fine everywhere;
camera/mic only work for someone browsing from `localhost` itself. Use this
for a quick trial, or when a reverse proxy in front of it isn't set up yet.

**2. Self-signed HTTPS — zero setup, one browser warning per device (default).**
Leave `TLS_CERT`/`TLS_KEY`/`DISABLE_TLS` unset. The app generates its own
certificate on first run and serves HTTPS on `LISTEN_ADDR` (default `:8443`)
**and** plain HTTP on `HTTP_ADDR` (default `:8080`) at the same time — camera
and mic work over the HTTPS port once you trust the certificate once per
device (see below); the HTTP port is there for anything that doesn't need
media. Set `HTTPS_REDIRECT=true` to make the HTTP port redirect to HTTPS
instead of also serving the app directly.

**3. Your own certificate.** Set `TLS_CERT`/`TLS_KEY` to a real certificate
(from your internal CA, or a public one if this box has a public hostname).
Same dual-port behavior as scenario 2, no browser warning, and no per-device
trust step. Add `HTTPS_REDIRECT=true` if you want plain HTTP visitors bounced
to HTTPS automatically.

**4. Behind a reverse proxy (nginx, Caddy, Traefik…).** Set `TRUST_PROXY=true`
and leave `TLS_CERT` unset. The app binds one plain-HTTP port (`LISTEN_ADDR`)
for the proxy to forward to; it never generates a certificate or opens a
second port, because the proxy owns TLS. The app reads `X-Forwarded-Proto`
from the proxy to mark cookies `Secure` correctly — make sure your proxy sets
it. If your proxy always terminates TLS and never forwards plain HTTP to the
app, you can set `PROXY_TLS=true` instead as a static override.

### Trusting a self-signed certificate (scenario 2)

Users will reach the app via `https://<LAN-IP>` or `https://<vpn-name>`, so on
**every client device** the local CA must be trusted **once**:

- The scripts create `data/certs/ca.crt` (the CA) plus `server.crt`/`server.key`.
- Pass every name/IP clients will use as SANs when generating.
- Install `ca.crt`:

| Device | How |
|---|---|
| Windows | double-click → Install Certificate → **Local Machine** → Place all certificates in the following store → **Trusted Root Certification Authorities** |
| macOS | Keychain Access → System → drag `ca.crt` in → double-click it → set Trust → Always Allow |
| iOS | AirDrop/mail the file → Settings profile installs → then Settings → General → About → Certificate Trust Settings → enable full trust |
| Android | Settings → Security → Install a certificate → CA certificate (varies by vendor) |
| Firefox | Options → Privacy & Security → Certificates → View Certificates → Authorities → Import… (Firefox keeps its own store) |

Alternative: install [mkcert](https://github.com/FiloSottile/mkcert) on the
server; the scripts auto-detect and use it.

## TURN / STUN on a VPN

Pure same-subnet LAN calls normally connect directly. Over VPN meshes or
multi-subnet networks, direct ICE can fail — the included **coturn** container
provides a local TURN relay with **no internet dependency**. The app fetches
short-lived TURN credentials (`GET /api/ice`) minted from the shared
`TURN_SECRET`; nothing needs to be reachable from outside your network.
Firewall notes: open `3478/udp+tcp` and `49160-49200/udp` for coturn, and the
HTTPS port plus ephemeral UDP for the app (host networking) or run the binary
natively where no container NAT exists.

## Configuration (environment, .env, and the Settings screen)

Three layers, highest priority first:

1. **A real environment variable** — set by your shell, systemd, or Docker's
   `environment:` block. Docker Compose's own `.env`-into-`environment:`
   substitution (if you use it) counts as this layer too, since it's
   resolved before the container starts.
2. **A `.env` file** — loaded by the binary itself on startup (default path
   `./.env`, override with `-env-file`). Only fills in variables that
   aren't already set by layer 1.
3. **The admin Settings screen** (Admin → Server Settings) — stored in the
   database, editable at runtime, no restart needed for most fields.

Anything not set by any of the three falls back to a hardcoded default.
Every setting is visible on the Settings screen regardless of which layer
set it, tagged with its source; one set by environment/`.env` shows a 🔒
and can't be edited there — change the environment and restart instead.
A handful of settings (ports, TLS, the database connection, `JWT_SECRET`,
OIDC) can *only* be set via environment/`.env` — they're needed before the
app can open its database to look anything else up, or are too
security-sensitive to leave editable at runtime. Everything else (TURN,
call size limits, upload limits/blocked extensions, SMTP, the public base
URL) is editable from the Settings screen when not pinned by the
environment.

| Variable | Default | Purpose |
|---|---|---|
| `LISTEN_ADDR` | `:8443` (`:8080` if `DISABLE_TLS`) | The app's primary port — see "HTTP vs HTTPS" below |
| `HTTP_ADDR` | `:8080` | Companion plain-HTTP port, active whenever TLS is active |
| `HTTPS_REDIRECT` | `false` | `true`: `HTTP_ADDR` only redirects to HTTPS; `false`: it also serves the app directly |
| `DISABLE_TLS` | `false` | Force plain HTTP only — no self-signed cert, no `HTTP_ADDR` companion port |
| `TLS_CERT` / `TLS_KEY` | — | Your own certificate; unset → a self-signed one is generated automatically (unless `DISABLE_TLS`/`TRUST_PROXY`) |
| `DATA_DIR` | `./data` | SQLite DB + `files/` uploads live here |
| `DATABASE_URL` | — | `postgres://…` or `mysql://…` to use that DB instead of sqlite; unset = sqlite in `DATA_DIR` |
| `JWT_SECRET` | ephemeral | Session signing key; **set it** or sessions die on restart |
| `SESSION_TTL_HOURS` | `12` | Login session lifetime |
| `BOOTSTRAP_ADMIN_USER` | `admin` | First account created when DB is empty |
| `BOOTSTRAP_ADMIN_PASSWORD` | random (printed once) | First admin password |
| `EXTERNAL_IP` | — | LAN/VPN IP the SFU advertises in ICE candidates; **set when behind NAT/container** |
| `TURN_HOST` | — | `host:port` of coturn advertised to clients (with `TURN_SECRET`) |
| `TURN_SECRET` | — | Shared coturn `static-auth-secret` |
| `MAX_CALL_PARTICIPANTS` | `8` | Conference soft cap |
| `MAX_FILE_MB` | `50` | Per-upload size limit |
| `MAX_USER_STORAGE_MB` | `2048` | Total upload storage allowed per user; `0` = unlimited |
| `BLOCKED_FILE_EXTENSIONS` | executables/scripts (see below) | Comma-separated list rejected on upload; `none` allows everything |

`BLOCKED_FILE_EXTENSIONS` defaults to `.exe,.bat,.cmd,.com,.scr,.msi,.msp,.ps1,.psm1,.vbs,.vbe,.js,.jse,.wsf,.wsh,.jar,.app,.dll,.sh,.bin,.cpl,.gadget,.hta,.lnk,.pif,.reg,.vb,.ws,.apk`
— documents, images, archives, media, etc. are all still allowed; this only
blocks the file types someone can double-click to run. Override with your
own list, or set it to `none` to allow every file type.

## Backups

**sqlite (default)**: `videocall -backup /path/to/backup.db` writes a
consistent, compacted point-in-time copy — safe to run while the server is
live. `deploy/backup.sh` wraps this for cron (writes timestamped files to
`DATA_DIR/backups`, prunes anything older than 14 days):

```bash
# crontab -e
0 3 * * * /opt/videocall/deploy/backup.sh
```

Running under systemd already (see below)? `deploy/videocall-backup.service`
+ `deploy/videocall-backup.timer` do the same thing as a systemd timer
instead of cron — no separate cron daemon needed, catches up automatically
if the machine was off at 3am:

```bash
sudo cp deploy/videocall-backup.service deploy/videocall-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now videocall-backup.timer
```

Uploaded files live under `DATA_DIR/files` — back that directory up too
(a plain file copy, not a DB backup).

**Postgres / MySQL**: use the standard tools instead — `pg_dump`/`mysqldump`
already do this better than a reimplementation would. `-backup` only applies
to the built-in sqlite database.

## Monitoring

`GET /api/healthz` — liveness check, no auth, returns `{"status":"ok"}`.

`GET /api/metrics` — Prometheus text-exposition format, no auth (same trust
model as `/healthz`; this is a LAN/VPN-only server, and Prometheus doesn't
send session cookies anyway). Exposes uptime, registered users, currently
online users, active calls, DB/files disk usage, and Go runtime stats
(goroutines, heap). Point a Prometheus `scrape_config` at it:

```yaml
- job_name: videocall
  static_configs:
    - targets: ["your-server:8443"]
  metrics_path: /api/metrics
```

## Running as a service

Both installers put everything in one directory (binary, `.env` with a
random `JWT_SECRET`, `data/`), keep an existing `.env` on re-run (so re-running
is also how you upgrade), and start the service. Edit `EXTERNAL_IP` in the
generated `.env`, then restart.

**Linux (systemd).** Installs to `/opt/videocall` as user `videocall`, using
`deploy/videocall.service`:

```bash
sudo ./install-linux.sh                                            # from a release archive
sudo ./scripts/install-linux.sh dist/videocall-server-linux-amd64  # from a checkout
journalctl -u videocall -f
```

**Windows.** Registers an auto-start `VisionCall` service in
`C:\ProgramData\VisionCall` (admin PowerShell). The exe handles the Service
Control Manager itself, no NSSM needed; logs go to `videocall.log` there:

```powershell
.\install-service.ps1                                                            # from a release archive
.\scripts\install-service.ps1 -BinPath dist\videocall-server-windows-amd64.exe  # from a checkout
```

Uninstall: `Stop-Service VisionCall; sc.exe delete VisionCall`.

## Releases

Pushing a `vX.Y.Z` tag runs `.github/workflows/release.yml`, which publishes:

- a GitHub Release with `videocall_<tag>_<os>-<arch>` archives (`.tar.gz`,
  `.zip` for Windows) for linux amd64/arm64, macOS arm64 and Windows
  amd64/arm64. Each archive has the binary, `.env.example`, this README and
  the platform's service installer. A `checksums.txt` file is included too
- a multi-arch (amd64 + arm64) image `ghcr.io/<owner>/<repo>:<tag>` and `:latest`

```bash
git tag v1.0.0 && git push origin v1.0.0
```

`videocall -version` prints the tag a binary was built from. To run the
published image instead of building it, replace `build:` with
`image: ghcr.io/<owner>/<repo>:latest` under `app` in `docker-compose.yml`
(the package is private until you make it public in the repo's Packages
settings).

## Development

```bash
# terminal 1 — backend on :8080 (localhost HTTP is a secure context,
# so camera/mic work for dev without certs)
cd server && go run ./cmd/server

# terminal 2 — Vite dev server on :5173 (proxies /api and /ws)
cd web && npm install && npm run dev
```

Layout:

```
server/                 Go module "videocall"
  cmd/server            entrypoint, TLS, static serving, bootstrap
  internal/config       env config
  internal/db           SQLite/Postgres/MySQL + embedded per-dialect migrations + queries
  internal/auth         argon2id, JWT sessions, middleware, limiter
  internal/api          REST handlers
  internal/signaling    WS hub: presence, chat, 1:1 calls, room bridging
  internal/sfu          pion SFU: rooms, publisher/subscriber PCs, forwarding
web/                    React 18 + TypeScript + Vite + Zustand + Tailwind v4
  public/               PWA manifest, icons, service worker
deploy/                 cert scripts, coturn reference config, systemd unit, cron backup script
scripts/                build the single binary
```

## Security notes

- Everything is treated as production: auth on every route, argon2id hashes,
  httpOnly SameSite=Lax cookies, origin checks on mutations, login rate
  limiting (5 fails / 15 min per user+IP), CSP + hardening headers.
- WebRTC media is always encrypted end-to-end via DTLS-SRTP. The SFU forwards
  RTP without persisting media.
- Chat messages are stored server-side in plain text (like most self-hosted
  chat); E2E encryption for chat is on the roadmap.
- Admins can disable/delete users at any time — sessions are revoked and
  sockets dropped immediately.

## Roadmap (not yet built)

Server-side recording, virtual backgrounds, E2E-encrypted chat, native mobile
(Electron/Tauri desktop + React Native or Capacitor wrappers — the HTTP/WS
contracts are wrapper-ready), federation between servers over VPN.

## Regenerating the embedded UI

`server/static/dist` is a build artifact. After editing `web/`, run
`scripts/build.sh` (or `.\scripts\build.ps1`), or `docker compose build`.
