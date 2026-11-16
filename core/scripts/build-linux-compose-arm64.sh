#!/usr/bin/env bash
set -euo pipefail

cache_dir="/mnt/d/yptun/.arm64-cache"
work_dir="/tmp/work-arm64"
appdir="$work_dir/AppDir-arm64"
debdir="$work_dir/deb-arm64"

rm -rf "$work_dir"
mkdir -p "$work_dir"

echo "=== Preparing ARM64 AppDir ==="
mkdir -p "$appdir"
cp -a /mnt/d/yptun/YPtun/desktopApp/build/compose/binaries/main-release/appimage/AppDir/. "$appdir/"

echo "=== Extracting Temurin ARM64 JRE ==="
rm -rf "$appdir/lib/runtime"
mkdir -p "$appdir/lib/runtime"
tar -xzf "$cache_dir/jre-arm64.tar.gz" -C "$appdir/lib/runtime" --strip-components=1

echo "=== Updating Skiko for ARM64 ==="
unzip -p "$cache_dir/skiko-arm64.jar" libskiko-linux-arm64.so > "$appdir/lib/app/libskiko-linux-arm64.so"
chmod 755 "$appdir/lib/app/libskiko-linux-arm64.so"
rm -f "$appdir/lib/app/libskiko-linux-x64.so"
cp "$cache_dir/skiko-arm64.jar" "$appdir/lib/app/skiko-awt-runtime-linux-arm64-0.148.1.jar"
rm -f "$appdir/lib/app/skiko-awt-runtime-linux-x64-"*.jar

echo "=== Updating YPtun.cfg ==="
sed -i "s/skiko-awt-runtime-linux-x64-[^.]*\.jar/skiko-awt-runtime-linux-arm64-0.148.1.jar/g" "$appdir/lib/app/YPtun.cfg"

echo "=== Writing ARM64 launcher ==="
cat > "$appdir/bin/YPtun" << 'LAUNCHER'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
APPDIR="$(dirname "$HERE")"
exec "$APPDIR/lib/runtime/bin/java" \
  -Djpackage.app-version=4.0.0 \
  -Dcompose.application.resources.dir="$APPDIR/lib/app/resources" \
  -Dcompose.application.configure.swing.globals=true \
  -Xmx512m -XX:MaxMetaspaceSize=256m -XX:+UseG1GC \
  -XX:G1PeriodicGCInterval=30000 \
  -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=25 \
  -XX:TieredStopAtLevel=1 \
  -Xverify:none -XX:+UseFastUnorderedTimeStamps \
  -Dskiko.library.path="$APPDIR/lib/app" \
  -cp "$APPDIR/lib/app/*" MainKt "$@"
LAUNCHER
chmod +x "$appdir/bin/YPtun"

echo "=== Building ARM64 AppImage ==="
rm -f /mnt/d/yptun/release-4.0.0/YPtun-4.0.0-arm64.AppImage
ARCH=aarch64 APPIMAGE_EXTRACT_AND_RUN=1 "$cache_dir/appimagetool.AppImage" "$appdir" /mnt/d/yptun/release-4.0.0/YPtun-4.0.0-arm64.AppImage

echo "=== Building ARM64 DEB ==="
mkdir -p "$debdir/DEBIAN"
mkdir -p "$debdir/opt/yptun"
cp -a "$appdir/lib" "$debdir/opt/yptun/"
cp -a "$appdir/bin" "$debdir/opt/yptun/"
mkdir -p "$debdir/usr/share/applications" "$debdir/usr/share/pixmaps" "$debdir/usr/bin"
ln -sf /opt/yptun/bin/YPtun "$debdir/usr/bin/yptun"
cp "$appdir/olcbox.png" "$debdir/usr/share/pixmaps/yptun.png"
cp "$appdir/org.olcbox.app.desktopApp.desktop" "$debdir/usr/share/applications/yptun.desktop"

cat > "$debdir/DEBIAN/control" << 'CONTROL'
Package: yptun
Version: 4.0.0-1
Section: net
Maintainer: YPtun <yptun@users.noreply.github.com>
Priority: optional
Architecture: arm64
Provides: yptun
Description: YPtun Compose Linux client
Depends: libasound2t64, libc6, libexpat1, libfontconfig1, libfreetype6, libgl1, libx11-6, libxext6, libxi6, libxrender1, libxtst6, xdg-utils, zlib1g
CONTROL

rm -f /mnt/d/yptun/release-4.0.0/YPtun-4.0.0-arm64.deb
dpkg-deb --build --root-owner-group "$debdir" /mnt/d/yptun/release-4.0.0/YPtun-4.0.0-arm64.deb

echo "=== Clean up temp dirs ==="
rm -rf "$work_dir"

echo "=== Done! ==="
