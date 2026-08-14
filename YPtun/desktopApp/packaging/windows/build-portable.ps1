# Builds the single-file portable YPtun .exe from the Compose app image.
#
# Run :desktopApp:createDistributable first - this only packages what it produced.
#
# The portable is a native launcher (packaging/windows/portable-launcher, Go, no cgo) with the app
# image appended to it as a zip:
#
#     [ launcher .exe ][ app-image zip ][ uint64 zip size ][ "YPTUNPKG" ]
#
# The launcher unpacks that zip ONCE into %LOCALAPPDATA%\YPtun\portable\<version> and starts the app;
# every later launch finds the directory ready and starts immediately. The previous portable was a
# 7-Zip SFX that re-unpacked all 160 MB into a fresh temp directory on every single launch.
#
# Unlike the old 7z.sfx stub - which is x86 in every 7-Zip distribution, ARM64 included - this
# launcher is built for the target architecture, so an ARM64 portable is now ARM64 end to end.
#
# Needs: Go on PATH (or at C:\Program Files\Go\bin). 7-Zip is no longer required.
#
# Kept strictly ASCII: Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI.
#
#   powershell -File build-portable.ps1 [-Version 3.2.1] [-Arch amd64|arm64]

param(
    [string]$Version = "",
    [string]$AppDir  = "",
    [string]$OutDir  = "",
    [string]$Arch    = ""
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$desktopApp = (Resolve-Path (Join-Path $here "..\..")).Path

if (-not $Version) {
    $props = Get-Content (Join-Path $desktopApp "..\gradle.properties")
    $Version = ($props | Where-Object { $_ -match '^olcbox\.version=' }) -replace '^olcbox\.version=', ''
}
if (-not $AppDir) { $AppDir = Join-Path $desktopApp "build\compose\binaries\main\app\YPtun" }
if (-not $OutDir) { $OutDir = Join-Path $desktopApp "build\compose\binaries\main\exe" }
if (-not $Arch) {
    $Arch = if ($env:PROCESSOR_ARCHITECTURE -match 'ARM64') { "arm64" } else { "amd64" }
}
$suffix = if ($Arch -eq "arm64") { "arm64" } else { "x64" }

if (-not (Test-Path (Join-Path $AppDir "YPtun.exe"))) {
    throw "App image not found at $AppDir - run :desktopApp:createDistributable first"
}
# .NET resolves relative paths against its own working directory, not PowerShell's.
$AppDir = (Resolve-Path $AppDir).Path

$go = (Get-Command go -ErrorAction SilentlyContinue).Source
if (-not $go) {
    $candidate = "$env:ProgramFiles\Go\bin\go.exe"
    if (Test-Path $candidate) { $go = $candidate }
}
if (-not $go) { throw "go.exe not found - install Go or add it to PATH" }

$work = Join-Path $desktopApp "build\tmp\portable-$Arch"
New-Item -ItemType Directory -Force $work | Out-Null
New-Item -ItemType Directory -Force $OutDir | Out-Null

# 1. The launcher stub, built for the TARGET architecture (pure stdlib, so no cgo toolchain needed).
$stub = Join-Path $work "yptun-launcher.exe"
Push-Location (Join-Path $here "portable-launcher")
try {
    $env:GOOS = "windows"
    $env:GOARCH = $Arch
    $env:CGO_ENABLED = "0"
    & $go build -trimpath -ldflags "-s -w -H=windowsgui -X main.version=$Version" -o $stub .
    if ($LASTEXITCODE -ne 0) { throw "go build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
    Remove-Item Env:GOOS, Env:GOARCH, Env:CGO_ENABLED -ErrorAction SilentlyContinue
}

# 2. The app icon. Without it the portable shows the generic Go/console icon in Explorer and the
#    taskbar, which is what made the warp-packer attempt unusable.
$rcedit = Join-Path $work "rcedit.exe"
if (-not (Test-Path $rcedit)) {
    Invoke-WebRequest "https://github.com/electron/rcedit/releases/download/v2.0.0/rcedit-x64.exe" -OutFile $rcedit
}
& $rcedit $stub `
    --set-icon (Join-Path $desktopApp "appIcons\WindowsIcon.ico") `
    --set-version-string "ProductName" "YPtun" `
    --set-version-string "FileDescription" "YPtun portable" `
    --set-version-string "CompanyName" "YPtun" `
    --set-file-version $Version `
    --set-product-version $Version
if ($LASTEXITCODE -ne 0) { throw "rcedit failed with exit code $LASTEXITCODE" }

# 3. The payload: the app image as a plain zip (Deflate, so the launcher needs nothing but the Go
#    standard library to read it back).
$archive = Join-Path $work "app.zip"
Remove-Item $archive -Force -ErrorAction SilentlyContinue
Write-Host "Compressing app image ($Arch)..."
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $AppDir, $archive, [System.IO.Compression.CompressionLevel]::Optimal, $false)

# 4. stub + payload + trailer.
$output = Join-Path $OutDir "YPtun-$Version-$suffix-portable.exe"
$out = [IO.File]::Create($output)
try {
    foreach ($part in @($stub, $archive)) {
        $bytes = [IO.File]::ReadAllBytes($part)
        $out.Write($bytes, 0, $bytes.Length)
    }
    $payloadSize = [UInt64]((Get-Item $archive).Length)
    $trailer = New-Object byte[] 16
    [Array]::Copy([BitConverter]::GetBytes($payloadSize), 0, $trailer, 0, 8)
    [Array]::Copy([Text.Encoding]::ASCII.GetBytes("YPTUNPKG"), 0, $trailer, 8, 8)
    $out.Write($trailer, 0, $trailer.Length)
} finally {
    $out.Close()
}

Write-Host "Portable: $output ($([math]::Round((Get-Item $output).Length / 1MB)) MB)"
