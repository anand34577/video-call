# Backups and Upgrades

Vision Call has two kinds of backup:

| | What it protects | Who manages it |
|---|---|---|
| **Server backup** | The whole server: accounts, messages, groups, rooms, call history, settings and uploaded files | Admins, in **Admin > Backups** |
| **Chat backup** | Your ability to read your own end-to-end encrypted messages on a new phone or browser | Each person, in **Settings > Chat backup** |

## Server backup

Open **Admin > Backups**. Everything below works the same with Docker, on Linux and on Windows. It covers servers using the built-in database; with Postgres or MySQL, back up with `pg_dump` or `mysqldump` plus the `files` folder.

### Automatic daily backups

The server copies its database every day, a couple of minutes after it starts and then every 24 hours. The newest 7 copies are kept in the `backups` folder inside the data folder and listed on the Backups screen, where you can download or restore any of them. **Back up now** makes one straight away.

These daily copies cover the database only. For the uploaded files too, use a full backup.

### Full backup

**Download** saves one `.zip` with everything: the database, all uploaded files and the key that signs logins. Keep it somewhere other than the server itself.

### Restore

On the Backups screen, choose **Restore** next to a daily backup, or **Choose file** to upload a full backup (`.zip`) or a database backup (`.db`). Then:

1. The server checks the backup. It must open cleanly and contain at least one active admin, so you can't lock yourself out.
2. The current data is moved aside to a `pre-restore-<date>` folder inside the data folder. Nothing is deleted.
3. The server restarts with the restored data, and the page reloads once it's back. Everyone signs in again.

The server restarts itself by exiting, and Docker, the Linux service and the Windows service all start it again automatically. If you run the program by hand, start it again yourself.

A backup from one install restores onto another, for example from Docker onto a Linux server. File locations are adjusted automatically.

### From the command line

A consistent copy of the database can also be made while the server is running:

```bash
# Docker
docker exec visioncall visioncall-server -backup /data/backup.db
# Linux
cd /opt/visioncall && sudo -u visioncall ./visioncall -backup data/backup.db
```

## Chat backup

Encrypted messages are sealed separately for each of the recipient's devices, so a brand-new phone or browser can't read messages sent before it existed. Chat backup fixes that:

1. In **Settings > Chat backup**, choose **Turn on backup** and pick a backup password.
2. From then on, every encrypted message sent to you is also sealed for your backup.
3. When you sign in on a new device, Vision Call notices the backup and asks for the password. Enter it and your backed-up messages become readable there.

The backup key is stored on your server, locked with your password. Neither the server nor your administrator can read it or recover the password. Messages from before you turned the backup on aren't covered. The same backup works in the browser and in the Android app.

## Upgrade

Upgrades keep your data and settings. The database is updated automatically on the first start of the new version. Downloading a full backup first is a good habit.

| Installed with | Upgrade with |
|---|---|
| Docker Compose | `docker compose pull && docker compose up -d` |
| Docker run | `docker pull ghcr.io/anand34577/vision-call:latest`, then `docker rm -f visioncall` and run the same `docker run` command again |
| Linux installer | Run the [one-line installer](Install-on-Linux) again |
| Windows installer | Run the [one-line installer](Install-on-Windows) again |
| Android app | Install the newer APK over the old one |

Check the installed version:

- Linux: `/opt/visioncall/visioncall -version`
- Windows: `C:\ProgramData\VisionCall\visioncall.exe -version`
- Any install: open `https://<server>:8443/api/healthz`

Release notes are on the [releases page](https://github.com/anand34577/vision-call/releases).

## Uninstall

- [Docker](Install-with-Docker): `docker rm -f visioncall`, then `docker volume rm visioncall-data` to delete the data too
- [Linux](Install-on-Linux#uninstall)
- [Windows](Install-on-Windows#uninstall)
- [macOS](Install-on-macOS#upgrade-or-uninstall)
