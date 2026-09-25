# Quick Start

Pick one way to install. Each one takes a single command and needs no configuration.

## Option 1: Docker (any operating system)

If you have [Docker](https://docs.docker.com/get-docker/) installed, run:

```bash
docker run -d --name videocall --restart unless-stopped \
  -p 8443:8443 -p 8080:8080 -p 7882:7882/udp \
  -v videocall-data:/data \
  ghcr.io/anand34577/video-call:latest
```

Then get the admin password:

```bash
docker logs videocall 2>&1 | grep -i password
```

Prefer Docker Compose? See [Install with Docker](Install-with-Docker).

## Option 2: Linux server

```bash
curl -fsSL https://raw.githubusercontent.com/anand34577/video-call/main/scripts/get.sh | sudo sh
```

This installs Vision Call as a system service that starts on boot, opens the firewall ports and prints the address and admin password when it's done. Details: [Install on Linux](Install-on-Linux).

## Option 3: Windows

Open **PowerShell as Administrator** (right-click the Start button, then "Terminal (Admin)" or "Windows PowerShell (Admin)") and run:

```powershell
irm https://raw.githubusercontent.com/anand34577/video-call/main/scripts/get.ps1 | iex
```

This installs Vision Call as a Windows service that starts with Windows and prints the address and admin password when it's done. Details: [Install on Windows](Install-on-Windows).

## Open it

1. On any computer or phone on the same network, open `https://<server-ip>:8443`, for example `https://192.168.1.50:8443`.
2. Your browser warns that the connection isn't private. That's expected, because the server made its own certificate. Choose **Advanced**, then **Proceed** (the wording differs a little between browsers). To remove the warning for good, see [HTTPS and Certificates](HTTPS-and-Certificates).
3. Sign in as `admin` with the password from the install output or the logs.
4. Change the password, then create accounts for everyone else. See [First Steps](First-Steps).

Not sure of the server's IP address? On Linux run `hostname -I`, on Windows run `ipconfig`, and use the address that starts with `192.168.`, `10.` or `172.`.

## Android phones

Download the APK from the [latest release](https://github.com/anand34577/video-call/releases/latest) and install it. When it asks for a server, enter `http://<server-ip>:8080`. See [Android App](Android-App).
