<#
.SYNOPSIS
  Generates a YPtun DESKTOP delta-update bundle between two builds of the same platform/arch.

.DESCRIPTION
  Run at release time, AFTER building the new desktop app image and BEFORE publishing the release.

  A desktop app image is ~160 MB, but only a handful of files change between releases: jpackage
  flattens every dependency into <install>/app/ as its own jar, our two jars (desktopApp-*.jar with
  the native cores, sharedUI-jvm-*.jar) are the ones that move, and YPtun.cfg changes with them
  because it names the classpath by exact filename. The launcher (YPtun.exe) changes on EVERY version
  as well - jpackage bakes the app version into its resources - so the bundle covers the whole image
  (paths relative to the image root: app/x.jar, YPtun.exe, runtime/...). A changed jar becomes a
  File-by-File patch, everything else that changed is carried whole, removed files are deleted.

  Output: YPtun-delta-<FromVer>-<ToVer>-<Target>-<base hash>.patch - a ZIP holding manifest.json plus
  one payload per operation (a gzip File-by-File v1 patch for a changed jar, the raw file otherwise). Every operation carries the SHA-256 of what it expects and what it produces, so the app
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

if (-not (Test-Path (Join-Path $OldImage "app"))) { throw "no app/ directory in $OldImage" }
if (-not (Test-Path (Join-Path $NewImage "app"))) { throw "no app/ directory in $NewImage" }

function Get-Sha([string]$path) { (Get-FileHash $path -Algorithm SHA256).Hash.ToLower() }

# A file's "stem" is its name with the jpackage content-hash suffix removed, which is what makes
# desktopApp-<oldhash>.jar and desktopApp-<newhash>.jar the same file across releases. A dependency
# VERSION change keeps its version in the stem, so it correctly reads as a delete plus an add.
function Get-Stem([string]$name) {
  if ($name -notlike "*.jar") { return $name }
  $base = [System.IO.Path]::GetFileNameWithoutExtension($name)
  return ($base -replace '-[0-9a-f]{20,}$', '')
}

# Every file of the image, keyed by its path relative to the image root with forward slashes - that is
# the form the bundle (and the applier on the user's machine) uses.
function Get-ImageFiles([string]$image) {
  # Get-Item, not Resolve-Path: it returns paths in the same form Get-ChildItem gives its children
  # (8.3 names like STANIS~1 expanded), so the prefix arithmetic below lines up.
  $imageFull = (Get-Item $image).FullName.TrimEnd('\')
  $result = @{}
  Get-ChildItem $imageFull -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($imageFull.Length).TrimStart('\').Replace('\', '/')
    $result[$rel] = $_
  }
  return $result
}
$oldFiles = Get-ImageFiles $OldImage
$newFiles = Get-ImageFiles $NewImage

# app/YPtun.cfg names every jar by exact filename and those names carry a content hash, so it is the
# cheap identity of a build. The bundle name carries the first 16 hex of its SHA-256; the app hashes
# its own YPtun.cfg and skips a bundle generated against a different image instead of downloading it
# to find out. Keep in sync with AppUpdateService.BASE_HASH_LENGTH / installedDesktopFingerprint().
$oldCfg = @($oldFiles.Keys | Where-Object { $_ -like "app/*.cfg" -and $_.IndexOf('/', 4) -lt 0 })
if ($oldCfg.Count -ne 1) { throw "expected exactly one app/*.cfg in $OldImage, found $($oldCfg.Count)" }
$baseSha = Get-Sha $oldFiles[$oldCfg[0]].FullName
$baseTag = $baseSha.Substring(0, 16)

# Old top-level app/ jars by stem, for pairing desktopApp-<oldhash>.jar with desktopApp-<newhash>.jar.
$oldJarByStem = @{}
foreach ($rel in $oldFiles.Keys) {
  if ($rel -like "app/*.jar" -and $rel.IndexOf('/', 4) -lt 0) {
    $oldJarByStem[(Get-Stem $oldFiles[$rel].Name)] = $rel
  }
}

$work = Join-Path $env:TEMP ("yptun-delta-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $work | Out-Null
$ops = @()
$index = 0
$patchedOld = @{}

foreach ($rel in ($newFiles.Keys | Sort-Object)) {
  $new = $newFiles[$rel]
  $newSha = Get-Sha $new.FullName
  if ($oldFiles.ContainsKey($rel) -and (Get-Sha $oldFiles[$rel].FullName) -eq $newSha) { continue }

  $matchRel = $null
  if ($rel -like "app/*.jar" -and $rel.IndexOf('/', 4) -lt 0) {
    $stem = Get-Stem $new.Name
    if ($oldJarByStem.ContainsKey($stem)) { $matchRel = $oldJarByStem[$stem] }
  }

  if ($null -ne $matchRel) {
    $match = $oldFiles[$matchRel]
    $payload = "p$index"; $index++
    $gz = Join-Path $work $payload
    & $java -Xmx2g -cp $out patchgen.PatchGen $match.FullName $new.FullName $gz | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "patch generation failed for $rel" }
    # Round-trip every patch before it can reach a user.
    $recon = Join-Path $work "recon.tmp"
    & $java -Xmx2g -cp $out patchgen.PatchApply $match.FullName $gz $recon | Out-Null
    if ((Get-Sha $recon) -ne $newSha) { throw "ROUND-TRIP MISMATCH for $rel - do NOT upload" }
    Remove-Item $recon -Force
    $patchedOld[$matchRel] = $true
    $ops += [ordered]@{
      op = "patch"; from = $matchRel; to = $rel
      fromSha = (Get-Sha $match.FullName); toSha = $newSha; payload = $payload
    }
    Write-Host ("  patch  {0} -> {1} ({2} KB)" -f $matchRel, $rel, [math]::Round((Get-Item $gz).Length / 1KB))
  } else {
    $payload = "p$index"; $index++
    Copy-Item $new.FullName (Join-Path $work $payload)
    $ops += [ordered]@{ op = "add"; to = $rel; toSha = $newSha; payload = $payload }
    Write-Host ("  add    {0} ({1} KB)" -f $rel, [math]::Round($new.Length / 1KB))
  }
}

foreach ($rel in ($oldFiles.Keys | Sort-Object)) {
  if ($newFiles.ContainsKey($rel) -or $patchedOld.ContainsKey($rel)) { continue }
  $ops += [ordered]@{ op = "delete"; from = $rel }
  Write-Host ("  delete {0}" -f $rel)
}

if ($ops.Count -eq 0) { throw "the two app images are identical - nothing to publish" }

$manifest = [ordered]@{ format = 3; from = $FromVer; to = $ToVer; target = $Target; ops = $ops }
$manifestPath = Join-Path $work "manifest.json"
# WriteAllText with a BOM-less encoder: PowerShell 5.1's -Encoding utf8 emits a BOM, which a strict
# JSON parser rejects.
[System.IO.File]::WriteAllText(
  $manifestPath, ($manifest | ConvertTo-Json -Depth 5), (New-Object System.Text.UTF8Encoding($false)))

New-Item -ItemType Directory -Force $OutDir | Out-Null
$bundle = Join-Path $OutDir ("YPtun-delta-{0}-{1}-{2}-{3}.patch" -f $FromVer, $ToVer, $Target, $baseTag)
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
Write-Host ("    base image YPtun.cfg sha256 $baseSha")
Write-Host ("    image $imageMb MB -> bundle $bundleMb MB  ({0} operation(s), every patch round-trip verified)" -f $ops.Count)
