#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "=== Building snolc-ffi for iOS (aarch64-apple-ios) ==="
rustup target add aarch64-apple-ios

cargo build --release -p snolc-ffi --target aarch64-apple-ios

OUT_DIR="../YPtun/sharedUI/build/generated/cores/ios"
mkdir -p "$OUT_DIR"
rm -rf "$OUT_DIR/SnolcCore.xcframework"

echo "=== Assembling SnolcCore.framework ==="
FW_DIR="target/SnolcCore.framework"
rm -rf "$FW_DIR"
mkdir -p "$FW_DIR/Headers"
mkdir -p "$FW_DIR/Modules"

cp target/aarch64-apple-ios/release/libsnolc_ffi.a "$FW_DIR/SnolcCore"
cp crates/snolc-ffi/include/snolc_ffi.h "$FW_DIR/Headers/snolc_ffi.h"

cat << 'EOF' > "$FW_DIR/Modules/module.modulemap"
framework module SnolcCore {
    umbrella header "snolc_ffi.h"
    export *
    module * { export * }
}
EOF

cat << 'EOF' > "$FW_DIR/Info.plist"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>SnolcCore</string>
    <key>CFBundleIdentifier</key>
    <string>org.olcbox.SnolcCore</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>SnolcCore</string>
    <key>CFBundlePackageType</key>
    <string>FMWK</string>
    <key>CFBundleShortVersionString</key>
    <string>0.0.4</string>
    <key>CFBundleVersion</key>
    <string>1</string>
    <key>MinimumOSVersion</key>
    <string>15.0</string>
</dict>
</plist>
EOF

echo "=== Packaging SnolcCore.xcframework ==="
xcodebuild -create-xcframework \
    -framework "$FW_DIR" \
    -output "$OUT_DIR/SnolcCore.xcframework"

echo "=== SnolcCore.xcframework built successfully at $OUT_DIR/SnolcCore.xcframework ==="
