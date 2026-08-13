<#
.SYNOPSIS
  Generates a YPtun DESKTOP delta-update patch between two releases of the same platform/arch.

.DESCRIPTION
  Run at release time, AFTER building the new desktop app image and BEFORE publishing the release.
  A desktop delta patches ONE file - the application jar inside the installed app image
  (<install>/app/YPtun.jar, which carries our code and every native core). The bundled JRE and the
  launcher are identical between releases, so that jar is the whole update.

  The output is `YPtun-delta-<FromVer>-<ToVer>-<Target>.patch`, a self-describing container:

      YPTUNDLT1\n
      <sha256 of the old jar>\n
      <sha256 of the new jar>\n
      <gzip-compressed File-by-File v1 patch>

  The app (AppUpdateService.selectDeltaAsset -> JvmUpdateInstaller.install) picks it up by name,
  refuses it unless the installed jar is exactly the base it was built against, refuses the result
  unless it matches the new jar byte-for-byte, and falls back to the full installer either way.

  Target must match the tokens in the full asset's name, i.e. `windows-amd64`, `windows-arm64`,
  `linux-amd64`, `linux-arm64`.

  The jar of a build is at:
      desktopApp/build/compose/binaries/main-release/app/YPtun/app/YPtun.jar   (or main/ for a
      non-release createDistributable). Keep a copy of each shipped jar - the next release's patch
      is generated against it.

.EXAMPLE
  ./make-desktop-patch.ps1 -OldJar old\3.2.1\YPtun.jar -NewJar new\YPtun.jar `
                           -FromVer 3.2.1 -ToVer 3.2.2 -Target windows-amd64 -OutDir .\dist
#>
param(
  [Parameter(Mandatory=$true)][string]$OldJar,
  [Parameter(Mandatory=$true)][string]$NewJar,
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

New-Item -ItemType Directory -Force $OutDir | Out-Null
$rawGz = Join-Path $env:TEMP ("yptun-raw-{0}.gz" -f ([guid]::NewGuid().ToString('N')))
& $java -Xmx2g -cp $out patchgen.PatchGen $OldJar $NewJar $rawGz
if ($LASTEXITCODE -ne 0) { throw "patch generation failed" }

$fromHash = (Get-FileHash $OldJar -Algorithm SHA256).Hash.ToLower()
$toHash   = (Get-FileHash $NewJar -Algorithm SHA256).Hash.ToLower()

# Container = three LF-terminated ASCII header lines, then the raw gzip payload.
$patch = Join-Path $OutDir ("YPtun-delta-{0}-{1}-{2}.patch" -f $FromVer, $ToVer, $Target)
$header = "YPTUNDLT1`n$fromHash`n$toHash`n"
$stream = [System.IO.File]::Create($patch)
try {
  $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
  $stream.Write($headerBytes, 0, $headerBytes.Length)
  $payload = [System.IO.File]::OpenRead($rawGz)
  try { $payload.CopyTo($stream) } finally { $payload.Close() }
} finally { $stream.Close() }

# Self-check: reconstruct from OldJar and confirm it matches NewJar byte-for-byte.
$recon = Join-Path $env:TEMP ("yptun-recon-{0}.jar" -f ([guid]::NewGuid().ToString('N')))
& $java -Xmx2g -cp $out patchgen.PatchApply $OldJar $rawGz $recon | Out-Null
$reconHash = (Get-FileHash $recon -Algorithm SHA256).Hash.ToLower()
Remove-Item $recon, $rawGz -Force -ErrorAction SilentlyContinue
if ($reconHash -ne $toHash) { throw "ROUND-TRIP MISMATCH - patch is broken, do NOT upload" }

$fullMb  = [math]::Round((Get-Item $NewJar).Length / 1MB, 2)
$patchMb = [math]::Round((Get-Item $patch).Length / 1MB, 2)
Write-Host ""
Write-Host "OK  $patch"
Write-Host ("    jar $fullMb MB -> patch $patchMb MB  (round-trip verified)")
Write-Host ("    from $fromHash")
Write-Host ("    to   $toHash")
