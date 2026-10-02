#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "=== Building snolc-ffi for iOS (aarch64-apple-ios) ==="
rustup target add aarch64-apple-ios

cargo build --release -p snolc-ffi --target aarch64-apple-ios

OUT_DIR="../YPtun/sharedUI/build/generated/cores/ios"
mkdir -p "$OUT_DIR"
rm -rf "$OUT_DIR/SnolcCore.xcframework"

# Prepare headers and modulemap for Swift import
HEADER_DIR="crates/snolc-ffi/include"
mkdir -p "$HEADER_DIR/SnolcCore"
cp "$HEADER_DIR/snolc_ffi.h" "$HEADER_DIR/SnolcCore/snolc_ffi.h"
cat << 'EOF' > "$HEADER_DIR/SnolcCore/module.modulemap"
module SnolcCore [system] {
    header "snolc_ffi.h"
    export *
}
EOF

echo "=== Packaging SnolcCore.xcframework ==="
xcodebuild -create-xcframework \
    -library target/aarch64-apple-ios/release/libsnolc_ffi.a \
    -headers "$HEADER_DIR" \
    -output "$OUT_DIR/SnolcCore.xcframework"

echo "=== SnolcCore.xcframework built successfully at $OUT_DIR/SnolcCore.xcframework ==="
