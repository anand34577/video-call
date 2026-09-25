# Vision Call one-line installer for Windows. In PowerShell run as
# Administrator:
#
#   irm https://raw.githubusercontent.com/anand34577/video-call/main/scripts/get.ps1 | iex
#
# Downloads the latest release for your CPU, checks its checksum and installs
# it as the "VisionCall" Windows service (via the bundled install-service.ps1).
# To install a specific version, set $env:VIDEOCALL_VERSION = "v1.0.1" first.
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest is very slow with the progress bar
$repo = "anand34577/video-call"

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) { throw "Please run PowerShell as Administrator and try again." }

$arch = if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64") { "arm64" } else { "amd64" }
$version = $env:VIDEOCALL_VERSION
if (-not $version) {
    $version = (Invoke-RestMethod "https://api.github.com/repos/$repo/releases/latest").tag_name
}

$name = "videocall_${version}_windows-$arch"
$base = "https://github.com/$repo/releases/download/$version"
$tmp = Join-Path ([IO.Path]::GetTempPath()) ([Guid]::NewGuid())
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    Write-Host "==> Downloading Vision Call $version for windows/$arch"
    Invoke-WebRequest "$base/$name.zip" -OutFile "$tmp\$name.zip" -UseBasicParsing
    Invoke-WebRequest "$base/checksums.txt" -OutFile "$tmp\checksums.txt" -UseBasicParsing

    Write-Host "==> Checking the download"
    $line = Get-Content "$tmp\checksums.txt" | Where-Object { $_ -match " $([regex]::Escape("$name.zip"))$" }
    if (-not $line) { throw "$name.zip is not listed in checksums.txt" }
    $expected = ($line -split " ")[0]
    $actual = (Get-FileHash "$tmp\$name.zip" -Algorithm SHA256).Hash.ToLower()
    if ($expected -ne $actual) { throw "Checksum mismatch, the download may be corrupted." }

    Expand-Archive "$tmp\$name.zip" -DestinationPath $tmp
    & "$tmp\$name\install-service.ps1"
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
