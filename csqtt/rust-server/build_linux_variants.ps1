param(
    [Parameter(Mandatory = $false)][string]$BuildScript,
    [Parameter(Mandatory = $false)][string]$ServerDir,
    [Parameter(Mandatory = $false)][string]$ProjectRoot,
    [Parameter(Mandatory = $false)][string]$AssetsDir,
    [Parameter(Mandatory = $false)][string]$CargoFeatures = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

if (-not $ServerDir) {
    if ($BuildScript) {
        $ServerDir = (Get-Item -LiteralPath $BuildScript).DirectoryName
    } else {
        $ServerDir = $PSScriptRoot
    }
}
$ServerDir = (Resolve-Path -LiteralPath $ServerDir).Path

if (-not $ProjectRoot) {
    $ProjectRoot = (Resolve-Path -LiteralPath (Join-Path $ServerDir "..")).Path
} else {
    $ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
}

if (-not $AssetsDir) {
    $AssetsDir = Join-Path $ProjectRoot "app\src\main\assets"
}
New-Item -ItemType Directory -Force -Path $AssetsDir | Out-Null

$shimDir = Join-Path ([System.IO.Path]::GetTempPath()) "csqtt-py3-shim"
New-Item -ItemType Directory -Force -Path $shimDir | Out-Null
Set-Content -Path (Join-Path $shimDir "python3.cmd") -Value "@exit /b 1"
$env:PATH = "$shimDir;$env:PATH"

$zig = Get-Command zig.exe -ErrorAction SilentlyContinue
if (-not $zig -and $env:LOCALAPPDATA) {
    $wingetZig = Get-ChildItem -Path "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Filter "zig.exe" -Recurse -File -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($wingetZig) {
        $env:PATH = "$($wingetZig.DirectoryName);$env:PATH"
    }
}

$cargo = (Get-Command cargo.exe -ErrorAction Stop).Source
$logsDir = Join-Path $ProjectRoot "build\csqtt-uring-linux-logs"
New-Item -ItemType Directory -Force -Path $logsDir | Out-Null

$variants = @(
    @{ Name = 'amd64'; Target = 'x86_64-unknown-linux-musl'; Asset = 'csqtt-linux-amd64' },
    @{ Name = 'arm64'; Target = 'aarch64-unknown-linux-musl'; Asset = 'csqtt-linux-arm64' },
    @{ Name = 'armv7'; Target = 'armv7-unknown-linux-musleabihf'; Asset = 'csqtt-linux-armv7' }
)

Write-Host "========================================================================" -ForegroundColor Cyan
Write-Host "  Building 3 Linux Server Variants in Parallel (Single Window)" -ForegroundColor Cyan
Write-Host "  Targets: amd64 (x86_64), arm64 (aarch64), armv7 (armv7eabihf)" -ForegroundColor Cyan
Write-Host "========================================================================" -ForegroundColor Cyan

$tasks = @()

foreach ($variant in $variants) {
    $targetDir = Join-Path $ProjectRoot "build\csqtt-uring-linux-$($variant.Asset)"
    New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
    $logFile = Join-Path $logsDir "$($variant.Name).log"
    if (Test-Path -LiteralPath $logFile) {
        Remove-Item -LiteralPath $logFile -Force -ErrorAction SilentlyContinue
    }

    $extraArgs = if ($CargoFeatures) { " $CargoFeatures" } else { "" }
    $cmdArgs = "/d /c (`"$cargo`" zigbuild --release --target $($variant.Target)$extraArgs) > `"$logFile`" 2>&1"

    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = "cmd.exe"
    $info.Arguments = $cmdArgs
    $info.WorkingDirectory = $ServerDir
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.EnvironmentVariables["CARGO_TARGET_DIR"] = $targetDir
    $info.EnvironmentVariables["PATH"] = $env:PATH

    $proc = [System.Diagnostics.Process]::Start($info)

    $tasks += [pscustomobject]@{
        Name = $variant.Name
        Target = $variant.Target
        Asset = $variant.Asset
        TargetDir = $targetDir
        LogFile = $logFile
        Process = $proc
        Stream = $null
        Reader = $null
        CurrentCrate = "initializing"
        ExitCode = $null
        Duration = $null
        WarningCount = 0
        ErrorCount = 0
    }
}

$isInteractive = $false
try {
    if ([Environment]::UserInteractive -and -not [Console]::IsOutputRedirected) {
        $null = [Console]::WindowWidth
        $isInteractive = $true
    }
} catch {
    $isInteractive = $false
}

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$lastDisplayUpdate = [DateTime]::MinValue
$lastNonInteractivePrint = [DateTime]::MinValue
$lastPrintedStatus = ""
$maxStatusLen = 0

try {
    while ($true) {
        $anyRunning = $false

        foreach ($item in $tasks) {
            if (-not $item.Process.HasExited) {
                $anyRunning = $true
            } elseif ($null -eq $item.ExitCode) {
                $item.Process.Refresh()
                $item.ExitCode = $item.Process.ExitCode
                $item.Duration = $sw.Elapsed
            }

            if ($null -eq $item.Reader -and (Test-Path -LiteralPath $item.LogFile)) {
                try {
                    $stream = [System.IO.File]::Open($item.LogFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
                    $item.Stream = $stream
                    $item.Reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
                } catch {
                }
            }

            if ($null -ne $item.Reader) {
                while ($true) {
                    $line = $item.Reader.ReadLine()
                    if ($null -eq $line) { break }
                    $trimmed = $line.Trim()
                    if ([string]::IsNullOrWhiteSpace($trimmed)) { continue }

                    if ($trimmed -match '^(?:Compiling|Checking|Building)\s+([^\s]+)') {
                        $item.CurrentCrate = $Matches[1]
                    } elseif ($trimmed -match '^Finished\s+') {
                        $item.CurrentCrate = "Finishing"
                    }

                    if ($trimmed -match '^(?:warning:\s*|CMake Warning|warning\[\w+\]:\s*|[a-zA-Z0-9_-]+@[\d\.]+: warning:)') {
                        $item.WarningCount++
                        if ($isInteractive) {
                            $pad = " " * [Math]::Min(120, [Math]::Max(80, $maxStatusLen))
                            Write-Host ("`r$pad`r") -NoNewline
                        }
                        Write-Host "  [!][$($item.Name)] $trimmed" -ForegroundColor Yellow
                    }
                    elseif ($trimmed -match '^(?:error:\s*|CMake Error|error\[\w+\]:\s*|error occurred in\s*)') {
                        $item.ErrorCount++
                        if ($isInteractive) {
                            $pad = " " * [Math]::Min(120, [Math]::Max(80, $maxStatusLen))
                            Write-Host ("`r$pad`r") -NoNewline
                        }
                        Write-Host "  [x][$($item.Name)] $trimmed" -ForegroundColor Red
                    }
                }
            }

            if ($item.Process.HasExited -and $null -ne $item.Stream) {
                $item.Reader.Dispose()
                $item.Stream.Dispose()
                $item.Reader = $null
                $item.Stream = $null
                $item.CurrentCrate = if ($item.ExitCode -eq 0) { "Done" } else { "Failed" }
            }
        }

        $now = [DateTime]::UtcNow
        if (($now - $lastDisplayUpdate).TotalMilliseconds -ge 300 -or -not $anyRunning) {
            $lastDisplayUpdate = $now
            $elapsed = $sw.Elapsed.ToString("mm\:ss")

            $parts = foreach ($item in $tasks) {
                if ($null -ne $item.ExitCode) {
                    if ($item.ExitCode -eq 0) {
                        "$($item.Name): [OK]"
                    } else {
                        "$($item.Name): [FAILED]"
                    }
                } else {
                    "$($item.Name): $($item.CurrentCrate)"
                }
            }
            $statusText = "[$elapsed] " + ($parts -join " | ")

            if ($isInteractive) {
                $maxLen = 80
                try { $maxLen = [Math]::Max(40, [Console]::WindowWidth - 2) } catch {}
                if ($statusText.Length -gt $maxLen) {
                    $statusText = $statusText.Substring(0, $maxLen - 3) + "..."
                }
                if ($statusText.Length -lt $maxStatusLen) {
                    $statusText = $statusText.PadRight($maxStatusLen)
                } else {
                    $maxStatusLen = $statusText.Length
                }
                Write-Host -NoNewline ("`r" + $statusText)
            } else {
                if ($statusText -ne $lastPrintedStatus -and (($now - $lastNonInteractivePrint).TotalSeconds -ge 4 -or -not $anyRunning)) {
                    $lastNonInteractivePrint = $now
                    $lastPrintedStatus = $statusText
                    Write-Host $statusText
                }
            }
        }

        if (-not $anyRunning) {
            break
        }
        Start-Sleep -Milliseconds 100
    }
} finally {
    foreach ($item in $tasks) {
        if ($null -ne $item.Stream) {
            try { $item.Reader.Dispose() } catch {}
            try { $item.Stream.Dispose() } catch {}
        }
    }
}

if ($isInteractive) {
    $pad = " " * [Math]::Min(120, [Math]::Max(80, $maxStatusLen))
    Write-Host ("`r$pad`r") -NoNewline
}

Write-Host "`n========================================================================" -ForegroundColor Cyan
Write-Host "  Linux Server Build Summary ($($sw.Elapsed.ToString('mm\:ss')) total):" -ForegroundColor Cyan
Write-Host "========================================================================" -ForegroundColor Cyan

$hasFailure = $false

foreach ($item in $tasks) {
    $dur = if ($null -ne $item.Duration) { $item.Duration.ToString("mm\:ss") } else { "N/A" }
    $warns = if ($item.WarningCount -gt 0) { " ($($item.WarningCount) warnings)" } else { "" }

    if ($item.ExitCode -eq 0) {
        $srcBinary = Join-Path $item.TargetDir "$($item.Target)\release\csqtt"
        if (-not (Test-Path -LiteralPath $srcBinary)) {
            $srcBinary = Join-Path $item.TargetDir "release\csqtt"
        }
        $destBinary = Join-Path $AssetsDir $item.Asset

        if (Test-Path -LiteralPath $srcBinary) {
            Copy-Item -LiteralPath $srcBinary -Destination $destBinary -Force
            $size = (Get-Item -LiteralPath $destBinary).Length
            Write-Host "  [OK]   $($item.Name) ($dur)$warns -> $($item.Asset) ($size bytes)" -ForegroundColor Green
        } else {
            Write-Host "  [FAIL] $($item.Name) binary not found at $srcBinary" -ForegroundColor Red
            $hasFailure = $true
        }
    } else {
        Write-Host "  [FAIL] $($item.Name) (ExitCode $($item.ExitCode), $dur)" -ForegroundColor Red
        $hasFailure = $true
    }
}

if ($hasFailure) {
    Write-Host "`n========================================================================" -ForegroundColor Red
    Write-Host "  Build Failures - Log Tail (last 40 lines):" -ForegroundColor Red
    Write-Host "========================================================================" -ForegroundColor Red
    foreach ($item in $tasks) {
        if ($item.ExitCode -ne 0 -or -not (Test-Path -LiteralPath (Join-Path $AssetsDir $item.Asset))) {
            Write-Host "--- Log for $($item.Name) ($($item.LogFile)) ---" -ForegroundColor Red
            if (Test-Path -LiteralPath $item.LogFile) {
                Get-Content -LiteralPath $item.LogFile -Tail 40 | ForEach-Object {
                    Write-Host "  $_" -ForegroundColor DarkRed
                }
            }
        }
    }
    exit 1
}

Write-Host "All Linux server variants compiled and copied to $AssetsDir successfully.`n" -ForegroundColor Green
exit 0
