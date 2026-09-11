# Builds the wdtt-server (linux amd64 + arm64), gzips each and drops them into the Android
# app assets so the in-app WDTT VPS installer can push the right binary. Run from anywhere.
#
#   pwsh -File build-wdtt-server.ps1
#
# Requires Go (1.25+). Server source = server/ of github.com/SpaceNeuroX/proxy-turn-vk-android (qWDTT, GPLv3),
# vendored here with its module renamed to wdtt-server — see WDTT-SERVER-README.md. Installed on the VPS
# by that project's own deploy.sh (assets/wdtt/deploy.sh), driven by SshWdttServerInstaller.

$ErrorActionPreference = "Stop"
$go = if (Test-Path "C:\Program Files\Go\bin\go.exe") { "C:\Program Files\Go\bin\go.exe" } else { "go" }
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$assets = Join-Path $here "..\YPtun\androidApp\src\main\assets\wdtt"
New-Item -ItemType Directory -Force $assets | Out-Null

Push-Location $here
try {
    $env:CGO_ENABLED = "0"
    foreach ($arch in @("amd64", "arm64")) {
        $env:GOOS = "linux"; $env:GOARCH = $arch
        $bin = Join-Path $here "wdtt-server-linux-$arch"
        & $go build -trimpath -ldflags="-s -w" -o $bin .
        if ($LASTEXITCODE -ne 0) { throw "build failed for $arch" }

        $gz = Join-Path $assets "wdtt-server-linux-$arch.gz"
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
