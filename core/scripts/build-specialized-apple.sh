#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/YPtun/sharedUI/build/generated/cores/ios"
WORK="$ROOT/core/build/specialized-apple"
TARGETS=(aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios aarch64-apple-darwin x86_64-apple-darwin)

mkdir -p "$OUT" "$WORK"
rustup target add "${TARGETS[@]}"

make_headers() {
  local module="$1"
  local source_header="$2"
  local root="$WORK/$module-headers"
  local output="$root/$module"
  rm -rf "$root"
  mkdir -p "$output"
  cp "$source_header" "$output/"
  local header
  header="$(basename "$source_header")"
  cat > "$output/module.modulemap" <<EOF
module $module {
  header "$header"
  export *
}
EOF
}

verify_module_map() {
  local framework="$1"
  local module="$2"
  test -n "$(find "$framework" -path "*/Headers/$module/module.modulemap" -print -quit)"
}

build_snolc() {
  for target in "${TARGETS[@]}"; do
    cargo build --release --manifest-path "$ROOT/snolc/Cargo.toml" -p snolc-ffi --target "$target"
  done
  xcrun lipo -create \
    "$ROOT/snolc/target/aarch64-apple-ios-sim/release/libsnolc_ffi.a" \
    "$ROOT/snolc/target/x86_64-apple-ios/release/libsnolc_ffi.a" \
    -output "$WORK/libsnolc-simulator.a"
  xcrun lipo -create \
    "$ROOT/snolc/target/aarch64-apple-darwin/release/libsnolc_ffi.a" \
    "$ROOT/snolc/target/x86_64-apple-darwin/release/libsnolc_ffi.a" \
    -output "$WORK/libsnolc-macos.a"
  make_headers SnolcCore "$ROOT/snolc/crates/snolc-ffi/include/snolc_ffi.h"
  rm -rf "$OUT/SnolcCore.xcframework"
  xcodebuild -create-xcframework \
    -library "$ROOT/snolc/target/aarch64-apple-ios/release/libsnolc_ffi.a" -headers "$WORK/SnolcCore-headers" \
    -library "$WORK/libsnolc-simulator.a" -headers "$WORK/SnolcCore-headers" \
    -library "$WORK/libsnolc-macos.a" -headers "$WORK/SnolcCore-headers" \
    -output "$OUT/SnolcCore.xcframework"
  verify_module_map "$OUT/SnolcCore.xcframework" SnolcCore
}

build_csqtt() {
  export IPHONEOS_DEPLOYMENT_TARGET=17.0
  for target in "${TARGETS[@]}"; do
    cargo build --release --lib --manifest-path "$ROOT/csqtt/rust-client/Cargo.toml" -p csqtt-client --target "$target"
  done
  local target_dir="$ROOT/csqtt/rust-client/target"
  xcrun lipo -create \
    "$target_dir/aarch64-apple-ios-sim/release/libcsqtt_ffi.a" \
    "$target_dir/x86_64-apple-ios/release/libcsqtt_ffi.a" \
    -output "$WORK/libcsqtt-simulator.a"
  xcrun lipo -create \
    "$target_dir/aarch64-apple-darwin/release/libcsqtt_ffi.a" \
    "$target_dir/x86_64-apple-darwin/release/libcsqtt_ffi.a" \
    -output "$WORK/libcsqtt-macos.a"
  make_headers CsqttCore "$ROOT/csqtt/include/csqtt_ffi.h"
  rm -rf "$OUT/CsqttCore.xcframework"
  xcodebuild -create-xcframework \
    -library "$target_dir/aarch64-apple-ios/release/libcsqtt_ffi.a" -headers "$WORK/CsqttCore-headers" \
    -library "$WORK/libcsqtt-simulator.a" -headers "$WORK/CsqttCore-headers" \
    -library "$WORK/libcsqtt-macos.a" -headers "$WORK/CsqttCore-headers" \
    -output "$OUT/CsqttCore.xcframework"
  verify_module_map "$OUT/CsqttCore.xcframework" CsqttCore
}

build_snolc
build_csqtt
