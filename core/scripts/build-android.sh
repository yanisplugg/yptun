#!/usr/bin/env bash
# Builds libyptun_ffi.so for Android and generates the Kotlin bindings (uniffi).
#
# Needs: rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
#        cargo install cargo-ndk
#        ANDROID_NDK_HOME pointing at NDK 28.2.13676358
# Env:   ABIS (default "arm64-v8a armeabi-v7a x86_64"), API (default 23), OUT (default build/android)
# Out:   $OUT/jniLibs/<abi>/libyptun_ffi.so and $OUT/kotlin/org/olcbox/app/core/yptun_ffi.kt
set -euo pipefail
cd "$(dirname "$0")/.."

ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"
API="${API:-23}"
OUT="${OUT:-build/android}"

targets=()
for abi in $ABIS; do targets+=(-t "$abi"); done
cargo ndk "${targets[@]}" -P "$API" -o "$OUT/jniLibs" build -p yptun-ffi --release

# Bindings come from an unstripped host build (the release .so is stripped, metadata is gone).
cargo build -p yptun-ffi
lib=""
for f in target/debug/libyptun_ffi.so target/debug/libyptun_ffi.dylib target/debug/yptun_ffi.dll; do
  if [ -f "$f" ]; then lib="$f"; break; fi
done
if [ -z "$lib" ]; then echo "host cdylib not found in target/debug" >&2; exit 1; fi
cargo run -p yptun-ffi --features bindgen --bin uniffi-bindgen -- generate --library "$lib" --language kotlin --out-dir "$OUT/kotlin"
echo "done: $OUT/jniLibs, $OUT/kotlin"
