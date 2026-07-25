# Builds the multilingual (en/ru/zh/fa) Windows installer from the Compose app image.
#
# Run :desktopApp:createDistributable first - this only packages what it produced. See yptun.iss for
# why the installer is Inno Setup rather than jpackage's WiX/MSI wrapper.
#
# Kept strictly ASCII: Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI, so a stray em dash in a
# string literal breaks the parse.
#
#   powershell -File build-installer.ps1 [-Version 3.1.1] [-OutDir <dir>]

param(
    [string]$Version = "",
    [string]$AppDir  = "",
    [string]$OutDir  = "",
    # amd64 | arm64. Defaults to the machine building it, which is what createDistributable produced.
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
if ($Arch -ne "amd64" -and $Arch -ne "arm64") { throw "Unsupported -Arch '$Arch' (amd64|arm64)" }

# Guard against shipping an app image built for the other architecture: the bundled core .dll is the
# arch-specific part, and a mismatch only shows up at runtime as a JNA load failure.
if (-not (Test-Path (Join-Path $AppDir "app\resources\native\yptuncore-windows-$Arch.dll"))) {
    $found = Get-ChildItem (Join-Path $AppDir "app") -Recurse -Filter "yptuncore-windows-*.dll" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty Name
    if ($found) { Write-Warning "App image carries '$found' but -Arch is '$Arch'" }
}

if (-not (Test-Path (Join-Path $AppDir "YPtun.exe"))) {
    throw "App image not found at $AppDir - run :desktopApp:createDistributable first"
}

$iscc = @(
    "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
    "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) { throw "ISCC.exe not found - install Inno Setup 6 (winget install JRSoftware.InnoSetup)" }

New-Item -ItemType Directory -Force $OutDir | Out-Null
Write-Host "Building YPtun $Version $Arch installer from $AppDir"
& $iscc "/DAppVersion=$Version" "/DAppDir=$AppDir" "/DOutDir=$OutDir" "/DAppArch=$Arch" (Join-Path $here "yptun.iss")
if ($LASTEXITCODE -ne 0) { throw "ISCC failed with exit code $LASTEXITCODE" }

$suffix = if ($Arch -eq "arm64") { "arm64" } else { "x64" }
Write-Host "Installer: $(Join-Path $OutDir ("YPtun-$Version-$suffix-installer.exe"))"
