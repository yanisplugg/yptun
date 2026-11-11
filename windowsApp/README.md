# YPtun for Windows

Native Windows client built with C#, WinUI 3 and the Windows App SDK. Shared VPN models,
configuration generation and engine orchestration stay in `core/yptun-core`; the WinUI process
talks to that Rust core through the small C ABI in `core/yptun-windows`.

The existing Compose `YPtun/desktopApp` remains the Linux/macOS desktop client while the native
Windows client reaches feature parity.

## Requirements

- Windows 10 1809 or newer
- .NET 9 SDK
- Visual Studio 2022 with Desktop development with C++
- Rust toolchain with `x86_64-pc-windows-msvc` (the ARM64 target is installed automatically)
- Go and a MinGW-compatible C compiler for the bundled `yptuncore` runtime

On an x64 host, `build.ps1 -Architecture arm64` automatically downloads an x64-hosted LLVM-MinGW
cross toolchain and uses Rust's `aarch64-pc-windows-gnullvm` target. Every staged native file is
therefore ARM64; no x64 runtime is copied into the ARM64 package.

## Build

```powershell
./build.ps1 -Architecture x64
```

The script builds the Rust bridge, stages native runtime files and publishes the unpackaged,
self-contained WinUI application under `artifacts/<architecture>`.

Create the installer EXE and the single-file portable EXE after publishing:

```powershell
./package.ps1 -Architecture x64 -Version 4.0.0
```

The packaging step is intentionally unsigned locally. Official artifacts must be Authenticode
signed by the repository's release workflow before publication.

The portable EXE unpacks the self-contained app image once and passes its own path through
`YPTUN_PORTABLE_EXE`. Profiles, settings and logs live in `Data` next to the portable EXE and
`%APPDATA%\YPtun` is not used. The installed build keeps using `%APPDATA%\YPtun` so it remains
compatible with existing desktop data.
