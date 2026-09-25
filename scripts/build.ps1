# Build the single self-contained server binary for every supported platform:
# React UI -> go:embed -> one binary per GOOS/GOARCH, written to .\dist.
#
# Default targets (all five):
#   linux/amd64, linux/arm64, darwin/arm64 (Apple Silicon),
#   windows/amd64, windows/arm64
# Build a subset:  .\scripts\build.ps1 -Targets windows/amd64,linux/arm64
param(
    [string[]]$Targets = @(
        "linux/amd64",
        "linux/arm64",
        "darwin/arm64",
        "windows/amd64",
        "windows/arm64"
    )
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

foreach ($tool in @("npm", "go")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "$tool is required on PATH but was not found"
    }
}

Write-Host "==> building web UI"
Push-Location "$root\web"
try {
    npm ci
    if ($LASTEXITCODE -ne 0) { throw "npm ci failed" }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw "npm run build failed" }
} finally {
    Pop-Location
}

Write-Host "==> embedding UI into server/static/dist"
Remove-Item -Recurse -Force "$root\server\static\dist" -ErrorAction SilentlyContinue
Copy-Item -Recurse "$root\web\dist" "$root\server\static\dist"

$outDir = Join-Path $root "dist"
$version = if ($env:VERSION) { $env:VERSION } else { git -C $root describe --tags --always --dirty 2>$null }
if (-not $version) { $version = "dev" }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# The toolchain is pure Go here (modernc.org/sqlite, no cgo), so every target
# cross-compiles. go build -o with an explicit name never appends .exe, so
# windows binaries must carry the extension themselves.
$env:CGO_ENABLED = "0"
Push-Location "$root\server"
try {
    foreach ($target in $Targets) {
        $parts = $target -split "/"
        if ($parts.Count -ne 2 -or -not $parts[0] -or -not $parts[1]) {
            throw "invalid target '$target' (expected os/arch, e.g. linux/arm64)"
        }
        $goos = $parts[0]
        $goarch = $parts[1]
        $name = "videocall-server-$goos-$goarch"
        if ($goos -eq "windows") { $name += ".exe" }
        $env:GOOS = $goos
        $env:GOARCH = $goarch
        Write-Host "==> compiling $goos/$goarch -> dist\$name"
        go build -trimpath -ldflags="-s -w -X main.version=$version" -o (Join-Path $outDir $name) ./cmd/server
        if ($LASTEXITCODE -ne 0) { throw "go build failed for $goos/$goarch" }
    }
} finally {
    Remove-Item Env:GOOS, Env:GOARCH, Env:CGO_ENABLED -ErrorAction SilentlyContinue
    Pop-Location
}

Write-Host "==> done: binaries in $outDir (set TLS_CERT/TLS_KEY before running, see README)"
