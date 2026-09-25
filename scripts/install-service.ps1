#requires -RunAsAdministrator
# Install Vision Call as a Windows service ("VisionCall", auto-start).
#
# From an extracted release archive (videocall.exe and .env.example next to
# this script):
#   .\install-service.ps1
# From a source checkout:
#   .\scripts\install-service.ps1 -BinPath dist\videocall-server-windows-amd64.exe
#
# Everything lives in -InstallDir: the exe, .env, data\ and videocall.log.
# Re-running upgrades the exe in place; an existing .env is kept.
# Uninstall: Stop-Service VisionCall; sc.exe delete VisionCall
param(
    [string]$BinPath = (Join-Path $PSScriptRoot "videocall.exe"),
    [string]$InstallDir = (Join-Path $env:ProgramData "VisionCall")
)
$ErrorActionPreference = "Stop"

$envExample = @((Join-Path $PSScriptRoot ".env.example"), (Join-Path $PSScriptRoot "..\.env.example")) |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not (Test-Path $BinPath)) { throw "binary not found at '$BinPath' (pass -BinPath)" }
if (-not $envExample) { throw ".env.example not found next to this script" }

$name = "VisionCall"
$exe = Join-Path $InstallDir "videocall.exe"
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
# .env holds JWT_SECRET: only Administrators + SYSTEM (the service account).
icacls $InstallDir /inheritance:r /grant:r "*S-1-5-32-544:(OI)(CI)F" "*S-1-5-18:(OI)(CI)F" | Out-Null

if (Get-Service $name -ErrorAction SilentlyContinue) { Stop-Service $name -Force }
Copy-Item $BinPath $exe -Force

$envFile = Join-Path $InstallDir ".env"
if (-not (Test-Path $envFile)) {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $secret = -join ($bytes | ForEach-Object { $_.ToString("x2") })
    (Get-Content $envExample) -replace '^JWT_SECRET=$', "JWT_SECRET=$secret" |
        Set-Content $envFile -Encoding ascii
    Write-Host "==> wrote $envFile (random JWT_SECRET) - set EXTERNAL_IP in it"
}

if (-not (Get-Service $name -ErrorAction SilentlyContinue)) {
    New-Service -Name $name -BinaryPathName "`"$exe`" -addr :8443" -DisplayName "Vision Call" `
        -Description "Self-hosted LAN chat + video calling" -StartupType Automatic | Out-Null
    # WebRTC uses dynamic UDP ports, so allow the program rather than a port list.
    New-NetFirewallRule -DisplayName "Vision Call" -Direction Inbound -Program $exe -Action Allow | Out-Null
}
Start-Service $name

Write-Host "==> installed $(& $exe -version) as service '$name'"
Write-Host "    logs + first-boot admin password: $(Join-Path $InstallDir 'videocall.log')"
