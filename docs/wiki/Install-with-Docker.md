# Install with Docker

Vision Call publishes a ready-made image for both regular PCs and ARM boards such as the Raspberry Pi:

```
ghcr.io/anand34577/video-call:latest
```

It works with Docker on Linux, Docker Desktop on Windows and macOS, and Podman.

## One command

```bash
docker run -d --name videocall --restart unless-stopped \
  -p 8443:8443 -p 8080:8080 -p 7882:7882/udp \
  -v videocall-data:/data \
  ghcr.io/anand34577/video-call:latest
```

On Windows PowerShell, put it on one line or replace each `\` with a backtick (`` ` ``).

Get the first admin password:

```bash
docker logs videocall 2>&1 | grep -i password
```

Open `https://<this-computer's-ip>:8443` from any device on your network and sign in as `admin`.

## Docker Compose

Download the compose file and start it:

```bash
curl -fsSL https://raw.githubusercontent.com/anand34577/video-call/main/docker-compose.yml -o docker-compose.yml
docker compose up -d
docker compose logs app | grep -i password
```

On Windows PowerShell, download it with:

```powershell
irm https://raw.githubusercontent.com/anand34577/video-call/main/docker-compose.yml -OutFile docker-compose.yml
```

To change a setting, create a file called `.env` next to `docker-compose.yml` with the lines you need (see [Configuration](Configuration) or the full list in [.env.example](https://github.com/anand34577/video-call/blob/main/.env.example)), then run `docker compose up -d` again.

To pin a version instead of following `latest`, add `VIDEOCALL_VERSION=v1.0.1` to `.env`.

## What the ports do

| Port | Purpose |
|---|---|
| `8443/tcp` | The web app over HTTPS. Browsers need HTTPS for camera and microphone. |
| `8080/tcp` | The web app over plain HTTP. Used by the Android app and health checks. |
| `7882/udp` | Audio and video for group calls. |

You can change the port on the left of `8443:8443` or `8080:8080` freely, for example `-p 9443:8443`. The UDP port is different: both numbers must match, and the app must know it. To use `7000`, set `-e WEBRTC_UDP_PORT=7000 -p 7000:7000/udp`.

## Where the data lives

Everything (database, uploaded files, the certificate and the session key) is stored in the `videocall-data` volume. Removing and recreating the container keeps it. To remove the data as well, run `docker volume rm videocall-data`.

Prefer a folder you can see? Use `-v ./videocall-data:/data` instead. The container runs as user ID 10001, so give it the folder first:

```bash
mkdir videocall-data && sudo chown 10001:10001 videocall-data
```

## Optional: TURN relay

Most networks don't need this. Add it only if calls fail between devices on different subnets or across a VPN mesh (see [Networking and Firewall](Networking-and-Firewall)).

1. Add these lines to `.env`, using this computer's LAN IP and a long random secret:

   ```
   EXTERNAL_IP=192.168.1.50
   TURN_HOST=192.168.1.50:3478
   TURN_SECRET=put-a-long-random-string-here
   ```

2. Start the relay alongside the app:

   ```bash
   docker compose --profile turn up -d
   ```

The relay uses host networking, which works on Linux. On Docker Desktop, run coturn directly on the host instead.

## Upgrade

```bash
docker compose pull && docker compose up -d
```

Or with plain `docker run`:

```bash
docker pull ghcr.io/anand34577/video-call:latest
docker rm -f videocall
# then run the same "docker run" command again
```

Your data stays in the volume. See [Backups and Upgrades](Backups-and-Upgrades) for backups.

## Build the image yourself

```bash
git clone https://github.com/anand34577/video-call.git
cd video-call
docker build -t videocall .
```

Then use `videocall` instead of `ghcr.io/anand34577/video-call:latest` in the commands above.
