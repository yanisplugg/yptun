<#
.SYNOPSIS
  One-time setup for building olcbox (olcrtc + sing-box) on Windows.

  Clones the two native cores next to this repo, installs gomobile, and prints the
  environment you need. Run from anywhere:  powershell -ExecutionPolicy Bypass -File scripts\setup-build.ps1

  Prerequisites you must install manually first:
    - Android Studio (bundles JDK 17 + Android SDK)
    - In Android Studio > SDK Manager > SDK Tools: NDK 28.2.13676358 and CMake
    - Go (already present: go 1.25.x)
#>

$ErrorActionPreference = 'Stop'
$repoRoot   = Split-Path -Parent $PSScriptRoot          # ...\kaz\olcbox
$workspace  = Split-Path -Parent $repoRoot              # ...\kaz

Write-Host "Workspace: $workspace" -ForegroundColor Cyan

function Clone-IfMissing($url, $dir, $branch) {
    $path = Join-Path $workspace $dir
    if (Test-Path $path) {
        Write-Host "[ok] $dir already exists" -ForegroundColor Green
        return
    }
    Write-Host "[..] cloning $dir" -ForegroundColor Yellow
    if ($branch) { git clone --branch $branch --depth 1 $url $path }
    else         { git clone --depth 1 $url $path }
}

# 1. Native cores as siblings of olcbox (matches OLCRTC_REPO / SINGBOX_REPO defaults).
Clone-IfMissing 'https://github.com/openlibrecommunity/olcrtc.git' 'olcrtc' $null
Clone-IfMissing 'https://github.com/SagerNet/sing-box.git'        'sing-box' 'v1.12.25'

# 2. gomobile toolchain.
Write-Host "[..] installing gomobile/gobind" -ForegroundColor Yellow
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest

$goBin = Join-Path (go env GOPATH) 'bin'
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Cyan
Write-Host "  1. Ensure these are on PATH / set (current shell):"
Write-Host "       `$env:Path += ';$goBin'"
Write-Host "       `$env:ANDROID_HOME = `"`$env:LOCALAPPDATA\Android\Sdk`""
Write-Host "       `$env:ANDROID_NDK_HOME = `"`$env:ANDROID_HOME\ndk\28.2.13676358`""
Write-Host "  2. Initialise gomobile (once):  gomobile init"
Write-Host "  3. Build:  scripts\build-android.ps1"
