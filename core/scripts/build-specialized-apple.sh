#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/YPtun/sharedUI/build/generated/cores/ios"
WORK="$ROOT/core/build/specialized-apple"
TARGETS=(aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios aarch64-apple-darwin x86_64-apple-darwin)

mkdir -p "$OUT" "$WORK"
rustup target add "${TARGETS[@]}"
export IPHONEOS_DEPLOYMENT_TARGET="${IPHONEOS_DEPLOYMENT_TARGET:-17.0}"
export MACOSX_DEPLOYMENT_TARGET="${MACOSX_DEPLOYMENT_TARGET:-14.0}"

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

# Linking several Rust staticlibs into one Apple target otherwise exports a copy of Rust's std/core
# from every archive. macOS rejects those duplicate symbols. Collapse each specialized archive into
# one relocatable object, then keep only its C ABI global; internal cross-object references remain
# valid because ld localizes symbols only after the collapse.
hide_rust_symbols() {
  local input="$1"
  local output="$2"
  local exports="$3"
  local label="$4"
  local sdk="$5"
  local platform="$6"
  local deployment_target="$7"
  local relocatable="$WORK/$label.o"
  local arch
  local sdk_path
  local sdk_version
  local rust_host
  local llvm_nm
  local symbols

  arch="$(xcrun lipo -archs "$input")"
  if [[ -z "$arch" || "$arch" == *" "* ]]; then
    echo "Expected one architecture in $input, found: ${arch:-none}" >&2
    return 1
  fi

  sdk_path="$(xcrun --sdk "$sdk" --show-sdk-path)"
  sdk_version="$(xcrun --sdk "$sdk" --show-sdk-version)"
  rust_host="$(rustc -vV | sed -n 's/^host: //p')"
  llvm_nm="$(rustc --print sysroot)/lib/rustlib/$rust_host/bin/llvm-nm"
  if [[ ! -x "$llvm_nm" ]]; then
    echo "Rust llvm-nm is unavailable; install rustup component llvm-tools-preview" >&2
    return 1
  fi
  symbols="$WORK/$label-symbols.txt"

  rm -f "$relocatable" "$output"
  xcrun ld -r \
    -arch "$arch" \
    -platform_version "$platform" "$deployment_target" "$sdk_version" \
    -syslibroot "$sdk_path" \
    -all_load "$input" \
    -exported_symbols_list "$exports" \
    -o "$relocatable"
  xcrun libtool -static -o "$output" "$relocatable"

  "$llvm_nm" --extern-only --defined-only --just-symbol-name "$output" > "$symbols"
  if grep -Eq '^(__R|__ZN)' "$symbols"; then
    echo "Rust implementation symbols remain public in $output" >&2
    return 1
  fi

  while IFS= read -r symbol; do
    if ! grep -Fxq "$symbol" "$symbols"; then
      echo "Required C ABI symbol $symbol is missing from $output" >&2
      return 1
    fi
  done < "$exports"
}

cat > "$WORK/SnolcCore.exports" <<'EOF'
_snolc_ffi_version
_snolc_ffi_start
_snolc_ffi_stop
_snolc_ffi_is_running
_snolc_ffi_free_string
EOF

cat > "$WORK/CsqttCore.exports" <<'EOF'
_csqtt_ffi_version
_csqtt_ffi_start
_csqtt_ffi_stop
_csqtt_ffi_is_running
_csqtt_ffi_free_string
EOF

build_snolc() {
  for target in "${TARGETS[@]}"; do
    cargo build --release --manifest-path "$ROOT/snolc/Cargo.toml" -p snolc-ffi --target "$target"
  done
  hide_rust_symbols \
    "$ROOT/snolc/target/aarch64-apple-ios/release/libsnolc_ffi.a" \
    "$WORK/libsnolc-ios-arm64.a" "$WORK/SnolcCore.exports" snolc-ios-arm64 \
    iphoneos ios "$IPHONEOS_DEPLOYMENT_TARGET"
  hide_rust_symbols \
    "$ROOT/snolc/target/aarch64-apple-ios-sim/release/libsnolc_ffi.a" \
    "$WORK/libsnolc-simulator-arm64.a" "$WORK/SnolcCore.exports" snolc-simulator-arm64 \
    iphonesimulator ios-simulator "$IPHONEOS_DEPLOYMENT_TARGET"
  hide_rust_symbols \
    "$ROOT/snolc/target/x86_64-apple-ios/release/libsnolc_ffi.a" \
    "$WORK/libsnolc-simulator-x86_64.a" "$WORK/SnolcCore.exports" snolc-simulator-x86_64 \
    iphonesimulator ios-simulator "$IPHONEOS_DEPLOYMENT_TARGET"
  xcrun lipo -create \
    "$WORK/libsnolc-simulator-arm64.a" \
    "$WORK/libsnolc-simulator-x86_64.a" \
    -output "$WORK/libsnolc-simulator.a"
  hide_rust_symbols \
    "$ROOT/snolc/target/aarch64-apple-darwin/release/libsnolc_ffi.a" \
    "$WORK/libsnolc-macos-arm64.a" "$WORK/SnolcCore.exports" snolc-macos-arm64 \
    macosx macos "$MACOSX_DEPLOYMENT_TARGET"
  hide_rust_symbols \
    "$ROOT/snolc/target/x86_64-apple-darwin/release/libsnolc_ffi.a" \
    "$WORK/libsnolc-macos-x86_64.a" "$WORK/SnolcCore.exports" snolc-macos-x86_64 \
    macosx macos "$MACOSX_DEPLOYMENT_TARGET"
  xcrun lipo -create \
    "$WORK/libsnolc-macos-arm64.a" \
    "$WORK/libsnolc-macos-x86_64.a" \
    -output "$WORK/libsnolc-macos.a"
  make_headers SnolcCore "$ROOT/snolc/crates/snolc-ffi/include/snolc_ffi.h"
  rm -rf "$OUT/SnolcCore.xcframework"
  xcodebuild -create-xcframework \
    -library "$WORK/libsnolc-ios-arm64.a" -headers "$WORK/SnolcCore-headers" \
    -library "$WORK/libsnolc-simulator.a" -headers "$WORK/SnolcCore-headers" \
    -library "$WORK/libsnolc-macos.a" -headers "$WORK/SnolcCore-headers" \
    -output "$OUT/SnolcCore.xcframework"
  verify_module_map "$OUT/SnolcCore.xcframework" SnolcCore
}

build_csqtt() {
  for target in "${TARGETS[@]}"; do
    cargo build --release --lib --manifest-path "$ROOT/csqtt/rust-client/Cargo.toml" -p csqtt-client --target "$target"
  done
  local target_dir="$ROOT/csqtt/rust-client/target"
  hide_rust_symbols \
    "$target_dir/aarch64-apple-ios/release/libcsqtt_ffi.a" \
    "$WORK/libcsqtt-ios-arm64.a" "$WORK/CsqttCore.exports" csqtt-ios-arm64 \
    iphoneos ios "$IPHONEOS_DEPLOYMENT_TARGET"
  hide_rust_symbols \
    "$target_dir/aarch64-apple-ios-sim/release/libcsqtt_ffi.a" \
    "$WORK/libcsqtt-simulator-arm64.a" "$WORK/CsqttCore.exports" csqtt-simulator-arm64 \
    iphonesimulator ios-simulator "$IPHONEOS_DEPLOYMENT_TARGET"
  hide_rust_symbols \
    "$target_dir/x86_64-apple-ios/release/libcsqtt_ffi.a" \
    "$WORK/libcsqtt-simulator-x86_64.a" "$WORK/CsqttCore.exports" csqtt-simulator-x86_64 \
    iphonesimulator ios-simulator "$IPHONEOS_DEPLOYMENT_TARGET"
  xcrun lipo -create \
    "$WORK/libcsqtt-simulator-arm64.a" \
    "$WORK/libcsqtt-simulator-x86_64.a" \
    -output "$WORK/libcsqtt-simulator.a"
  hide_rust_symbols \
    "$target_dir/aarch64-apple-darwin/release/libcsqtt_ffi.a" \
    "$WORK/libcsqtt-macos-arm64.a" "$WORK/CsqttCore.exports" csqtt-macos-arm64 \
    macosx macos "$MACOSX_DEPLOYMENT_TARGET"
  hide_rust_symbols \
    "$target_dir/x86_64-apple-darwin/release/libcsqtt_ffi.a" \
    "$WORK/libcsqtt-macos-x86_64.a" "$WORK/CsqttCore.exports" csqtt-macos-x86_64 \
    macosx macos "$MACOSX_DEPLOYMENT_TARGET"
  xcrun lipo -create \
    "$WORK/libcsqtt-macos-arm64.a" \
    "$WORK/libcsqtt-macos-x86_64.a" \
    -output "$WORK/libcsqtt-macos.a"
  make_headers CsqttCore "$ROOT/csqtt/include/csqtt_ffi.h"
  rm -rf "$OUT/CsqttCore.xcframework"
  xcodebuild -create-xcframework \
    -library "$WORK/libcsqtt-ios-arm64.a" -headers "$WORK/CsqttCore-headers" \
    -library "$WORK/libcsqtt-simulator.a" -headers "$WORK/CsqttCore-headers" \
    -library "$WORK/libcsqtt-macos.a" -headers "$WORK/CsqttCore-headers" \
    -output "$OUT/CsqttCore.xcframework"
  verify_module_map "$OUT/CsqttCore.xcframework" CsqttCore
}

build_snolc
build_csqtt
