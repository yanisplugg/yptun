<#
.SYNOPSIS
  Generates the desktop delta bundle for a release WITHOUT keeping every shipped app image around:
  the previous release's single-file portable .exe carries its whole app image, so the OLD side is
  recovered from that.

.DESCRIPTION
  make-desktop-patch.ps1 needs the app image of the PREVIOUS release, and nobody kept those - so no
  desktop delta bundle was ever published and every Windows update pulled the full ~230 MB.
  Every release does ship that image though: YPtun-<ver>-x64-portable.exe is the Go launcher with
  the app image appended as a zip ([ launcher ][ zip ][ uint64 zip size ]["YPTUNPKG"] and, when the
  file is Authenticode-signed, a certificate table after that). This script downloads it (or takes
  -OldPortable), cuts the zip out, and hands the result to make-desktop-patch.ps1 together with the
  freshly built image.

  The installed (Inno Setup) copy and the portable copy of one release are the same app image, so
  the bundle's base - the SHA-256 of app/YPtun.cfg, written into its file name - matches installs of
  both and the app picks the bundle by that hash.

  Run after building the new app image, before publishing the release, once per arch:

    ./make-desktop-patch-from-release.ps1 -FromVer 3.6.1 -ToVer 3.6.2 `
        -NewImage ..\..\desktopApp\build\compose\binaries\main-release\app\YPtun -OutDir C:\dist

  Upload the resulting YPtun-delta-<from>-<to>-windows-<arch>-<hash>.patch next to the installer.
  If make-desktop-patch.ps1 refuses (the JRE or the launcher changed) ship the full installer only.

  Kept strictly ASCII: Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI.
#>
param(
  [Parameter(Mandatory=$true)][string]$FromVer,
  [Parameter(Mandatory=$true)][string]$ToVer,
  [Parameter(Mandatory=$true)][string]$NewImage,
  [ValidateSet("amd64","arm64")][string]$Arch = "amd64",
  [string]$OutDir = ".",
  [string]$OldPortable = "",
  [string]$Repo = "yanisplugg/olcvpn-client"
)

$ErrorActionPreference = "Stop"
$here   = Split-Path -Parent $MyInvocation.MyCommand.Path
$suffix = if ($Arch -eq "arm64") { "arm64" } else { "x64" }
$work   = Join-Path ([IO.Path]::GetTempPath()) ("yptun-oldimage-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force $work | Out-Null

try {
  if (-not $OldPortable) {
    $OldPortable = Join-Path $work "old-portable.exe"
    $url = "https://github.com/$Repo/releases/download/v$FromVer/YPtun-$FromVer-$suffix-portable.exe"
    Write-Host "Downloading $url"
    Invoke-WebRequest $url -OutFile $OldPortable -UseBasicParsing
  }

  # Find the trailer. A signed file has a certificate table after it, so look in the tail rather than
  # at the very end: the LAST occurrence of the magic is the trailer.
  $magic = [Text.Encoding]::ASCII.GetBytes("YPTUNPKG")
  $fs = [IO.File]::OpenRead($OldPortable)
  try {
    $tailLen = [int][Math]::Min($fs.Length, 262144)
    $tail = New-Object byte[] $tailLen
    $fs.Seek($fs.Length - $tailLen, [IO.SeekOrigin]::Begin) | Out-Null
    $read = 0
    while ($read -lt $tailLen) {
      $n = $fs.Read($tail, $read, $tailLen - $read)
      if ($n -le 0) { throw "short read" }
      $read += $n
    }
    $at = -1
    for ($i = $tailLen - $magic.Length; $i -ge 8; $i--) {
      $hit = $true
      for ($j = 0; $j -lt $magic.Length; $j++) { if ($tail[$i + $j] -ne $magic[$j]) { $hit = $false; break } }
      if ($hit) { $at = $i; break }
    }
    if ($at -lt 0) { throw "$OldPortable carries no app image (no YPTUNPKG trailer)" }
    $size = [BitConverter]::ToUInt64($tail, $at - 8)
    $trailerStart = $fs.Length - $tailLen + $at - 8
    $zipStart = $trailerStart - [int64]$size
    if ($zipStart -lt 0) { throw "truncated app image in $OldPortable" }

    $zip = Join-Path $work "old-image.zip"
    $fs.Seek($zipStart, [IO.SeekOrigin]::Begin) | Out-Null
    $out = [IO.File]::Create($zip)
    try {
      $buf = New-Object byte[] 1048576
      $left = [int64]$size
      while ($left -gt 0) {
        $n = $fs.Read($buf, 0, [int][Math]::Min($buf.Length, $left))
        if ($n -le 0) { throw "short read" }
        $out.Write($buf, 0, $n)
        $left -= $n
      }
    } finally { $out.Close() }
  } finally { $fs.Close() }

  $oldImage = Join-Path $work "image"
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  [IO.Compression.ZipFile]::ExtractToDirectory($zip, $oldImage)
  if (-not (Test-Path (Join-Path $oldImage "app\YPtun.cfg"))) { throw "old image has no app\YPtun.cfg" }

  & (Join-Path $here "make-desktop-patch.ps1") `
      -OldImage $oldImage -NewImage $NewImage `
      -FromVer $FromVer -ToVer $ToVer -Target "windows-$Arch" -OutDir $OutDir
  if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "make-desktop-patch.ps1 failed with exit code $LASTEXITCODE" }
}
finally {
  Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}
