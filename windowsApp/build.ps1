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

$target = if ($Architecture -eq "arm64") { "aarch64-pc-windows-msvc" } else { "x86_64-pc-windows-msvc" }
$rid = if ($Architecture -eq "arm64") { "win-arm64" } else { "win-x64" }
$goArch = if ($Architecture -eq "arm64") { "arm64" } else { "amd64" }

New-Item -ItemType Directory -Force -Path $staging | Out-Null

function Import-VsDevEnvironment([string]$TargetArchitecture) {
    $vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
    if (-not (Test-Path $vswhere)) { return }
    $installation = (& $vswhere -latest -products * -property installationPath).Trim()
    if ([string]::IsNullOrWhiteSpace($installation)) { return }
    $devCmd = Join-Path $installation "Common7\Tools\VsDevCmd.bat"
    if (-not (Test-Path $devCmd)) { return }
    $hostArchitecture = if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64") { "arm64" } else { "amd64" }
    $target = if ($TargetArchitecture -eq "arm64") { "arm64" } else { "amd64" }
    $environment = & $env:ComSpec /d /s /c "`"$devCmd`" -no_logo -arch=$target -host_arch=$hostArchitecture && set"
    foreach ($line in $environment) {
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { continue }
        $name = $line.Substring(0, $separator)
        $value = $line.Substring($separator + 1)
        [Environment]::SetEnvironmentVariable($name, $value, "Process")
    }
}

if ($Architecture -eq "arm64") {
    Import-VsDevEnvironment $Architecture
    $arm64Crt = if ($env:VCToolsInstallDir) { Join-Path $env:VCToolsInstallDir "lib\arm64\msvcrt.lib" } else { "" }
    if ([string]::IsNullOrWhiteSpace($arm64Crt) -or -not (Test-Path $arm64Crt)) {
        throw "Visual Studio ARM64 C++ build tools are required (msvcrt.lib for ARM64 was not found)."
    }
}

Write-Host "Building Rust WinUI bridge ($target)..."
cargo build --manifest-path (Join-Path $coreRoot "Cargo.toml") -p yptun-windows --release --target $target
if ($LASTEXITCODE -ne 0) { throw "Rust bridge build failed" }
Copy-Item (Join-Path $coreRoot "target\$target\release\yptun_windows.dll") (Join-Path $staging "yptun_windows.dll") -Force

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
        if ($Architecture -eq "arm64" -and [string]::IsNullOrWhiteSpace($env:CC)) {
            throw "ARM64 yptuncore needs a native/cross MinGW CC. Set CC and CXX before running this script."
        }
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
    $downloadDir = Join-Path $PSScriptRoot ".native\downloads"
    $wintunZip = Join-Path $downloadDir "wintun-$wintunVersion.zip"
    $wintunExtract = Join-Path $downloadDir "wintun-$wintunVersion"
    New-Item -ItemType Directory -Force -Path $downloadDir | Out-Null
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
