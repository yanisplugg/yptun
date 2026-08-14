<#
.SYNOPSIS
  Generates a YPtun DESKTOP delta-update bundle between two builds of the same platform/arch.

.DESCRIPTION
  Run at release time, AFTER building the new desktop app image and BEFORE publishing the release.

  A desktop app image is ~160 MB, but only a handful of files change between releases: jpackage
  flattens every dependency into <install>/app/ as its own jar, our two jars (desktopApp-*.jar with
  the native cores, sharedUI-jvm-*.jar) are the ones that move, and YPtun.cfg changes with them
  because it names the classpath by exact filename. runtime/ (the bundled JRE) and the launcher do
  not change - if they DO, this script refuses to produce a bundle and the release simply ships the
  full installer.

  Output: YPtun-delta-<FromVer>-<ToVer>-<Target>.patch - a ZIP holding manifest.json plus one
  payload per operation (a gzip File-by-File v1 patch for a changed jar, the raw file for a new
  one). Every operation carries the SHA-256 of what it expects and what it produces, so the app
  refuses to apply it to anything but the exact build it was generated against, and refuses any
  rebuilt file that isn't byte-identical to the published one.

  Target must match the tokens in the full asset's name: windows-amd64, windows-arm64, linux-amd64,
  linux-arm64.

  OldImage/NewImage are the app-image ROOTS, i.e. the directory holding YPtun.exe, app/ and
  runtime/ (desktopApp/build/compose/binaries/main/app/YPtun). Keep a copy of each shipped image -
  the next release's bundle is generated against it.

.EXAMPLE
  ./make-desktop-patch.ps1 -OldImage C:\ship\3.2.1\YPtun -NewImage C:\ship\3.2.2\YPtun `
                           -FromVer 3.2.1 -ToVer 3.2.2 -Target windows-amd64 -OutDir .\dist
#>
param(
  [Parameter(Mandatory=$true)][string]$OldImage,
  [Parameter(Mandatory=$true)][string]$NewImage,
  [Parameter(Mandatory=$true)][string]$FromVer,
  [Parameter(Mandatory=$true)][string]$ToVer,
  [Parameter(Mandatory=$true)][string]$Target,
  [string]$OutDir = "."
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdk  = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "C:\Program Files\Android\Android Studio\jbr" }
$javac = Join-Path $jdk "bin\javac.exe"
$java  = Join-Path $jdk "bin\java.exe"
$out   = Join-Path $root "out"

# Compile the vendored generator/applier + tools once (skip if already built).
if (-not (Test-Path (Join-Path $out "patchgen\PatchGen.class"))) {
  New-Item -ItemType Directory -Force $out | Out-Null
  $srcs = Get-ChildItem (Join-Path $root "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
  & $javac -nowarn -d $out @srcs
  if ($LASTEXITCODE -ne 0) { throw "javac failed" }
}

$oldApp = Join-Path $OldImage "app"
$newApp = Join-Path $NewImage "app"
if (-not (Test-Path $oldApp)) { throw "no app/ directory in $OldImage" }
if (-not (Test-Path $newApp)) { throw "no app/ directory in $NewImage" }

function Get-Sha([string]$path) { (Get-FileHash $path -Algorithm SHA256).Hash.ToLower() }

# A file's "stem" is its name with the jpackage content-hash suffix removed, which is what makes
# desktopApp-<oldhash>.jar and desktopApp-<newhash>.jar the same file across releases. A dependency
# VERSION change keeps its version in the stem, so it correctly reads as a delete plus an add.
function Get-Stem([string]$name) {
  if ($name -notlike "*.jar") { return $name }
  $base = [System.IO.Path]::GetFileNameWithoutExtension($name)
  return ($base -replace '-[0-9a-f]{20,}$', '')
}

# Everything outside app/ must be identical - a JRE or launcher change needs the full installer.
function Get-OutsideApp([string]$image) {
  # Get-Item, not Resolve-Path: it returns paths in the same form Get-ChildItem gives its children
  # (8.3 names like STANIS~1 expanded), so the prefix arithmetic below lines up.
  $imageFull = (Get-Item $image).FullName.TrimEnd('\')
  $appFull = Join-Path $imageFull "app"
  $result = @{}
  Get-ChildItem $imageFull -Recurse -File | ForEach-Object {
    if (-not $_.FullName.StartsWith($appFull, [StringComparison]::OrdinalIgnoreCase)) {
      $result[$_.FullName.Substring($imageFull.Length).TrimStart('\')] = $_.FullName
    }
  }
  return $result
}
$oldOutside = Get-OutsideApp $OldImage
$newOutside = Get-OutsideApp $NewImage
foreach ($rel in $newOutside.Keys) {
  if (-not $oldOutside.ContainsKey($rel)) { throw "$rel is new outside app/ - ship the full installer" }
  if ((Get-Sha $oldOutside[$rel]) -ne (Get-Sha $newOutside[$rel])) {
    throw "$rel differs (runtime or launcher changed) - ship the full installer"
  }
}
foreach ($rel in $oldOutside.Keys) {
  if (-not $newOutside.ContainsKey($rel)) { throw "$rel was removed outside app/ - ship the full installer" }
}

# Only the top level of app/ is diffed (that is where jpackage puts every jar and YPtun.cfg).
# jpackage also creates an empty app/resources; refuse rather than silently skip if it ever fills up.
foreach ($dir in @($oldApp, $newApp)) {
  $nested = Get-ChildItem $dir -Recurse -File | Where-Object { $_.DirectoryName -ne (Get-Item $dir).FullName }
  if ($nested) { throw "app/ has files in subdirectories ($($nested[0].FullName)) - ship the full installer" }
}

$oldFiles = @{}
Get-ChildItem $oldApp -File | ForEach-Object { $oldFiles[$_.Name] = $_ }
$newFiles = @{}
Get-ChildItem $newApp -File | ForEach-Object { $newFiles[$_.Name] = $_ }

$work = Join-Path $env:TEMP ("yptun-delta-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $work | Out-Null
$ops = @()
$index = 0

foreach ($name in $newFiles.Keys) {
  $new = $newFiles[$name]
  $newSha = Get-Sha $new.FullName
  if ($oldFiles.ContainsKey($name) -and (Get-Sha $oldFiles[$name].FullName) -eq $newSha) { continue }

  $stem = Get-Stem $name
  $match = $null
  foreach ($oldName in $oldFiles.Keys) {
    if ((Get-Stem $oldName) -eq $stem) { $match = $oldFiles[$oldName]; break }
  }

  if ($null -ne $match -and $name -like "*.jar") {
    $payload = "p$index"; $index++
    $gz = Join-Path $work $payload
    & $java -Xmx2g -cp $out patchgen.PatchGen $match.FullName $new.FullName $gz | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "patch generation failed for $name" }
    # Round-trip every patch before it can reach a user.
    $recon = Join-Path $work "recon.tmp"
    & $java -Xmx2g -cp $out patchgen.PatchApply $match.FullName $gz $recon | Out-Null
    if ((Get-Sha $recon) -ne $newSha) { throw "ROUND-TRIP MISMATCH for $name - do NOT upload" }
    Remove-Item $recon -Force
    $ops += [ordered]@{
      op = "patch"; from = $match.Name; to = $name
      fromSha = (Get-Sha $match.FullName); toSha = $newSha; payload = $payload
    }
    Write-Host ("  patch  {0} -> {1} ({2} KB)" -f $match.Name, $name, [math]::Round((Get-Item $gz).Length / 1KB))
  } else {
    $payload = "p$index"; $index++
    Copy-Item $new.FullName (Join-Path $work $payload)
    $ops += [ordered]@{ op = "add"; to = $name; toSha = $newSha; payload = $payload }
    Write-Host ("  add    {0} ({1} KB)" -f $name, [math]::Round($new.Length / 1KB))
  }
}

foreach ($name in $oldFiles.Keys) {
  if ($newFiles.ContainsKey($name)) { continue }
  $stem = Get-Stem $name
  $replaced = $false
  foreach ($op in $ops) { if ($op.op -eq "patch" -and $op.from -eq $name) { $replaced = $true; break } }
  if ($replaced) { continue }
  $ops += [ordered]@{ op = "delete"; from = $name }
  Write-Host ("  delete {0}" -f $name)
}

if ($ops.Count -eq 0) { throw "the two app images are identical - nothing to publish" }

$manifest = [ordered]@{ format = 2; from = $FromVer; to = $ToVer; target = $Target; ops = $ops }
$manifestPath = Join-Path $work "manifest.json"
# WriteAllText with a BOM-less encoder: PowerShell 5.1's -Encoding utf8 emits a BOM, which a strict
# JSON parser rejects.
[System.IO.File]::WriteAllText(
  $manifestPath, ($manifest | ConvertTo-Json -Depth 5), (New-Object System.Text.UTF8Encoding($false)))

New-Item -ItemType Directory -Force $OutDir | Out-Null
$bundle = Join-Path $OutDir ("YPtun-delta-{0}-{1}-{2}.patch" -f $FromVer, $ToVer, $Target)
if (Test-Path $bundle) { Remove-Item $bundle -Force }
# ZipFile, not Compress-Archive: the latter refuses any destination that isn't named *.zip.
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory(
  $work, $bundle, [System.IO.Compression.CompressionLevel]::Optimal, $false)

$imageMb  = [math]::Round((Get-ChildItem $NewImage -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB, 1)
$bundleMb = [math]::Round((Get-Item $bundle).Length / 1MB, 2)
Remove-Item $work -Recurse -Force
Write-Host ""
Write-Host "OK  $bundle"
Write-Host ("    app image $imageMb MB -> bundle $bundleMb MB  ({0} operation(s), every patch round-trip verified)" -f $ops.Count)
