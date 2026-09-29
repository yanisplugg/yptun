#!/bin/sh
# Builds the statically-linked snolc (all modules built in, packages = "builtin") for every YPtun target
# into prebuilt/. Needs rustup 1.98.1 + NDK (Android) — musl targets link with rust-lld and an empty libdl.a.
set -e
cd "$(dirname "$0")"
NDK=${ANDROID_NDK_HOME:-/c/Android/sdk/ndk/28.2.13676358}
BIN=$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin
mkdir -p prebuilt .stub; [ -f .stub/libdl.a ] || printf '!<arch>\n' > .stub/libdl.a
STUB=$(pwd -W 2>/dev/null || pwd)/.stub
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$BIN/aarch64-linux-android24-clang.cmd
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER=$BIN/armv7a-linux-androideabi24-clang.cmd
export CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER=rust-lld CARGO_TARGET_AARCH64_UNKNOWN_LINUX_MUSL_LINKER=rust-lld
b() { cargo build --release -p snolc-cli --target "$1"; }
m() { RUSTFLAGS="-L $STUB" cargo build --release -p snolc-cli --target "$1"; }
cargo build --release -p snolc-cli 
cp target/release/snolc.exe prebuilt/snolc-windows-amd64.exe
b aarch64-linux-android 
cp target/aarch64-linux-android/release/snolc prebuilt/snolc-android-arm64
b armv7-linux-androideabi 
cp target/armv7-linux-androideabi/release/snolc prebuilt/snolc-android-armv7
m x86_64-unknown-linux-musl
cp target/x86_64-unknown-linux-musl/release/snolc prebuilt/snolc-linux-amd64
m aarch64-unknown-linux-musl
cp target/aarch64-unknown-linux-musl/release/snolc prebuilt/snolc-linux-arm64
ls -la prebuilt
# the VPS installer uploads the static linux binaries from the app's assets
A=../YPtun/androidApp/src/main/assets/snolc; mkdir -p $A
for a in amd64 arm64; do gzip -9 -c prebuilt/snolc-linux-$a > $A/snolc-server-linux-$a.gz; done
