# Install on macOS

A prebuilt program is available for Apple Silicon Macs (M1 and newer). On Intel Macs, use [Docker](Install-with-Docker).

Macs usually aren't left running as servers, so the macOS install is kept simple: it puts a `videocall` command on your Mac, and you start it when you need it.

## One command

```bash
curl -fsSL https://raw.githubusercontent.com/anand34577/video-call/main/scripts/get.sh | sudo sh
```

This downloads the latest release, checks its checksum and installs the program to `/usr/local/bin/videocall`.

## Start it

```bash
mkdir -p ~/videocall && cd ~/videocall
videocall
```

The first start prints the admin password. Open `https://localhost:8443` on the Mac, or `https://<the-mac's-ip>:8443` from other devices. The data lives in `~/videocall/data`. Press `Ctrl+C` to stop it.

macOS asks whether to accept incoming network connections the first time; choose **Allow**.

## If macOS blocks the program

If you downloaded the archive in a browser rather than with the command above, macOS may refuse to open it because it wasn't downloaded from the App Store. Clear the quarantine flag once:

```bash
xattr -d com.apple.quarantine ./videocall
```

## Upgrade or uninstall

Run the install command again to upgrade. To uninstall, delete `/usr/local/bin/videocall` and the `~/videocall` folder.
