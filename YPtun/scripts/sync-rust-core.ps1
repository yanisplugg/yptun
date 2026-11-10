param(
    [Parameter(Mandatory = $false)]
    [string]$CoreDir
)

$ErrorActionPreference = "Stop"
$appRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$repoRoot = (Resolve-Path (Join-Path $appRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($CoreDir)) {
    $CoreDir = Join-Path $repoRoot "core"
}
$CoreDir = (Resolve-Path $CoreDir).Path
$output = Join-Path $CoreDir "build\android"

$binding = Join-Path $output "kotlin\org\olcbox\app\core\yptun_ffi.kt"
if (-not (Test-Path -LiteralPath $binding)) {
    throw "Rust Android bindings not found at $binding. Run core/scripts/build-android.sh first."
}

$bindingTarget = Join-Path $appRoot "sharedUI\src\androidMain\kotlin\org\olcbox\app\core\yptun_ffi.kt"
New-Item -ItemType Directory -Path (Split-Path -Parent $bindingTarget) -Force | Out-Null
Copy-Item -LiteralPath $binding -Destination $bindingTarget -Force
# UniFFI output can contain trailing spaces when ktlint is unavailable. Normalize only line endings/whitespace;
# this keeps generated commits reviewable and makes `git diff --check` useful.
$bindingLines = [IO.File]::ReadAllLines($bindingTarget) | ForEach-Object { $_.TrimEnd() }
$normalizedBinding = ($bindingLines -join "`n").TrimEnd() + "`n"
[IO.File]::WriteAllText(
    $bindingTarget,
    $normalizedBinding,
    [Text.UTF8Encoding]::new($false)
)

foreach ($abi in @("arm64-v8a", "armeabi-v7a", "x86_64")) {
    $library = Join-Path $output "jniLibs\$abi\libyptun_ffi.so"
    if (-not (Test-Path -LiteralPath $library)) {
        throw "Rust library missing for $abi at $library"
    }
    $target = Join-Path $appRoot "androidApp\src\main\jniLibs\$abi\libyptun_ffi.so"
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    Copy-Item -LiteralPath $library -Destination $target -Force
}

Write-Host "Synchronized yptun-core Kotlin bindings and Android libraries from $CoreDir"
