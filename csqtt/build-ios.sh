#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "=== Building csqtt-client for iOS (aarch64-apple-ios) ==="
rustup target add aarch64-apple-ios

export IPHONEOS_DEPLOYMENT_TARGET=15.0
export CFLAGS="-miphoneos-version-min=15.0"

cargo build --release --lib -p csqtt-client --manifest-path rust-client/Cargo.toml --target aarch64-apple-ios

OUT_DIR="../YPtun/sharedUI/build/generated/cores/ios"
mkdir -p "$OUT_DIR"
rm -rf "$OUT_DIR/CsqttCore.xcframework"

echo "=== Assembling CsqttCore.framework ==="
FW_DIR="target/CsqttCore.framework"
rm -rf "$FW_DIR"
mkdir -p "$FW_DIR/Headers"
mkdir -p "$FW_DIR/Modules"

if [ -f "rust-client/target/aarch64-apple-ios/release/libcsqtt_ffi.a" ]; then
    LIB_PATH="rust-client/target/aarch64-apple-ios/release/libcsqtt_ffi.a"
elif [ -f "target/aarch64-apple-ios/release/libcsqtt_ffi.a" ]; then
    LIB_PATH="target/aarch64-apple-ios/release/libcsqtt_ffi.a"
else
    echo "Error: libcsqtt_ffi.a not found"
    exit 1
fi

cp "$LIB_PATH" "$FW_DIR/CsqttCore"
cp include/csqtt_ffi.h "$FW_DIR/Headers/csqtt_ffi.h"

cat << 'EOF' > "$FW_DIR/Modules/module.modulemap"
framework module CsqttCore {
    umbrella header "csqtt_ffi.h"
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
    <string>CsqttCore</string>
    <key>CFBundleIdentifier</key>
    <string>org.olcbox.CsqttCore</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>CsqttCore</string>
    <key>CFBundlePackageType</key>
    <string>FMWK</string>
    <key>CFBundleShortVersionString</key>
    <string>2.1.9</string>
    <key>CFBundleVersion</key>
    <string>1</string>
    <key>MinimumOSVersion</key>
    <string>15.0</string>
</dict>
</plist>
EOF

echo "=== Packaging CsqttCore.xcframework ==="
xcodebuild -create-xcframework \
    -framework "$FW_DIR" \
    -output "$OUT_DIR/CsqttCore.xcframework"

echo "=== CsqttCore.xcframework built successfully at $OUT_DIR/CsqttCore.xcframework ==="
