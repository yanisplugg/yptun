#!/usr/bin/env bash
set -euo pipefail

cache_dir="/mnt/d/yptun/.arm64-cache"
mkdir -p "$cache_dir"
if [[ ! -f "$cache_dir/appimagetool.AppImage" ]]; then
    curl -fsSL -o "$cache_dir/appimagetool.AppImage" "https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage"
    chmod +x "$cache_dir/appimagetool.AppImage"
fi
if [[ ! -f "$cache_dir/jre-amd64.tar.gz" ]]; then
    echo "Downloading Temurin AMD64 JRE..."
    curl -fsSL -o "$cache_dir/jre-amd64.tar.gz" "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.6%2B7/OpenJDK21U-jre_x64_linux_hotspot_21.0.6_7.tar.gz"
fi
if [[ ! -f "$cache_dir/runtime-x86_64" ]]; then
    echo "Downloading AppImage x86_64 runtime..."
    curl -fsSL -o "$cache_dir/runtime-x86_64" "https://github.com/AppImage/type2-runtime/releases/download/continuous/runtime-x86_64"
fi

work_dir="/tmp/work-amd64"
appdir="$work_dir/AppDir-amd64"
debdir="$work_dir/deb-amd64"
out="/mnt/d/yptun/release-4.0.0"

rm -rf "$work_dir"
mkdir -p "$work_dir"

echo "=== Preparing AMD64 AppDir ==="
mkdir -p "$appdir"
source_dist="/mnt/d/yptun/YPtun/desktopApp/build/compose/binaries/main-release/app/YPtun"
if [[ -d "$source_dist" ]]; then
    cp -a "$source_dist/." "$appdir/"
else
    cp -a /mnt/d/yptun/YPtun/desktopApp/build/compose/binaries/main-release/appimage/AppDir/. "$appdir/"
fi

# Icon and Desktop file
cp /mnt/d/yptun/YPtun/desktopApp/appIcons/LinuxIcon.png "$appdir/olcbox.png"
ln -sf olcbox.png "$appdir/.DirIcon"

cat > "$appdir/org.olcbox.app.desktopApp.desktop" << 'DESKTOP'
[Desktop Entry]
Type=Application
Name=YPtun
Comment=Fast, versatile VPN client to bypass censorship
Exec=YPtun %u
Icon=olcbox
Categories=Network;Utility;
Terminal=false
MimeType=x-scheme-handler/yptun;x-scheme-handler/vless;x-scheme-handler/vmess;x-scheme-handler/ss;x-scheme-handler/trojan;x-scheme-handler/hysteria2;x-scheme-handler/tuic;
DESKTOP

echo "=== Extracting Temurin AMD64 JRE ==="
rm -rf "$appdir/lib/runtime"
mkdir -p "$appdir/lib/runtime"
tar -xzf "$cache_dir/jre-amd64.tar.gz" -C "$appdir/lib/runtime" --strip-components=1

echo "=== Stripping foreign binaries from desktopApp jar for AMD64 ==="
python3 -c "
import zipfile, os, glob, shutil

jars = glob.glob('$appdir/lib/app/desktopApp-*.jar')
for jar_path in jars:
    tmp_path = jar_path + '.stripped'
    with zipfile.ZipFile(jar_path, 'r') as zin, zipfile.ZipFile(tmp_path, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
        for item in zin.infolist():
            if item.filename.startswith('native/'):
                if not ('-linux-amd64' in item.filename or item.filename.startswith('native/olcrtc-data/')):
                    continue
            zout.writestr(item, zin.read(item.filename))
    shutil.move(tmp_path, jar_path)
"

echo "=== Writing AMD64 launcher ==="
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
  -Dskiko.library.path="$APPDIR/lib/app" \
  -cp "$APPDIR/lib/app/*" MainKt "$@"
LAUNCHER
chmod +x "$appdir/bin/YPtun"

ln -sf ../lib/runtime/bin/java "$appdir/bin/java"

echo "=== Writing AppRun ==="
cat > "$appdir/AppRun" << 'APPRUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
export JAVA_HOME="$HERE/lib/runtime"
export PATH="$HERE/bin:$HERE/lib/runtime/bin:$PATH"
exec "$HERE/bin/YPtun" "$@"
APPRUN
chmod +x "$appdir/AppRun"

echo "=== Building Slimmed Linux amd64 AppImage ==="
rm -f "$out/YPtun-4.0.0-amd64.AppImage"
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$cache_dir/appimagetool.AppImage" --runtime-file "$cache_dir/runtime-x86_64" "$appdir" "$out/YPtun-4.0.0-amd64.AppImage"

echo "=== Building Slimmed Linux amd64 DEB ==="
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

echo "=== Clean up temp dirs ==="
rm -rf "$work_dir"

echo "=== Done amd64 ==="
