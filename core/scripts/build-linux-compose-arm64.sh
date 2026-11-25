#!/usr/bin/env bash
set -euo pipefail

cache_dir="/mnt/d/yptun/.arm64-cache"
mkdir -p "$cache_dir"
if [[ ! -f "$cache_dir/appimagetool.AppImage" ]]; then
    curl -fsSL -o "$cache_dir/appimagetool.AppImage" "https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage"
    chmod +x "$cache_dir/appimagetool.AppImage"
fi
if [[ ! -f "$cache_dir/jre-arm64.tar.gz" ]]; then
    echo "Downloading Temurin ARM64 JRE..."
    curl -fsSL -o "$cache_dir/jre-arm64.tar.gz" "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.6%2B7/OpenJDK21U-jre_aarch64_linux_hotspot_21.0.6_7.tar.gz"
fi
if [[ ! -f "$cache_dir/skiko-arm64.jar" ]]; then
    echo "Downloading Skiko ARM64..."
    curl -fsSL -o "$cache_dir/skiko-arm64.jar" "https://repo1.maven.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-linux-arm64/0.148.1/skiko-awt-runtime-linux-arm64-0.148.1.jar"
fi
if [[ ! -f "$cache_dir/runtime-aarch64" ]]; then
    echo "Downloading AppImage aarch64 runtime..."
    curl -fsSL -o "$cache_dir/runtime-aarch64" "https://github.com/AppImage/type2-runtime/releases/download/continuous/runtime-aarch64"
fi

work_dir="/tmp/work-arm64"
appdir="$work_dir/AppDir-arm64"
debdir="$work_dir/deb-arm64"
out="/mnt/d/yptun/release-4.0.1"
mkdir -p "$out"

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

echo "=== Stripping foreign binaries from desktopApp jar for ARM64 ==="
python3 -c "
import zipfile, os, glob, shutil

jars = glob.glob('$appdir/lib/app/desktopApp-*.jar')
for jar_path in jars:
    tmp_path = jar_path + '.stripped'
    with zipfile.ZipFile(jar_path, 'r') as zin, zipfile.ZipFile(tmp_path, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zout:
        for item in zin.infolist():
            if item.filename.startswith('native/'):
                if not ('-linux-arm64' in item.filename or item.filename.startswith('native/olcrtc-data/')):
                    continue
            zout.writestr(item, zin.read(item.filename))
    shutil.move(tmp_path, jar_path)
"

echo "=== Updating YPtun.cfg ==="
sed -i "s/skiko-awt-runtime-linux-x64-[^.]*\.jar/skiko-awt-runtime-linux-arm64-0.148.1.jar/g" "$appdir/lib/app/YPtun.cfg"

echo "=== Writing ARM64 launcher ==="
cat > "$appdir/bin/YPtun" << 'LAUNCHER'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
APPDIR="$(dirname "$HERE")"
exec "$APPDIR/lib/runtime/bin/java" \
  -Djpackage.app-version=4.0.1 \
  --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
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

echo "=== Building Slimmed ARM64 AppImage ==="
rm -f "$out/YPtun-4.0.1-arm64.AppImage"
ARCH=aarch64 APPIMAGE_EXTRACT_AND_RUN=1 "$cache_dir/appimagetool.AppImage" --runtime-file "$cache_dir/runtime-aarch64" "$appdir" "$out/YPtun-4.0.1-arm64.AppImage"

echo "=== Building Slimmed ARM64 DEB ==="
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
Version: 4.0.1-1
Section: net
Maintainer: YPtun <yptun@users.noreply.github.com>
Priority: optional
Architecture: arm64
Provides: yptun
Description: YPtun Compose Linux client
Depends: libasound2t64, libc6, libexpat1, libfontconfig1, libfreetype6, libgl1, libx11-6, libxext6, libxi6, libxrender1, libxtst6, xdg-utils, zlib1g
CONTROL

rm -f "$out/YPtun-4.0.1-arm64.deb"
dpkg-deb -Zxz --build --root-owner-group "$debdir" "$out/YPtun-4.0.1-arm64.deb"

echo "=== Clean up temp dirs ==="
rm -rf "$work_dir"

echo "=== Done! ==="
