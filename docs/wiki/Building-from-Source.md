# Building from Source

You only need this to change Vision Call itself. To run it, use a [ready-made release](Quick-Start).

## What you need

- [Go](https://go.dev/dl/) 1.26 or newer
- [Node.js](https://nodejs.org/) 22 or newer (includes npm)
- For the Android app: JDK 17 and the Android SDK (Android Studio installs both)

## How the pieces fit

```
server/    Go server: web app, REST API, WebSocket signaling and the group-call SFU
web/       React + TypeScript web app, built with Vite and embedded into the server
android/   Native Android app (Kotlin, Jetpack Compose)
deploy/    systemd units, backup script, certificate scripts, coturn config
scripts/   build scripts and installers
docs/wiki/ the pages of this wiki
```

The web app is compiled into the server binary, so a release is one file with nothing else to install.

Inside `server/internal`:

| Package | Job |
|---|---|
| `api` | REST endpoints |
| `auth` | password hashing, sessions, rate limits, middleware |
| `config` | environment settings |
| `settings` | settings editable from the admin screen |
| `db` | SQLite, Postgres and MySQL access, with migrations for each |
| `signaling` | WebSocket hub: presence, chat, call signaling, rooms |
| `sfu` | group calls on [pion/webrtc](https://github.com/pion/webrtc) |
| `oidc`, `mail`, `ice` | single sign-on, password reset email, TURN credentials |

## Develop

Run the server and the web app's dev server side by side:

```bash
# terminal 1: the server on http://localhost:8080
cd server
DISABLE_TLS=true go run ./cmd/server

# terminal 2: the web app on http://localhost:5173, with hot reload
cd web
npm install
npm run dev
```

Open `http://localhost:5173`. Camera and microphone work on `localhost` without a certificate. The first admin password is printed in terminal 1.

## Build

Build the web app, embed it and compile binaries for every platform into `dist/`:

```bash
./scripts/build.sh                    # Linux and macOS
.\scripts\build.ps1                   # Windows
```

Build just some platforms:

```bash
./scripts/build.sh linux/amd64 windows/amd64
.\scripts\build.ps1 -Targets linux/arm64
```

No C compiler is needed, so every platform builds from any machine.

Build the Docker image:

```bash
docker build -t videocall .
```

Build the Android app (unsigned):

```bash
cd android
./gradlew assembleRelease
```

## Test

```bash
cd server && go vet ./... && go test ./...
cd web && npm run build          # also type-checks
cd android && ./gradlew compileDebugKotlin
```

The same checks run on every pull request.

## Database changes

Migrations live in `server/internal/db/migrations/<sqlite|postgres|mysql>/`. Add a new numbered file for each database; they run automatically on startup. Never edit a migration that has already been released.
