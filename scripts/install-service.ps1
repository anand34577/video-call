#requires -RunAsAdministrator
# Install Vision Call as a Windows service ("VisionCall", starts with Windows).
#
# Most people should use the one-line installer instead, which downloads the
# latest release and runs this script for you (PowerShell as Administrator):
#   irm https://raw.githubusercontent.com/anand34577/vision-call/main/scripts/get.ps1 | iex
#
# From an extracted release archive (visioncall.exe and .env.example next to
# this script):
#   .\install-service.ps1
# From a source checkout:
#   .\scripts\install-service.ps1 -BinPath dist\visioncall-server-windows-amd64.exe
#
# Everything lives in -InstallDir: the exe, .env, data\ and visioncall.log.
# Running it again upgrades the exe in place and keeps your settings and data.
# Uninstall: Stop-Service VisionCall; sc.exe delete VisionCall
param(
    [string]$BinPath = (Join-Path $PSScriptRoot "visioncall.exe"),
    [string]$InstallDir = (Join-Path $env:ProgramData "VisionCall")
)
$ErrorActionPreference = "Stop"

$envExample = @((Join-Path $PSScriptRoot ".env.example"), (Join-Path $PSScriptRoot "..\.env.example")) |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not (Test-Path $BinPath)) { throw "binary not found at '$BinPath' (pass -BinPath)" }
if (-not $envExample) { throw ".env.example not found next to this script" }

$name = "VisionCall"
$exe = Join-Path $InstallDir "visioncall.exe"
$log = Join-Path $InstallDir "visioncall.log"
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
# The data folder holds the session signing key: only Administrators and
# SYSTEM (the service account) may read it.
icacls $InstallDir /inheritance:r /grant:r "*S-1-5-32-544:(OI)(CI)F" "*S-1-5-18:(OI)(CI)F" | Out-Null

if (Get-Service $name -ErrorAction SilentlyContinue) { Stop-Service $name -Force }
Copy-Item $BinPath $exe -Force
$envFile = Join-Path $InstallDir ".env"
if (-not (Test-Path $envFile)) { Copy-Item $envExample $envFile }

# Earlier releases named the program videocall.exe; clean those files up.
Remove-Item (Join-Path $InstallDir "videocall.exe") -ErrorAction SilentlyContinue
$oldLog = Join-Path $InstallDir "videocall.log"
if ((Test-Path $oldLog) -and -not (Test-Path $log)) { Rename-Item $oldLog $log }

if (Get-Service $name -ErrorAction SilentlyContinue) {
    & sc.exe config $name binPath= "`"$exe`"" | Out-Null
} else {
    New-Service -Name $name -BinaryPathName "`"$exe`"" -DisplayName "Vision Call" `
        -Description "Self-hosted chat and video calling" -StartupType Automatic | Out-Null
}
# WebRTC also uses UDP, so allow the program rather than a list of ports.
if (Get-NetFirewallRule -DisplayName "Vision Call" -ErrorAction SilentlyContinue) {
    Set-NetFirewallRule -DisplayName "Vision Call" -Program $exe
} else {
    New-NetFirewallRule -DisplayName "Vision Call" -Direction Inbound -Program $exe -Action Allow | Out-Null
}
$logStart = if (Test-Path $log) { (Get-Item $log).Length } else { 0 }
Start-Service $name

# The very first start prints a random admin password; show it here so nobody
# has to open the log file. Only lines written by this start count, so an
# upgrade never shows an old password.
$password = $null
foreach ($i in 1..10) {
    if (Test-Path $log) {
        $stream = [IO.File]::Open($log, "Open", "Read", "ReadWrite")
        try {
            [void]$stream.Seek($logStart, "Begin")
            $fresh = (New-Object IO.StreamReader($stream)).ReadToEnd()
        } finally { $stream.Close() }
        if ($fresh -match 'Bootstrap admin password: ([^" \r\n]+)') { $password = $Matches[1]; break }
    }
    Start-Sleep -Seconds 1
}
$ip = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notmatch '^(127\.|169\.254\.)' -and $_.PrefixOrigin -ne 'WellKnown' } |
    Select-Object -First 1).IPAddress
if (-not $ip) { $ip = "localhost" }

Write-Host ""
Write-Host "Vision Call $(& $exe -version) is running."
Write-Host "  Open:      https://${ip}:8443"
if ($password) {
    Write-Host "  Sign in:   admin / $password   (change it after signing in)"
} else {
    Write-Host "  Sign in:   use your existing admin account"
}
Write-Host "  Settings:  $envFile, then: Restart-Service $name"
Write-Host "  Logs:      $log"
Write-Host "  Uninstall: Stop-Service $name; sc.exe delete $name"
