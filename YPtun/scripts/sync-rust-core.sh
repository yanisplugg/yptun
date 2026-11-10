#!/usr/bin/env bash
# Copies artifacts produced by core/scripts/build-android.sh into the Android source tree.
set -euo pipefail

app_root="$(cd "$(dirname "$0")/.." && pwd)"
repo_root="$(cd "$app_root/.." && pwd)"
core_dir="${1:-$repo_root/core}"
output="$core_dir/build/android"
binding="$output/kotlin/org/olcbox/app/core/yptun_ffi.kt"

if [[ ! -f "$binding" ]]; then
  echo "Rust Android bindings not found at $binding. Run core/scripts/build-android.sh first." >&2
  exit 1
fi

binding_target="$app_root/sharedUI/src/androidMain/kotlin/org/olcbox/app/core/yptun_ffi.kt"
mkdir -p "$(dirname "$binding_target")"
cp "$binding" "$binding_target"
# UniFFI 0.29 emits trailing spaces when ktlint is unavailable. Keep generated commits clean and
# collapse its extra blank lines at EOF without requiring ktlint on every developer machine.
awk '{ sub(/[[:space:]]+$/, ""); lines[NR]=$0; if ($0 != "") last=NR }
     END { for (i=1; i<=last; i++) print lines[i] }' "$binding_target" > "$binding_target.tmp"
mv "$binding_target.tmp" "$binding_target"

for abi in arm64-v8a armeabi-v7a x86_64; do
  library="$output/jniLibs/$abi/libyptun_ffi.so"
  if [[ ! -f "$library" ]]; then
    echo "Rust library missing for $abi at $library" >&2
    exit 1
  fi
  target="$app_root/androidApp/src/main/jniLibs/$abi/libyptun_ffi.so"
  mkdir -p "$(dirname "$target")"
  cp "$library" "$target"
done

echo "Synchronized yptun-core Kotlin bindings and Android libraries from $core_dir"
