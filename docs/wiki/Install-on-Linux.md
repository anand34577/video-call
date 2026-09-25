# Install on Linux

Works on any 64-bit Linux with systemd: Ubuntu, Debian, Fedora, Rocky, Raspberry Pi OS (64-bit) and so on, on both x86 (`amd64`) and ARM (`arm64`) machines.

## One command

```bash
curl -fsSL https://raw.githubusercontent.com/anand34577/video-call/main/scripts/get.sh | sudo sh
```

The installer:

1. Downloads the latest release for your CPU and checks it against the published checksum.
2. Creates a `videocall` system user and installs everything to `/opt/videocall`.
3. Starts the `videocall` service and enables it at boot.
4. Turns on a daily database backup at 03:00.
5. Opens ports 8443/tcp, 8080/tcp and 7882/udp if `ufw` or `firewalld` is active.
6. Prints the address to open and the first admin password.

To install a specific version:

```bash
curl -fsSL https://raw.githubusercontent.com/anand34577/video-call/main/scripts/get.sh | sudo VIDEOCALL_VERSION=v1.0.1 sh
```

## Install from a downloaded file

If the server can't reach the internet, download the right archive from the [releases page](https://github.com/anand34577/video-call/releases) on another machine: `videocall_<version>_linux-amd64.tar.gz` for most servers, or `linux-arm64` for ARM boards. Copy it over, then:

```bash
tar -xzf videocall_*_linux-amd64.tar.gz
cd videocall_*_linux-amd64
sudo ./install-linux.sh
```

## Where things are

| What | Where |
|---|---|
| Program | `/opt/videocall/videocall` |
| Settings | `/opt/videocall/.env` |
| Database, uploads, certificate | `/opt/videocall/data` |
| Daily backups | `/opt/videocall/data/backups` |
| Logs | `journalctl -u videocall -f` |

## Everyday commands

```bash
sudo systemctl status videocall     # is it running?
sudo systemctl restart videocall    # after editing /opt/videocall/.env
journalctl -u videocall -f          # follow the logs
/opt/videocall/videocall -version   # which version is installed
```

## Change a setting

Open `/opt/videocall/.env` in an editor, remove the `#` in front of the setting you want and change its value, then run `sudo systemctl restart videocall`. Many settings can also be changed without a restart from **Admin > Server Settings** in the app. See [Configuration](Configuration).

## Upgrade

Run the same one-line command again. It replaces the program and keeps your settings and data.

## Uninstall

```bash
sudo systemctl disable --now videocall videocall-backup.timer
sudo rm -f /etc/systemd/system/videocall.service /etc/systemd/system/videocall-backup.service /etc/systemd/system/videocall-backup.timer
sudo systemctl daemon-reload
sudo rm -rf /opt/videocall        # deletes all data, back it up first if you need it
sudo userdel videocall
```

## Run it without installing

The program also runs by itself, which is handy for a quick try:

```bash
tar -xzf videocall_*_linux-amd64.tar.gz
cd videocall_*_linux-amd64
./videocall
```

It keeps its data in a `data` folder next to where you started it and stops when you close the terminal.
