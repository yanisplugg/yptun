<#
.SYNOPSIS
  Generates a YPtun delta-update patch (gzip File-by-File v1) between two release APKs of the SAME ABI.

.DESCRIPTION
  Run at release time, AFTER building the new release APKs and BEFORE `gh release create`. Produces
  `YPtun-delta-<FromVer>-<ToVer>-<Abi>.patch.gz` to upload alongside the full APKs. The app discovers
  it by name (AppUpdateService.selectDeltaAsset), downloads it instead of the full APK, reconstructs
  the new APK locally from the user's installed one, signature-verifies it, then installs.

  Generate one patch per ABI, FROM the previous published release TO the new one. If a user is more
  than one version behind (no matching patch), the app falls back to the full APK automatically.

.EXAMPLE
  ./make-patch.ps1 -OldApk old\YPtun-v2.6.1-arm64-v8a.apk -NewApk YPtun-v2.6.2-arm64-v8a.apk `
                   -FromVer 2.6.1 -ToVer 2.6.2 -Abi arm64-v8a -OutDir .\dist
#>
param(
  [Parameter(Mandatory=$true)][string]$OldApk,
  [Parameter(Mandatory=$true)][string]$NewApk,
  [Parameter(Mandatory=$true)][string]$FromVer,
  [Parameter(Mandatory=$true)][string]$ToVer,
  [Parameter(Mandatory=$true)][string]$Abi,
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

New-Item -ItemType Directory -Force $OutDir | Out-Null
$patch = Join-Path $OutDir ("YPtun-delta-{0}-{1}-{2}.patch.gz" -f $FromVer, $ToVer, $Abi)
& $java -Xmx2g -cp $out patchgen.PatchGen $OldApk $NewApk $patch
if ($LASTEXITCODE -ne 0) { throw "patch generation failed" }

# Self-check: reconstruct from OldApk and confirm it matches NewApk byte-for-byte.
$recon = Join-Path $env:TEMP ("yptun-recon-{0}.apk" -f ([guid]::NewGuid().ToString('N')))
& $java -Xmx2g -cp $out patchgen.PatchApply $OldApk $patch $recon | Out-Null
$h1 = (Get-FileHash $NewApk -Algorithm SHA256).Hash.ToLower()
$h2 = (Get-FileHash $recon  -Algorithm SHA256).Hash.ToLower()
Remove-Item $recon -Force -ErrorAction SilentlyContinue
if ($h1 -ne $h2) { throw "ROUND-TRIP MISMATCH — patch is broken, do NOT upload" }

$fullMb  = [math]::Round((Get-Item $NewApk).Length / 1MB, 2)
$patchMb = [math]::Round((Get-Item $patch).Length / 1MB, 2)
Write-Host ""
Write-Host "OK  $patch"
Write-Host ("    full $fullMb MB -> patch $patchMb MB  (round-trip verified)")
