#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$ROOT/core/build/specialized-apple"
TARGET_ROOT="$ROOT/core/target"

export IPHONEOS_DEPLOYMENT_TARGET="${IPHONEOS_DEPLOYMENT_TARGET:-16.0}"
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
  local undefined="$WORK/rust-link-probe-$suffix-undefined.txt"
  local rust_host
  local llvm_nm

  sdk_path="$(xcrun --sdk "$sdk" --show-sdk-path)"
  sdk_version="$(xcrun --sdk "$sdk" --show-sdk-version)"
  rust_host="$(rustc -vV | sed -n 's/^host: //p')"
  llvm_nm="$(rustc --print sysroot)/lib/rustlib/$rust_host/bin/llvm-nm"
  if [[ ! -x "$llvm_nm" ]]; then
    echo "Rust llvm-nm is unavailable; install rustup component llvm-tools-preview" >&2
    return 1
  fi
  rm -f "$output" "$undefined"

  # Match the real Xcode link: pull each staticlib through its public C entrypoints instead of
  # force-loading every archive member. `-all_load` creates artificial compiler_builtins clashes
  # that normal archive resolution deliberately coalesces.
  xcrun ld -r \
    -arch "$arch" \
    -platform_version "$platform" "$deployment_target" "$sdk_version" \
    -syslibroot "$sdk_path" \
    -u _snolc_ffi_version \
    -u _snolc_ffi_start \
    -u _snolc_ffi_stop \
    -u _snolc_ffi_is_running \
    -u _snolc_ffi_free_string \
    -u _csqtt_ffi_version \
    -u _csqtt_ffi_start \
    -u _csqtt_ffi_stop \
    -u _csqtt_ffi_is_running \
    -u _csqtt_ffi_free_string \
    "$TARGET_ROOT/$target/release/libyptun_ffi.a" \
    "$WORK/libsnolc-$suffix.a" \
    "$WORK/libcsqtt-$suffix.a" \
    -o "$output"
  test -s "$output"
  "$llvm_nm" --undefined-only --just-symbol-name "$output" > "$undefined"
  if grep -Eq '^___.*[._]rs[[:xdigit:]]{8,}$' "$undefined"; then
    echo "Unresolved namespaced Rust helpers remain after combined link: $suffix" >&2
    grep -E '^___.*[._]rs[[:xdigit:]]{8,}$' "$undefined" | head -20 >&2
    return 1
  fi
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
