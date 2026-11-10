#!/usr/bin/env bash
# Builds the UniFFI Rust bridge for iOS 17+, iOS Simulator, and macOS 14+, then places a local
# Swift package at YPtun/apple/Packages/YPtunRustBridge.
#
# Needs: macOS, Xcode command line tools, rustup, and the Apple Rust targets below.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$ROOT/.." && pwd)"
OUT="${OUT:-$ROOT/build/apple}"
PACKAGE_DIR="${PACKAGE_DIR:-$REPO/YPtun/apple/Packages/YPtunRustBridge}"
GENERATED="$OUT/generated"
HEADERS="$OUT/headers"

IOS_DEVICE=aarch64-apple-ios
IOS_SIM_ARM=aarch64-apple-ios-sim
IOS_SIM_INTEL=x86_64-apple-ios
MAC_ARM=aarch64-apple-darwin
MAC_INTEL=x86_64-apple-darwin

cd "$ROOT"
mkdir -p "$OUT" "$GENERATED" "$HEADERS"

export IPHONEOS_DEPLOYMENT_TARGET="${IPHONEOS_DEPLOYMENT_TARGET:-17.0}"
export MACOSX_DEPLOYMENT_TARGET="${MACOSX_DEPLOYMENT_TARGET:-14.0}"

for target in "$IOS_DEVICE" "$IOS_SIM_ARM" "$IOS_SIM_INTEL" "$MAC_ARM" "$MAC_INTEL"; do
  echo "Building yptun-ffi for $target"
  cargo build -p yptun-ffi --lib --release --locked --target "$target"
done

# UniFFI metadata is read from an unstripped host dynamic library.
cargo build -p yptun-ffi --lib --locked
HOST_LIBRARY="$ROOT/target/debug/libyptun_ffi.dylib"
test -s "$HOST_LIBRARY"
cargo run -p yptun-ffi --bin uniffi-bindgen --locked -- \
  generate --library "$HOST_LIBRARY" --language swift --out-dir "$GENERATED" --no-format

HEADER=$(find "$GENERATED" -maxdepth 1 -name '*FFI.h' -print -quit)
MODULEMAP=$(find "$GENERATED" -maxdepth 1 -name '*.modulemap' -print -quit)
SWIFT=$(find "$GENERATED" -maxdepth 1 -name '*.swift' -print -quit)
test -s "$HEADER"
test -s "$MODULEMAP"
test -s "$SWIFT"
cp "$HEADER" "$HEADERS/"
cp "$MODULEMAP" "$HEADERS/module.modulemap"

mkdir -p "$OUT/ios-simulator" "$OUT/macos"
xcrun lipo -create \
  "$ROOT/target/$IOS_SIM_ARM/release/libyptun_ffi.a" \
  "$ROOT/target/$IOS_SIM_INTEL/release/libyptun_ffi.a" \
  -output "$OUT/ios-simulator/libyptun_ffi.a"
xcrun lipo -create \
  "$ROOT/target/$MAC_ARM/release/libyptun_ffi.a" \
  "$ROOT/target/$MAC_INTEL/release/libyptun_ffi.a" \
  -output "$OUT/macos/libyptun_ffi.a"

rm -rf "$PACKAGE_DIR/Artifacts/yptun_ffiFFI.xcframework"
mkdir -p "$PACKAGE_DIR/Artifacts" "$PACKAGE_DIR/Sources/YPtunRustBridge/Generated"
rm -f "$PACKAGE_DIR/Sources/YPtunRustBridge/Generated/"*.swift

xcodebuild -create-xcframework \
  -library "$ROOT/target/$IOS_DEVICE/release/libyptun_ffi.a" -headers "$HEADERS" \
  -library "$OUT/ios-simulator/libyptun_ffi.a" -headers "$HEADERS" \
  -library "$OUT/macos/libyptun_ffi.a" -headers "$HEADERS" \
  -output "$PACKAGE_DIR/Artifacts/yptun_ffiFFI.xcframework"

cp "$SWIFT" "$PACKAGE_DIR/Sources/YPtunRustBridge/Generated/yptun_ffi.swift"
echo "Apple Rust bridge ready at $PACKAGE_DIR"
