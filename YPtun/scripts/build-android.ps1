<#
.SYNOPSIS
  Builds the olcbox Android debug APK (triggers the gomobile builds of olcrtc.aar and libbox.aar).

  Run after scripts\setup-build.ps1:
     powershell -ExecutionPolicy Bypass -File scripts\build-android.ps1

  Sets the environment gomobile needs, then invokes Gradle. Adjust NDK version if you
  installed a different one (must match androidApp ndkVersion = 28.2.13676358).
#>

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

# --- environment for the gomobile bind tasks ---
$goBin = Join-Path (go env GOPATH) 'bin'
if ($env:Path -notlike "*$goBin*") { $env:Path = "$goBin;$env:Path" }

if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $env:ANDROID_NDK_HOME) { $env:ANDROID_NDK_HOME = Join-Path $env:ANDROID_HOME 'ndk\28.2.13676358' }

Write-Host "ANDROID_HOME     = $env:ANDROID_HOME"
Write-Host "ANDROID_NDK_HOME = $env:ANDROID_NDK_HOME"
Write-Host "gomobile         = $((Get-Command gomobile -ErrorAction SilentlyContinue).Source)"

if (-not (Test-Path $env:ANDROID_NDK_HOME)) {
    Write-Warning "NDK not found at $env:ANDROID_NDK_HOME — install it via Android Studio SDK Manager (NDK 28.2.13676358)."
}

# Optional: build only the arm64 ABI for a faster first build (most phones).
# Uncomment to limit (also speeds up the gomobile bind step if you trim its targets in build.gradle.kts):
# $extra = '-Polcbox.android.abiFilters=arm64-v8a'

Push-Location $repoRoot
try {
    & .\gradlew.bat :androidApp:assembleDebug --stacktrace $extra
    $apk = Join-Path $repoRoot 'androidApp\build\outputs\apk\debug'
    Write-Host ""
    Write-Host "Done. APK(s) in: $apk" -ForegroundColor Green
    Get-ChildItem $apk -Filter *.apk -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $($_.Name)" }
}
finally {
    Pop-Location
}
