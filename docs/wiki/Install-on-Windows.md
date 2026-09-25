# Install on Windows

Works on Windows 10, Windows 11 and Windows Server 2019 or newer, on both regular (`amd64`) and ARM (`arm64`) PCs.

## One command

1. Right-click the Start button and choose **Terminal (Admin)** or **Windows PowerShell (Admin)**.
2. Paste this and press Enter:

   ```powershell
   irm https://raw.githubusercontent.com/anand34577/video-call/main/scripts/get.ps1 | iex
   ```

The installer:

1. Downloads the latest release for your PC and checks it against the published checksum.
2. Installs it to `C:\ProgramData\VisionCall`.
3. Registers a **Vision Call** Windows service that starts automatically with Windows.
4. Adds a Windows Firewall rule so other devices can connect.
5. Prints the address to open and the first admin password.

To install a specific version, run `$env:VIDEOCALL_VERSION = "v1.0.1"` first, in the same window.

## Install from a downloaded file

1. Download `videocall_<version>_windows-amd64.zip` from the [releases page](https://github.com/anand34577/video-call/releases) (`windows-arm64` for ARM PCs such as Surface Pro X or Snapdragon laptops).
2. Right-click the zip, choose **Properties**, tick **Unblock** if you see it, then **Extract All**.
3. Open PowerShell as Administrator in the extracted folder and run:

   ```powershell
   Set-ExecutionPolicy -Scope Process Bypass
   .\install-service.ps1
   ```

## Where things are

| What | Where |
|---|---|
| Program | `C:\ProgramData\VisionCall\videocall.exe` |
| Settings | `C:\ProgramData\VisionCall\.env` |
| Database, uploads, certificate | `C:\ProgramData\VisionCall\data` |
| Log file | `C:\ProgramData\VisionCall\videocall.log` |

The folder is readable only by administrators, because it holds the key that signs everyone's logins. `C:\ProgramData` is hidden by default; type the path into File Explorer's address bar to open it.

## Everyday commands

Run these in PowerShell as Administrator, or use the **Services** app (`services.msc`) and look for **Vision Call**.

```powershell
Get-Service VisionCall                  # is it running?
Restart-Service VisionCall              # after editing the .env file
Get-Content C:\ProgramData\VisionCall\videocall.log -Tail 50 -Wait   # follow the log
```

## Change a setting

Open `C:\ProgramData\VisionCall\.env` in Notepad (as Administrator), remove the `#` in front of the setting you want and change its value, save, then run `Restart-Service VisionCall`. Many settings can also be changed without a restart from **Admin > Server Settings** in the app. See [Configuration](Configuration).

## Upgrade

Run the same one-line command again. It replaces the program and keeps your settings and data.

## Uninstall

```powershell
Stop-Service VisionCall
sc.exe delete VisionCall
Remove-NetFirewallRule -DisplayName "Vision Call"
Remove-Item -Recurse -Force C:\ProgramData\VisionCall   # deletes all data, back it up first if you need it
```

## Run it without installing

Double-click `videocall.exe` in the extracted folder, or run it from a terminal. It keeps its data in a `data` folder next to the program and stops when you close the window. Windows may ask whether to allow it through the firewall; allow it on private networks.
