# Builds the dnstt-server (linux amd64 + arm64), gzips each and drops them into the Android app
# assets so the in-app DNSTT VPS installer can push the right binary. Run from anywhere.
#
#   pwsh -File build-dnstt-server.ps1
#
# Requires Go (1.24+). Server source is main.go (upstream dnstt-server, www.bamsoftware.com) +
# socks.go (built-in SOCKS5 upstream). The dnstt protocol packages come from ../dnstt via a replace.

$ErrorActionPreference = "Stop"
$go = if (Test-Path "C:\Program Files\Go\bin\go.exe") { "C:\Program Files\Go\bin\go.exe" } else { "go" }
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$assets = Join-Path $here "..\YPtun\androidApp\src\main\assets\dnstt"
New-Item -ItemType Directory -Force $assets | Out-Null

Push-Location $here
try {
    $env:CGO_ENABLED = "0"
    $env:GOFLAGS = "-mod=mod"
    foreach ($arch in @("amd64", "arm64")) {
        $env:GOOS = "linux"; $env:GOARCH = $arch
        $bin = Join-Path $here "dnstt-server-linux-$arch"
        & $go build -trimpath -ldflags="-s -w" -o $bin .
        if ($LASTEXITCODE -ne 0) { throw "build failed for $arch" }

        $gz = Join-Path $assets "dnstt-server-linux-$arch.gz"
        $bytes = [System.IO.File]::ReadAllBytes($bin)
        $fs = [System.IO.File]::Create($gz)
        $gzs = New-Object System.IO.Compression.GzipStream($fs, [System.IO.Compression.CompressionLevel]::Optimal)
        $gzs.Write($bytes, 0, $bytes.Length); $gzs.Close(); $fs.Close()
        Remove-Item $bin
        Write-Host ("OK {0}: {1} -> {2} bytes -> {3}" -f $arch, $bytes.Length, (Get-Item $gz).Length, $gz)
    }
} finally {
    Pop-Location
}
