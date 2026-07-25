# Builds the single-file portable YPtun .exe from the Compose app image.
#
# Run :desktopApp:createDistributable first - this only packages what it produced.
#
# The portable is a 7-Zip self-extracting archive: 7z.sfx (GUI installer module) + a config telling
# it to unpack to a temp dir and launch YPtun.exe + the archive itself, concatenated. The stub gets
# the app icon patched in with rcedit, because a bare 7z.sfx shows a generic icon and warp-packer
# (the earlier attempt) produced a console window with no icon at all.
#
# The stub is x86 on every architecture - 7-Zip ships only an x86 7z.sfx, even in its x64 and ARM64
# packages. So on ARM64 the self-extractor runs emulated for the second it takes to unpack, and the
# app it then launches is fully native. The payload arch is what -Arch controls.
#
# Kept strictly ASCII: Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI.
#
#   powershell -File build-portable.ps1 [-Version 3.1.1] [-Arch amd64|arm64]

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

$sevenZip = @(
    "$env:ProgramFiles\7-Zip\7z.exe",
    "${env:ProgramFiles(x86)}\7-Zip\7z.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $sevenZip) { throw "7z.exe not found - install 7-Zip (winget install 7zip.7zip)" }
$sfxStub = Join-Path (Split-Path $sevenZip) "7z.sfx"
if (-not (Test-Path $sfxStub)) { throw "7z.sfx not found next to $sevenZip" }

$work = Join-Path $desktopApp "build\tmp\portable-$Arch"
New-Item -ItemType Directory -Force $work | Out-Null
New-Item -ItemType Directory -Force $OutDir | Out-Null

# rcedit patches the app icon into the SFX stub. Pinned release, cached between runs.
$rcedit = Join-Path $work "rcedit.exe"
if (-not (Test-Path $rcedit)) {
    Invoke-WebRequest "https://github.com/electron/rcedit/releases/download/v2.0.0/rcedit-x64.exe" -OutFile $rcedit
}

$stub = Join-Path $work "yptun-sfx.bin"
Copy-Item $sfxStub $stub -Force
& $rcedit $stub --set-icon (Join-Path $desktopApp "appIcons\WindowsIcon.ico")
if ($LASTEXITCODE -ne 0) { throw "rcedit failed with exit code $LASTEXITCODE" }

# GUIMode=2 keeps the extraction silent (no console, no progress window); RunProgram starts the app
# once the temp extraction completes.
$configPath = Join-Path $work "sfx_config.txt"
$config = ";!@Install@!UTF-8!`r`nTitle=`"YPtun`"`r`nRunProgram=`"YPtun.exe`"`r`nGUIMode=`"2`"`r`n;!@InstallEnd@!`r`n"
[IO.File]::WriteAllText($configPath, $config, (New-Object Text.UTF8Encoding $false))

$archive = Join-Path $work "app.7z"
Remove-Item $archive -Force -ErrorAction SilentlyContinue
Write-Host "Compressing app image ($Arch)..."
& $sevenZip a -mx=5 $archive "$AppDir\*" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "7z failed with exit code $LASTEXITCODE" }

$output = Join-Path $OutDir "YPtun-$Version-$suffix-portable.exe"
$out = [IO.File]::Create($output)
try {
    foreach ($part in @($stub, $configPath, $archive)) {
        $bytes = [IO.File]::ReadAllBytes($part)
        $out.Write($bytes, 0, $bytes.Length)
    }
} finally {
    $out.Close()
}

Write-Host "Portable: $output ($([math]::Round((Get-Item $output).Length / 1MB)) MB)"
