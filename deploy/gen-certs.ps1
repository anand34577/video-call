# Generate a local CA + HTTPS server certificate for Vision Call (Windows).
# Uses mkcert when on PATH, otherwise openssl (bundled with Git for Windows).
#
# Usage:
#   .\gen-certs.ps1 [-OutDir .\certs] [-Sans @("visioncall.lan","IP:192.168.1.50")]
# Without SANs defaults to hostname, localhost, and all local IPv4 addresses.
param(
  [string]$OutDir = ".\certs",
  [string[]]$Sans = @()
)

$ErrorActionPreference = "Stop"

if ($Sans.Count -eq 0) {
  $Sans += $env:COMPUTERNAME.ToLower()
  $Sans += "localhost"
  Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -ne "" } |
    ForEach-Object { $Sans += "IP:$($_.IPAddress)" }
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path
Push-Location $OutDir

$mkcert = Get-Command mkcert -ErrorAction SilentlyContinue
if ($mkcert) {
  Write-Host "==> mkcert found; generating + installing a local CA"
  mkcert -install
  mkcert -cert-file server.crt -key-file server.key @Sans
  Write-Host "==> Done. Root CA location: $(mkcert -CAROOT)"
  Pop-Location
  exit 0
}

# openssl: prefer PATH, then Git for Windows bundle
$openssl = Get-Command openssl -ErrorAction SilentlyContinue
if (-not $openssl) {
  $gitOpenssl = Join-Path $env:ProgramFiles "Git\usr\bin\openssl.exe"
  if (-not (Test-Path $gitOpenssl)) {
    throw "openssl not found (looked on PATH and at $gitOpenssl). Install Git for Windows or openssl, or install mkcert."
  }
  $openssl = $gitOpenssl
}

Write-Host "==> openssl fallback: creating local CA"
$primary = $Sans[0] -replace "^IP:", ""

& $openssl genrsa -out ca.key 2048
& $openssl req -x509 -new -nodes -key ca.key -sha256 -days 3650 `
  -out ca.crt -subj "/CN=Vision Call Local CA"

Write-Host "==> creating server key + cert for: $($Sans -join ', ')"
& $openssl genrsa -out server.key 2048
& $openssl req -new -key server.key -out server.csr -subj "/CN=$primary"

$sanParts = $Sans | ForEach-Object {
  if ($_ -like "IP:*") { "IP:" + $_.Substring(3) }
  elseif ($_ -like "DNS:*") { "DNS:" + $_.Substring(4) }
  else { "DNS:$_" }
}
@"
[req]
distinguished_name = req
[SAN]
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = $($sanParts -join ",")
"@ | Set-Content -Encoding ascii san.ext

& $openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial `
  -out server.crt -days 825 -sha256 -extfile san.ext -extensions SAN
Remove-Item server.csr, san.ext, ca.srl -ErrorAction SilentlyContinue

Pop-Location
Write-Host "==> Done. Files in $OutDir"
Write-Host "    server.crt / server.key -> TLS_CERT / TLS_KEY"
Write-Host "    ca.crt                  -> install on every client device"
