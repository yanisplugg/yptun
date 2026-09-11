# Builds the OpenFlux exit node (the same program run with --exit-node) for linux amd64 + arm64, gzips
# each and drops them into the Android app assets so the in-app OpenFlux VPS installer can push the
# right binary (the desktop build takes them from there too). Run from anywhere.
#
#   pwsh -File build-openflux-server.ps1
#
# The CLIENT binaries are built by Gradle (androidApp: buildOpenFluxAndroid, desktopApp: buildOpenFluxHost).

$ErrorActionPreference = "Stop"
$go = if (Test-Path "C:\Program Files\Go\bin\go.exe") { "C:\Program Files\Go\bin\go.exe" } else { "go" }
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$assets = Join-Path $here "..\YPtun\androidApp\src\main\assets\openflux"
New-Item -ItemType Directory -Force $assets | Out-Null

Push-Location $here
try {
    $env:CGO_ENABLED = "0"
    foreach ($arch in @("amd64", "arm64")) {
        $env:GOOS = "linux"; $env:GOARCH = $arch
        $bin = Join-Path $here "openflux-server-linux-$arch"
        & $go build -trimpath -ldflags="-s -w -checklinkname=0" -o $bin .
        if ($LASTEXITCODE -ne 0) { throw "build failed for $arch" }

        $gz = Join-Path $assets "openflux-server-linux-$arch.gz"
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
