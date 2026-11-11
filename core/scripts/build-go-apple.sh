#!/usr/bin/env bash
# Builds the in-process Go network cores as one Apple XCFramework and verifies
# that every requested platform slice was actually produced.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CORES="$ROOT/cores"
OUT="$ROOT/YPtun/sharedUI/build/generated/cores/ios/Coreapi.xcframework"
TARGETS="${GOMOBILE_APPLE_TARGETS:-ios,iossimulator,macos}"
IOS_VERSION="${IPHONEOS_DEPLOYMENT_TARGET:-17.0}"
MACOS_VERSION="${MACOSX_DEPLOYMENT_TARGET:-14.0}"

cd "$CORES"
go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
GOMOBILE="$(go env GOPATH)/bin/gomobile"
"$GOMOBILE" init

VERSION="$(grep -E '^####[[:space:]]+[0-9]+\.[0-9]+\.[0-9]+' "$ROOT/sing-box/docs/changelog.md" | head -1 | awk '{print $2}')"
mkdir -p "$(dirname "$OUT")"
rm -rf "$OUT"

"$GOMOBILE" bind \
  -target="$TARGETS" \
  -iosversion "$IOS_VERSION" \
  -macosversion "$MACOS_VERSION" \
  -tags with_gvisor,with_dhcp,with_wireguard,with_utls,with_clash_api,with_quic \
  -trimpath \
  -ldflags "-X github.com/sagernet/sing-box/constant.Version=$VERSION -s -w -checklinkname=0" \
  -o "$OUT" \
  ./coreapi

python3 - "$OUT/Info.plist" "$TARGETS" <<'PY'
import plistlib
import sys

info_path, requested = sys.argv[1:]
with open(info_path, "rb") as stream:
    libraries = plistlib.load(stream)["AvailableLibraries"]

actual = {
    (library["SupportedPlatform"], library.get("SupportedPlatformVariant"))
    for library in libraries
}
mapping = {
    "ios": ("ios", None),
    "iossimulator": ("ios", "simulator"),
    "macos": ("macos", None),
    "maccatalyst": ("ios", "maccatalyst"),
}
requested_platforms = {item.split("/", 1)[0] for item in requested.split(",")}
unknown = requested_platforms - mapping.keys()
if unknown:
    raise SystemExit(f"Cannot validate unknown gomobile Apple targets: {sorted(unknown)}")

required = {mapping[platform] for platform in requested_platforms}
missing = required - actual
if missing:
    raise SystemExit(
        f"Coreapi.xcframework is missing requested slices {sorted(missing, key=str)}; "
        f"available slices: {sorted(actual, key=str)}"
    )

print(f"Verified Coreapi.xcframework slices: {sorted(actual, key=str)}")
PY
