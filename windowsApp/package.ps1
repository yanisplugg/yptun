[CmdletBinding()]
param(
    [ValidateSet("x64", "arm64")]
    [string]$Architecture = "x64",
    [string]$Version = "4.0.0",
    [string]$PublishedDirectory = "",
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($PublishedDirectory)) {
    $PublishedDirectory = Join-Path $PSScriptRoot "artifacts\$Architecture"
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $PSScriptRoot "artifacts\packages"
}
$PublishedDirectory = [IO.Path]::GetFullPath($PublishedDirectory)
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)

if (-not (Test-Path (Join-Path $PublishedDirectory "YPtun.exe"))) {
    throw "Published YPtun.exe not found in $PublishedDirectory. Run windowsApp/build.ps1 first."
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

$stalePackages = @(
    "YPtun-$Version-windows-$Architecture-portable.zip",
    "YPtun-$Version-windows-$Architecture-portable.exe",
    "YPtun-$Version-windows-$Architecture-installer.exe",
    "YPtun-$Version-$Architecture-portable.zip"
)
foreach ($stalePackage in $stalePackages) {
    $path = Join-Path $OutputDirectory $stalePackage
    if (Test-Path $path) { Remove-Item $path -Force }
}

Write-Host "Creating single-file portable launcher..."
$launcherDir = Join-Path (Split-Path -Parent $PSScriptRoot) "YPtun\desktopApp\packaging\windows\portable-launcher"
$go = (Get-Command go -ErrorAction SilentlyContinue).Source
if (-not $go) { throw "go.exe was not found; it is required for the single-file portable launcher" }
$launcherWork = Join-Path $OutputDirectory ".launcher-$Architecture"
if (Test-Path $launcherWork) { Remove-Item $launcherWork -Recurse -Force }
New-Item -ItemType Directory -Force -Path $launcherWork | Out-Null
try {
    $payload = Join-Path $launcherWork "app.zst"
    Push-Location $launcherDir
    try {
        & $go run ./cmd/pack $PublishedDirectory $payload
        if ($LASTEXITCODE -ne 0) { throw "portable payload pack failed" }
    }
    finally {
        Pop-Location
    }

    $buildId = (Get-FileHash $payload -Algorithm SHA256).Hash.Substring(0, 16).ToLowerInvariant()
    $stub = Join-Path $launcherWork "yptun-launcher.exe"
    $goArch = if ($Architecture -eq "arm64") { "arm64" } else { "amd64" }
    $oldGoOs = $env:GOOS
    $oldGoArch = $env:GOARCH
    $oldCgo = $env:CGO_ENABLED
    try {
        $env:GOOS = "windows"
        $env:GOARCH = $goArch
        $env:CGO_ENABLED = "0"
        Push-Location $launcherDir
        try {
            & $go build -trimpath -ldflags "-s -w -H=windowsgui -X main.version=$Version -X main.buildID=$buildId" -o $stub .
            if ($LASTEXITCODE -ne 0) { throw "portable launcher build failed" }
        }
        finally {
            Pop-Location
        }
    }
    finally {
        $env:GOOS = $oldGoOs
        $env:GOARCH = $oldGoArch
        $env:CGO_ENABLED = $oldCgo
    }

    $rcedit = Join-Path $PSScriptRoot ".native\downloads\rcedit.exe"
    if (-not (Test-Path $rcedit)) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $rcedit) | Out-Null
        Invoke-WebRequest "https://github.com/electron/rcedit/releases/download/v2.0.0/rcedit-x64.exe" -OutFile $rcedit
    }
    & $rcedit $stub `
        --set-icon (Join-Path $PSScriptRoot "src\YPtun.Windows\Assets\WindowsIcon.ico") `
        --set-version-string "ProductName" "YPtun" `
        --set-version-string "FileDescription" "YPtun portable" `
        --set-version-string "CompanyName" "YPtun" `
        --set-file-version $Version `
        --set-product-version $Version
    if ($LASTEXITCODE -ne 0) { throw "rcedit failed" }

    $portableExe = Join-Path $OutputDirectory "YPtun-$Version-$Architecture-portable.exe"
    if (Test-Path $portableExe) { Remove-Item $portableExe -Force }
    $output = [IO.File]::Create($portableExe)
    try {
        foreach ($part in @($stub, $payload)) {
            $input = [IO.File]::OpenRead($part)
            try { $input.CopyTo($output) } finally { $input.Dispose() }
        }
        $trailer = New-Object byte[] 16
        $payloadSize = [UInt64]((Get-Item $payload).Length)
        [Array]::Copy([BitConverter]::GetBytes($payloadSize), 0, $trailer, 0, 8)
        [Array]::Copy([Text.Encoding]::ASCII.GetBytes("YPTUNPKG"), 0, $trailer, 8, 8)
        $output.Write($trailer, 0, $trailer.Length)
    }
    finally {
        $output.Dispose()
    }
}
finally {
    if (Test-Path $launcherWork) { Remove-Item $launcherWork -Recurse -Force }
}

$isccCandidates = @(
    "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
    "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
)
$iscc = $isccCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($null -eq $iscc) {
    throw "Inno Setup 6 was not found. Install it, then run this script again."
}

Write-Host "Creating installer..."
& $iscc `
    "/DSourceDir=$PublishedDirectory" `
    "/DOutputDir=$OutputDirectory" `
    "/DAppVersion=$Version" `
    "/DArchitecture=$Architecture" `
    (Join-Path $PSScriptRoot "packaging\YPtun.iss")
if ($LASTEXITCODE -ne 0) { throw "Inno Setup failed" }

$hashFile = Join-Path $OutputDirectory "SHA256SUMS-windows-$Architecture.txt"
$installerExe = Join-Path $OutputDirectory "YPtun-$Version-$Architecture-installer.exe"
@($installerExe, $portableExe) |
    Get-Item |
    Sort-Object Name |
    ForEach-Object {
        $hash = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $($_.Name)"
    } | Set-Content $hashFile -Encoding ascii

Write-Host "Packages ready: $OutputDirectory"
