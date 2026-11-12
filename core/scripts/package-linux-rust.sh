#!/usr/bin/env bash
set -euo pipefail

version="${1:-3.7.3}"
root="$(cd "$(dirname "$0")/.." && pwd)"
out="${OUT_DIR:-${root}/../YPtun/linux-rust-release}"
rm -rf "$out"
mkdir -p "$out"

appimage_tool="${APPIMAGETOOL:-/tmp/appimagetool.AppImage}"
if [[ ! -x "$appimage_tool" ]]; then
  curl -fsSL -o "$appimage_tool" "https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage"
  chmod +x "$appimage_tool"
fi

build_one() {
  local arch="$1" binary="$2" deb_arch="$3"
  local stage="$out/stage-$arch" appdir="$out/appdir-$arch"
  [[ -x "$binary" ]] || { echo "missing $binary" >&2; exit 1; }

  mkdir -p "$stage/DEBIAN" "$stage/usr/bin"
  chmod 755 "$stage" "$stage/DEBIAN" "$stage/usr" "$stage/usr/bin"
  install -m 0755 "$binary" "$stage/usr/bin/yptun-linux"
  cat > "$stage/DEBIAN/control" <<EOF
Package: yptun
Version: $version
Section: net
Priority: optional
Architecture: $deb_arch
Maintainer: YPtun
Description: Native Rust YPtun Linux client
EOF
  dpkg-deb --build --root-owner-group "$stage" "$out/YPtun-${version}-${arch}.deb" >/dev/null

  mkdir -p "$appdir/usr/bin"
  install -m 0755 "$binary" "$appdir/usr/bin/yptun-linux"
  cat > "$appdir/AppRun" <<'EOF'
#!/bin/sh
exec "$(dirname "$0")/usr/bin/yptun-linux" "$@"
EOF
  chmod +x "$appdir/AppRun"
  cat > "$appdir/yptun.desktop" <<EOF
[Desktop Entry]
Name=YPtun
Exec=yptun-linux
Icon=yptun
Type=Application
Categories=Network;
EOF
  cp "$root/../YPtun/desktopApp/appIcons/LinuxIcon.png" "$appdir/yptun.png"
  ARCH="$arch" APPIMAGE_EXTRACT_AND_RUN=1 "$appimage_tool" "$appdir" "$out/YPtun-${version}-${arch}.AppImage" >/dev/null
  chmod +x "$out/YPtun-${version}-${arch}.AppImage"
}

build_one amd64 "$root/target/release/yptun-linux" amd64
build_one arm64 "$root/target/aarch64-unknown-linux-gnu/release/yptun-linux" arm64

rm -rf "$out"/stage-* "$out"/appdir-*
find "$out" -maxdepth 1 -type f -printf '%f\n' | sort
[[ "$(find "$out" -maxdepth 1 -type f | wc -l)" -eq 4 ]]
