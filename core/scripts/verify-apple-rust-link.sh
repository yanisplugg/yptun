#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$ROOT/core/build/specialized-apple"
TARGET_ROOT="$ROOT/core/target"

export IPHONEOS_DEPLOYMENT_TARGET="${IPHONEOS_DEPLOYMENT_TARGET:-17.0}"
export MACOSX_DEPLOYMENT_TARGET="${MACOSX_DEPLOYMENT_TARGET:-14.0}"

probe_slice() {
  local target="$1"
  local arch="$2"
  local sdk="$3"
  local platform="$4"
  local deployment_target="$5"
  local suffix="$6"
  local sdk_path
  local sdk_version
  local output="$WORK/rust-link-probe-$suffix.o"

  sdk_path="$(xcrun --sdk "$sdk" --show-sdk-path)"
  sdk_version="$(xcrun --sdk "$sdk" --show-sdk-version)"
  rm -f "$output"
  xcrun ld -r \
    -arch "$arch" \
    -platform_version "$platform" "$deployment_target" "$sdk_version" \
    -syslibroot "$sdk_path" \
    -all_load \
    "$TARGET_ROOT/$target/release/libyptun_ffi.a" \
    "$WORK/libsnolc-$suffix.a" \
    "$WORK/libcsqtt-$suffix.a" \
    -o "$output"
  test -s "$output"
  echo "Rust archive link probe passed: $suffix"
}

probe_slice aarch64-apple-ios arm64 iphoneos ios \
  "$IPHONEOS_DEPLOYMENT_TARGET" ios-arm64
probe_slice aarch64-apple-ios-sim arm64 iphonesimulator ios-simulator \
  "$IPHONEOS_DEPLOYMENT_TARGET" simulator-arm64
probe_slice x86_64-apple-ios x86_64 iphonesimulator ios-simulator \
  "$IPHONEOS_DEPLOYMENT_TARGET" simulator-x86_64
probe_slice aarch64-apple-darwin arm64 macosx macos \
  "$MACOSX_DEPLOYMENT_TARGET" macos-arm64
probe_slice x86_64-apple-darwin x86_64 macosx macos \
  "$MACOSX_DEPLOYMENT_TARGET" macos-x86_64

echo "All combined Apple Rust archive probes passed."
