[CmdletBinding()]
param(
    [ValidateSet("x64", "arm64")]
    [string]$Architecture = "x64",
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Release",
    [switch]$SkipGoCore,
    [switch]$NoPublish
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$coreRoot = Join-Path $root "core"
$project = Join-Path $PSScriptRoot "src\YPtun.Windows\YPtun.Windows.csproj"
$staging = Join-Path $PSScriptRoot ".native\$Architecture"
$artifacts = Join-Path $PSScriptRoot "artifacts\$Architecture"
$downloadDir = if ($env:YPTUN_BUILD_CACHE_DIR) {
    [IO.Path]::GetFullPath($env:YPTUN_BUILD_CACHE_DIR)
} else {
    Join-Path $PSScriptRoot ".native\downloads"
}
$cargoTargetRoot = if ($env:CARGO_TARGET_DIR) {
    [IO.Path]::GetFullPath($env:CARGO_TARGET_DIR)
} else {
    Join-Path $coreRoot "target"
}

$target = if ($Architecture -eq "arm64") { "aarch64-pc-windows-gnullvm" } else { "x86_64-pc-windows-msvc" }
$rid = if ($Architecture -eq "arm64") { "win-arm64" } else { "win-x64" }
$goArch = if ($Architecture -eq "arm64") { "arm64" } else { "amd64" }

New-Item -ItemType Directory -Force -Path $staging | Out-Null
New-Item -ItemType Directory -Force -Path $downloadDir | Out-Null

function Resolve-Arm64CrossCompiler {
    $version = "20260616"
    $hostPackageArch = if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64") { "aarch64" } else { "x86_64" }
    $package = "llvm-mingw-$version-ucrt-$hostPackageArch"
    $archive = Join-Path $downloadDir "$package.zip"
    $extract = Join-Path $downloadDir $package
    if (-not (Test-Path $archive)) {
        Write-Host "Downloading LLVM-MinGW ARM64 cross toolchain ($hostPackageArch host)..."
        Invoke-WebRequest "https://github.com/mstorsjo/llvm-mingw/releases/download/$version/$package.zip" -OutFile $archive
    }
    $compiler = Get-ChildItem $extract -Recurse -Filter "aarch64-w64-mingw32-clang.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $compiler) {
        if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
        Expand-Archive $archive -DestinationPath $extract -Force
        $compiler = Get-ChildItem $extract -Recurse -Filter "aarch64-w64-mingw32-clang.exe" | Select-Object -First 1
    }
    if ($null -eq $compiler) { throw "LLVM-MinGW ARM64 compiler was not found in $archive" }
    return $compiler.FullName
}

if ($Architecture -eq "arm64") {
    $crossCc = Resolve-Arm64CrossCompiler
    $crossBin = Split-Path -Parent $crossCc
    $env:PATH = "$crossBin;$env:PATH"
    $env:CC = $crossCc
    $env:CXX = Join-Path $crossBin "aarch64-w64-mingw32-clang++.exe"
    $env:CARGO_TARGET_AARCH64_PC_WINDOWS_GNULLVM_LINKER = $crossCc
    rustup target add $target | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Rust target $target could not be installed" }
}

Write-Host "Building Rust WinUI bridge ($target)..."
cargo build --manifest-path (Join-Path $coreRoot "Cargo.toml") -p yptun-windows --release --target $target
if ($LASTEXITCODE -ne 0) { throw "Rust bridge build failed" }
Copy-Item (Join-Path $cargoTargetRoot "$target\release\yptun_windows.dll") (Join-Path $staging "yptun_windows.dll") -Force

if (-not $SkipGoCore) {
    Write-Host "Building shared YPtun engine core ($goArch)..."
    $cores = Join-Path $root "cores"
    $singBoxChangelog = Join-Path $root "sing-box\docs\changelog.md"
    $versionLine = Get-Content $singBoxChangelog | Where-Object { $_ -match '^####\s+(\d+\.\d+\.\d+\S*)\s*$' } | Select-Object -First 1
    $singBoxVersion = if ($versionLine -match '^####\s+(\d+\.\d+\.\d+\S*)\s*$') { $Matches[1] } else { "unknown" }
    $tags = "with_gvisor,with_dhcp,with_wireguard,with_utls,with_clash_api,with_quic,with_naive_outbound,with_openvpn,with_purego"
    $engine = Join-Path $staging "yptuncore-windows-$goArch.dll"

    $oldGoOs = $env:GOOS
    $oldGoArch = $env:GOARCH
    $oldCgo = $env:CGO_ENABLED
    try {
        $env:GOOS = "windows"
        $env:GOARCH = $goArch
        $env:CGO_ENABLED = "1"
        & go -C $cores build -buildmode=c-shared -trimpath -tags $tags `
            -ldflags "-X github.com/sagernet/sing-box/constant.Version=$singBoxVersion -s -w" `
            -o $engine ./cmd/yptuncore
        if ($LASTEXITCODE -ne 0) { throw "Go yptuncore build failed" }
    }
    finally {
        $env:GOOS = $oldGoOs
        $env:GOARCH = $oldGoArch
        $env:CGO_ENABLED = $oldCgo
    }

    $generatedHeader = [IO.Path]::ChangeExtension($engine, ".h")
    if (Test-Path $generatedHeader) { Remove-Item $generatedHeader }

    Write-Host "Staging Wintun runtime..."
    $wintunVersion = "0.14.1"
    $wintunZip = Join-Path $downloadDir "wintun-$wintunVersion.zip"
    $wintunExtract = Join-Path $downloadDir "wintun-$wintunVersion"
    if (-not (Test-Path $wintunZip)) {
        Invoke-WebRequest "https://www.wintun.net/builds/wintun-$wintunVersion.zip" -OutFile $wintunZip
    }
    if (-not (Test-Path $wintunExtract)) {
        Expand-Archive $wintunZip -DestinationPath $wintunExtract -Force
    }
    Copy-Item (Join-Path $wintunExtract "wintun\bin\$goArch\wintun.dll") (Join-Path $staging "wintun.dll") -Force

    Write-Host "Staging tun2socks runtime..."
    $tun2socksVersion = "2.6.0"
    $tun2socksZip = Join-Path $downloadDir "tun2socks-windows-$goArch-$tun2socksVersion.zip"
    $tun2socksExtract = Join-Path $downloadDir "tun2socks-windows-$goArch-$tun2socksVersion"
    if (-not (Test-Path $tun2socksZip)) {
        Invoke-WebRequest "https://github.com/xjasonlyu/tun2socks/releases/download/v$tun2socksVersion/tun2socks-windows-$goArch.zip" -OutFile $tun2socksZip
    }
    if (-not (Test-Path $tun2socksExtract)) {
        Expand-Archive $tun2socksZip -DestinationPath $tun2socksExtract -Force
    }
    $tun2socksBinary = Get-ChildItem $tun2socksExtract -Recurse -Filter "tun2socks-windows-$goArch.exe" | Select-Object -First 1
    if ($null -eq $tun2socksBinary) { throw "tun2socks binary not found in $tun2socksZip" }
    Copy-Item $tun2socksBinary.FullName (Join-Path $staging "tun2socks-windows-$goArch.exe") -Force

    Write-Host "Staging optional Cronet runtime..."
    Push-Location $cores
    try {
        $cronetModule = "github.com/sagernet/cronet-go/lib/windows_$goArch"
        $cronetDir = (& go list -m -f '{{.Dir}}' $cronetModule 2>$null | Select-Object -Last 1).Trim()
        if ($cronetDir -and (Test-Path (Join-Path $cronetDir "libcronet.dll"))) {
            Copy-Item (Join-Path $cronetDir "libcronet.dll") (Join-Path $staging "libcronet.dll") -Force
        }
    }
    finally {
        Pop-Location
    }
}

Write-Host "Building WinUI 3 application..."
$msbuildProperties = @(
    "-p:Platform=$Architecture",
    "-p:NativeAssetsDir=$staging"
)

if ($NoPublish) {
    dotnet build $project -c $Configuration @msbuildProperties
} else {
    if (Test-Path $artifacts) { Remove-Item $artifacts -Recurse -Force }
    dotnet publish $project -c $Configuration -r $rid --self-contained true @msbuildProperties -o $artifacts
}
if ($LASTEXITCODE -ne 0) { throw "WinUI build failed" }

$resultPath = if ($NoPublish) {
    Join-Path $PSScriptRoot "src\YPtun.Windows\bin\$Architecture\$Configuration"
} else {
    $artifacts
}
Write-Host "Windows build ready: $resultPath"
