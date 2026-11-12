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

# Multiple Rust staticlibs normally contain colliding copies of std/core. Rust 1.99 has a
# purpose-built (currently unstable) archive post-processor that renames and hides every internal
# symbol while preserving #[no_mangle] C exports. RUSTC_BOOTSTRAP is deliberately scoped to these
# pinned staticlib builds; the final application link still verifies that all archives coexist.
build_specialized_rust() {
  env \
    CARGO_PROFILE_RELEASE_LTO=false \
    CARGO_PROFILE_RELEASE_STRIP=none \
    RUSTC_BOOTSTRAP=1 \
    cargo rustc "$@" -- \
      -Zstaticlib-rename-internal-symbols \
      -Zstaticlib-hide-internal-symbols
}

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

# Verify rustc's staticlib symbol isolation before packaging the archive as an XCFramework.
hide_rust_symbols() {
  local input="$1"
  local output="$2"
  local exports="$3"
  local label="$4"
  local arch
  local rust_host
  local llvm_nm
  local llvm_ar
  local symbols
  local internal_symbols
  local target
  local target_libdir
  local compiler_builtins
  local archive_members
  local builtin_members
  local duplicate_builtin_members

  arch="$(xcrun lipo -archs "$input")"
  if [[ -z "$arch" || "$arch" == *" "* ]]; then
    echo "Expected one architecture in $input, found: ${arch:-none}" >&2
    return 1
  fi

  rust_host="$(rustc -vV | sed -n 's/^host: //p')"
  llvm_nm="$(rustc --print sysroot)/lib/rustlib/$rust_host/bin/llvm-nm"
  llvm_ar="$(rustc --print sysroot)/lib/rustlib/$rust_host/bin/llvm-ar"
  if [[ ! -x "$llvm_nm" || ! -x "$llvm_ar" ]]; then
    echo "Rust LLVM tools are unavailable; install rustup component llvm-tools-preview" >&2
    return 1
  fi
  symbols="$WORK/$label-symbols.txt"
  internal_symbols="$WORK/$label-internal-symbols.txt"
  archive_members="$WORK/$label-archive-members.txt"
  builtin_members="$WORK/$label-compiler-builtins-members.txt"
  duplicate_builtin_members="$WORK/$label-duplicate-compiler-builtins-members.txt"

  rm -f "$output"
  cp "$input" "$output"

  # `staticlib-rename-internal-symbols` handles Rust definitions, but compiler_builtins also embeds
  # native assembly/C objects with intentionally fixed ABI names (atomic fallbacks, arithmetic
  # helpers, etc.). Every Rust staticlib carries the same objects and Xcode force-loads them all.
  # Keep the canonical copy in the always-linked yptun_ffi archive and remove those exact members
  # from the two specialized archives; their undefined references resolve from yptun_ffi.
  target="$(basename "$(dirname "$(dirname "$input")")")"
  target_libdir="$(rustc --print target-libdir --target "$target")"
  compiler_builtins="$(find "$target_libdir" -maxdepth 1 -name 'libcompiler_builtins-*.rlib' -print -quit)"
  if [[ -z "$compiler_builtins" ]]; then
    echo "compiler_builtins rlib is unavailable for $target" >&2
    return 1
  fi
  "$llvm_ar" t "$output" | sort -u > "$archive_members"
  "$llvm_ar" t "$compiler_builtins" | sort -u > "$builtin_members"
  comm -12 "$archive_members" "$builtin_members" > "$duplicate_builtin_members"
  if [[ ! -s "$duplicate_builtin_members" ]]; then
    echo "No bundled compiler_builtins members found in $output" >&2
    return 1
  fi
  xargs -n 100 "$llvm_ar" d "$output" < "$duplicate_builtin_members"
  "$llvm_ar" s "$output"

  "$llvm_nm" --extern-only --defined-only --just-symbol-name "$output" > "$symbols"
  grep -vxFf "$exports" "$symbols" > "$internal_symbols" || true
  if ! grep -Eq '[._]rs[[:xdigit:]]{8,}$' "$internal_symbols"; then
    echo "rustc did not rename internal staticlib symbols in $output" >&2
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
  build_specialized_rust --release --locked --manifest-path "$ROOT/snolc/Cargo.toml" -p snolc-ffi --target aarch64-apple-ios
  hide_rust_symbols \
    "$ROOT/snolc/target/aarch64-apple-ios/release/libsnolc_ffi.a" \
    "$WORK/libsnolc-ios-arm64.a" "$WORK/SnolcCore.exports" snolc-ios-arm64 \
    iphoneos ios "$IPHONEOS_DEPLOYMENT_TARGET"
  build_specialized_rust --release --locked --manifest-path "$ROOT/snolc/Cargo.toml" -p snolc-ffi --target aarch64-apple-ios-sim
  hide_rust_symbols \
    "$ROOT/snolc/target/aarch64-apple-ios-sim/release/libsnolc_ffi.a" \
    "$WORK/libsnolc-simulator-arm64.a" "$WORK/SnolcCore.exports" snolc-simulator-arm64 \
    iphonesimulator ios-simulator "$IPHONEOS_DEPLOYMENT_TARGET"
  build_specialized_rust --release --locked --manifest-path "$ROOT/snolc/Cargo.toml" -p snolc-ffi --target x86_64-apple-ios
  hide_rust_symbols \
    "$ROOT/snolc/target/x86_64-apple-ios/release/libsnolc_ffi.a" \
    "$WORK/libsnolc-simulator-x86_64.a" "$WORK/SnolcCore.exports" snolc-simulator-x86_64 \
    iphonesimulator ios-simulator "$IPHONEOS_DEPLOYMENT_TARGET"
  xcrun lipo -create \
    "$WORK/libsnolc-simulator-arm64.a" \
    "$WORK/libsnolc-simulator-x86_64.a" \
    -output "$WORK/libsnolc-simulator.a"
  build_specialized_rust --release --locked --manifest-path "$ROOT/snolc/Cargo.toml" -p snolc-ffi --target aarch64-apple-darwin
  hide_rust_symbols \
    "$ROOT/snolc/target/aarch64-apple-darwin/release/libsnolc_ffi.a" \
    "$WORK/libsnolc-macos-arm64.a" "$WORK/SnolcCore.exports" snolc-macos-arm64 \
    macosx macos "$MACOSX_DEPLOYMENT_TARGET"
  build_specialized_rust --release --locked --manifest-path "$ROOT/snolc/Cargo.toml" -p snolc-ffi --target x86_64-apple-darwin
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
  local target_dir="$ROOT/csqtt/rust-client/target"
  build_specialized_rust --release --locked --lib --manifest-path "$ROOT/csqtt/rust-client/Cargo.toml" -p csqtt-client --target aarch64-apple-ios
  hide_rust_symbols \
    "$target_dir/aarch64-apple-ios/release/libcsqtt_ffi.a" \
    "$WORK/libcsqtt-ios-arm64.a" "$WORK/CsqttCore.exports" csqtt-ios-arm64 \
    iphoneos ios "$IPHONEOS_DEPLOYMENT_TARGET"
  build_specialized_rust --release --locked --lib --manifest-path "$ROOT/csqtt/rust-client/Cargo.toml" -p csqtt-client --target aarch64-apple-ios-sim
  hide_rust_symbols \
    "$target_dir/aarch64-apple-ios-sim/release/libcsqtt_ffi.a" \
    "$WORK/libcsqtt-simulator-arm64.a" "$WORK/CsqttCore.exports" csqtt-simulator-arm64 \
    iphonesimulator ios-simulator "$IPHONEOS_DEPLOYMENT_TARGET"
  build_specialized_rust --release --locked --lib --manifest-path "$ROOT/csqtt/rust-client/Cargo.toml" -p csqtt-client --target x86_64-apple-ios
  hide_rust_symbols \
    "$target_dir/x86_64-apple-ios/release/libcsqtt_ffi.a" \
    "$WORK/libcsqtt-simulator-x86_64.a" "$WORK/CsqttCore.exports" csqtt-simulator-x86_64 \
    iphonesimulator ios-simulator "$IPHONEOS_DEPLOYMENT_TARGET"
  xcrun lipo -create \
    "$WORK/libcsqtt-simulator-arm64.a" \
    "$WORK/libcsqtt-simulator-x86_64.a" \
    -output "$WORK/libcsqtt-simulator.a"
  build_specialized_rust --release --locked --lib --manifest-path "$ROOT/csqtt/rust-client/Cargo.toml" -p csqtt-client --target aarch64-apple-darwin
  hide_rust_symbols \
    "$target_dir/aarch64-apple-darwin/release/libcsqtt_ffi.a" \
    "$WORK/libcsqtt-macos-arm64.a" "$WORK/CsqttCore.exports" csqtt-macos-arm64 \
    macosx macos "$MACOSX_DEPLOYMENT_TARGET"
  build_specialized_rust --release --locked --lib --manifest-path "$ROOT/csqtt/rust-client/Cargo.toml" -p csqtt-client --target x86_64-apple-darwin
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
