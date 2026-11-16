#!/usr/bin/env bash
set -euo pipefail

cache_dir="/mnt/d/yptun/.arm64-cache"
if [[ ! -f "$cache_dir/appimagetool.AppImage" ]]; then
    mkdir -p "$cache_dir"
    curl -fsSL -o "$cache_dir/appimagetool.AppImage" "https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage"
    chmod +x "$cache_dir/appimagetool.AppImage"
fi

apptool="$cache_dir/appimagetool.AppImage"
out="/mnt/d/yptun/release-4.0.0"
appdir="/mnt/d/yptun/YPtun/desktopApp/build/compose/binaries/main-release/appimage/AppDir"

echo "=== Building Slimmed Linux amd64 AppImage ==="
rm -f "$out/YPtun-4.0.0-amd64.AppImage"
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$apptool" "$appdir" "$out/YPtun-4.0.0-amd64.AppImage"

echo "=== Building Slimmed Linux amd64 DEB ==="
debdir="/tmp/deb-amd64"
rm -rf "$debdir"
mkdir -p "$debdir/DEBIAN" "$debdir/opt/yptun" "$debdir/usr/bin" "$debdir/usr/share/applications" "$debdir/usr/share/pixmaps"
cp -a "$appdir/lib" "$debdir/opt/yptun/"
cp -a "$appdir/bin" "$debdir/opt/yptun/"
ln -sf /opt/yptun/bin/YPtun "$debdir/usr/bin/yptun"
cp "$appdir/olcbox.png" "$debdir/usr/share/pixmaps/yptun.png"
cp "$appdir/org.olcbox.app.desktopApp.desktop" "$debdir/usr/share/applications/yptun.desktop"

cat > "$debdir/DEBIAN/control" << 'CONTROL'
Package: yptun
Version: 4.0.0-1
Section: net
Maintainer: YPtun <yptun@users.noreply.github.com>
Priority: optional
Architecture: amd64
Provides: yptun
Description: YPtun Compose Linux client
Depends: libasound2t64, libc6, libexpat1, libfontconfig1, libfreetype6, libgl1, libx11-6, libxext6, libxi6, libxrender1, libxtst6, xdg-utils, zlib1g
CONTROL

rm -f "$out/YPtun-4.0.0-amd64.deb"
dpkg-deb -Zxz --build --root-owner-group "$debdir" "$out/YPtun-4.0.0-amd64.deb"
rm -rf "$debdir"

echo "=== Done amd64 ==="
