#!/bin/sh
# Builds every csqtt artifact YPtun ships, into prebuilt/ (committed, like snolc/prebuilt):
#   csqtt-<os>-<arch>      the Rust client           (windows-amd64.exe, linux-{amd64,arm64}, android-{arm64,armv7})
#   csqtthost-<os>-<arch>  the Go bridge (bridge/)   (same targets)
#   csqtt-server-linux-<arch>.gz  the server, also copied to the app's assets/csqtt/ for the VPS installer
# Needs: Rust >= 1.97.1 with targets {aarch64,armv7}-linux-android, {x86_64,aarch64}-unknown-linux-gnu,
# {x86_64,aarch64}-unknown-linux-musl + armv7-unknown-linux-musleabihf (the server), cargo-ndk, cargo-zigbuild + zig (pip install ziglang), the Android NDK, Go.
# Usage: build-all.sh [client] [server] [bridge]   (default: all three)
set -e
cd "$(dirname "$0")"
NDK=${ANDROID_NDK_HOME:-/c/Android/sdk/ndk/28.2.13676358}
export ANDROID_NDK_HOME=$NDK
mkdir -p prebuilt
WHAT=${*:-client server bridge}
ASSETS=../YPtun/androidApp/src/main/assets/csqtt

has() { case " $WHAT " in *" $1 "*) return 0 ;; esac; return 1; }

if has client; then
  (cd rust-client
   cargo build --release
   cp target/release/client.exe ../prebuilt/csqtt-windows-amd64.exe
   cargo ndk -t arm64-v8a -t armeabi-v7a -P 26 build --release
   cp target/aarch64-linux-android/release/client ../prebuilt/csqtt-android-arm64
   cp target/armv7-linux-androideabi/release/client ../prebuilt/csqtt-android-armv7
   # glibc, not musl: the client uses libc's recvmmsg/sendmmsg/msghdr the glibc way (musl's differ and don't
   # compile). Linking against glibc 2.17 runs on any distro from CentOS 7 / Debian 8 on. x86_64 Android
   # (emulators only) is not built, like snolc.
   cargo zigbuild --release --target x86_64-unknown-linux-gnu.2.17
   cp target/x86_64-unknown-linux-gnu/release/client ../prebuilt/csqtt-linux-amd64
   cargo zigbuild --release --target aarch64-unknown-linux-gnu.2.17
   cp target/aarch64-unknown-linux-gnu/release/client ../prebuilt/csqtt-linux-arm64)
   # Windows ARM64 is not built: it needs the MSVC ARM64 toolchain (cl.exe) for zstd-sys/aws-lc.
fi

if has server; then
  mkdir -p "$ASSETS"
  (cd rust-server
   for t in x86_64-unknown-linux-musl:amd64 aarch64-unknown-linux-musl:arm64 armv7-unknown-linux-musleabihf:armv7; do
     triple=${t%%:*}; arch=${t##*:}
     cargo zigbuild --release --target "$triple"
     gzip -9 -c "target/$triple/release/csqtt" > "../$ASSETS/csqtt-server-linux-$arch.gz"
   done)
  cp scripts/deploy.sh "$ASSETS/deploy.sh"
fi

if has bridge; then
  (cd bridge
   b() { env GOOS=$1 GOARCH=$2 CGO_ENABLED=0 ${3:+GOARM=$3} go build -trimpath -ldflags "-s -w" -o "../prebuilt/csqtthost-$4" .; }
   b windows amd64 "" windows-amd64.exe
   b linux amd64 "" linux-amd64
   b linux arm64 "" linux-arm64
   # Android runs plain static Linux executables from nativeLibraryDir; the bridge does no name lookups
   # of its own (the Rust client resolves the server), so no cgo/NDK is needed.
   b linux arm64 "" android-arm64
   b linux arm 7 android-armv7)
fi
ls -la prebuilt
