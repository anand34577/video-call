# Install on macOS

A prebuilt program is available for Apple Silicon Macs (M1 and newer). On Intel Macs, use [Docker](Install-with-Docker).

Macs usually aren't left running as servers, so the macOS install is kept simple: it puts a `visioncall` command on your Mac, and you start it when you need it.

## One command

```bash
curl -fsSL https://raw.githubusercontent.com/anand34577/vision-call/main/scripts/get.sh | sudo sh
```

This downloads the latest release, checks its checksum and installs the program to `/usr/local/bin/visioncall`.

## Start it

```bash
mkdir -p ~/visioncall && cd ~/visioncall
visioncall
```

The first start prints the admin password. Open `https://localhost:8443` on the Mac, or `https://<the-mac's-ip>:8443` from other devices. The data lives in `~/visioncall/data`. Press `Ctrl+C` to stop it.

macOS asks whether to accept incoming network connections the first time; choose **Allow**.

## If macOS blocks the program

If you downloaded the archive in a browser rather than with the command above, macOS may refuse to open it because it wasn't downloaded from the App Store. Clear the quarantine flag once:

```bash
xattr -d com.apple.quarantine ./visioncall
```

## Upgrade or uninstall

Run the install command again to upgrade. To uninstall, delete `/usr/local/bin/visioncall` and the `~/visioncall` folder.
