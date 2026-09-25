# Backups and Upgrades

## What to back up

Everything lives in the data folder:

| Item | What it is |
|---|---|
| `visioncall.db` | The database: accounts, messages, groups, rooms, call history, settings |
| `files/` | Uploaded files and images |
| `jwt_secret` | The key that signs logins. Without it, everyone has to sign in again. |
| `cert.pem`, `key.pem` | The automatic certificate. Without them, devices that trusted it will warn again. |

The data folder is `/opt/visioncall/data` on Linux, `C:\ProgramData\VisionCall\data` on Windows, and the `visioncall-data` volume with Docker.

## Back up the database safely

Copying `visioncall.db` while the server is running can give you a damaged copy. Use the built-in backup command instead. It makes a consistent copy while the server keeps running.

**Linux** (the installer already runs this daily at 03:00, keeping 14 days in `/opt/visioncall/data/backups`):

```bash
sudo systemctl start visioncall-backup     # run a backup now
ls /opt/visioncall/data/backups
```

**Docker:**

```bash
docker exec visioncall visioncall-server -backup /data/backup.db
docker cp visioncall:/data/backup.db ./visioncall-backup.db
docker cp visioncall:/data/files ./visioncall-files
```

**Windows** (PowerShell as Administrator):

```powershell
cd C:\ProgramData\VisionCall
.\visioncall.exe -backup data\backup.db
```

Then copy `backup.db` and the `files` folder somewhere safe. With Task Scheduler you can run the same command daily.

**Postgres or MySQL:** if you set `DATABASE_URL`, use `pg_dump` or `mysqldump` as usual. The `-backup` command only covers the built-in SQLite database. Back up the `files/` folder either way.

## Restore

1. Stop Vision Call.
2. Replace `visioncall.db` in the data folder with your backup copy (named `visioncall.db`), and put back `files/` if needed.
3. Delete any `visioncall.db-wal` and `visioncall.db-shm` files next to it.
4. Start Vision Call.

With Docker, stop the container, copy the files into the volume with a temporary container, then start it again:

```bash
docker stop visioncall
docker run --rm -v visioncall-data:/data -v "$PWD":/backup alpine \
  sh -c "cp /backup/visioncall-backup.db /data/visioncall.db && rm -f /data/visioncall.db-wal /data/visioncall.db-shm && chown 10001:10001 /data/visioncall.db"
docker start visioncall
```

## Upgrade

Upgrades keep your data and settings. The database is updated automatically on the first start of the new version. Taking a backup first is still a good habit.

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
