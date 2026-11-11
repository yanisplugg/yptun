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
HEADER_ROOT="$OUT/headers"
HEADERS="$HEADER_ROOT/yptun_ffiFFI"

IOS_DEVICE=aarch64-apple-ios
IOS_SIM_ARM=aarch64-apple-ios-sim
IOS_SIM_INTEL=x86_64-apple-ios
MAC_ARM=aarch64-apple-darwin
MAC_INTEL=x86_64-apple-darwin

cd "$ROOT"
# Do not let a previous generation leave stale headers or bindings in the XCFramework.
rm -rf "$GENERATED" "$HEADER_ROOT" "$OUT/ios-simulator" "$OUT/macos"
mkdir -p "$OUT" "$GENERATED" "$HEADERS" "$OUT/ios-simulator" "$OUT/macos"

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
cargo run -p yptun-ffi --features bindgen --bin uniffi-bindgen --locked -- \
  generate --library "$HOST_LIBRARY" --language swift --out-dir "$GENERATED" --no-format

HEADER="$GENERATED/yptun_ffiFFI.h"
MODULEMAP="$GENERATED/yptun_ffiFFI.modulemap"
SWIFT="$GENERATED/yptun_ffi.swift"
test -s "$HEADER"
test -s "$MODULEMAP"
test -s "$SWIFT"
cp "$HEADER" "$HEADERS/"
cp "$MODULEMAP" "$HEADERS/module.modulemap"

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
  -library "$ROOT/target/$IOS_DEVICE/release/libyptun_ffi.a" -headers "$HEADER_ROOT" \
  -library "$OUT/ios-simulator/libyptun_ffi.a" -headers "$HEADER_ROOT" \
  -library "$OUT/macos/libyptun_ffi.a" -headers "$HEADER_ROOT" \
  -output "$PACKAGE_DIR/Artifacts/yptun_ffiFFI.xcframework"
test -n "$(find "$PACKAGE_DIR/Artifacts/yptun_ffiFFI.xcframework" \
  -path '*/Headers/yptun_ffiFFI/module.modulemap' -print -quit)"

cp "$SWIFT" "$PACKAGE_DIR/Sources/YPtunRustBridge/Generated/yptun_ffi.swift"
echo "Apple Rust bridge ready at $PACKAGE_DIR"
