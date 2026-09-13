#!/bin/bash
set -e
SRC="../androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher.png"
DEST="iosApp/Assets.xcassets/AppIcon.appiconset"
mkdir -p "$DEST"

SRC_BASE="$DEST/icon_ios-marketing_1024x1024_1x.png"
if [ ! -f "$SRC_BASE" ]; then
  SRC_BASE="$SRC"
fi

sips -z 40 40 "$SRC_BASE" --out "$DEST/icon_iphone_20x20_2x.png"
sips -z 60 60 "$SRC_BASE" --out "$DEST/icon_iphone_20x20_3x.png"
sips -z 58 58 "$SRC_BASE" --out "$DEST/icon_iphone_29x29_2x.png"
sips -z 87 87 "$SRC_BASE" --out "$DEST/icon_iphone_29x29_3x.png"
sips -z 80 80 "$SRC_BASE" --out "$DEST/icon_iphone_40x40_2x.png"
sips -z 120 120 "$SRC_BASE" --out "$DEST/icon_iphone_40x40_3x.png"
sips -z 120 120 "$SRC_BASE" --out "$DEST/icon_iphone_60x60_2x.png"
sips -z 180 180 "$SRC_BASE" --out "$DEST/icon_iphone_60x60_3x.png"
sips -z 20 20 "$SRC_BASE" --out "$DEST/icon_ipad_20x20_1x.png"
sips -z 40 40 "$SRC_BASE" --out "$DEST/icon_ipad_20x20_2x.png"
sips -z 29 29 "$SRC_BASE" --out "$DEST/icon_ipad_29x29_1x.png"
sips -z 58 58 "$SRC_BASE" --out "$DEST/icon_ipad_29x29_2x.png"
sips -z 40 40 "$SRC_BASE" --out "$DEST/icon_ipad_40x40_1x.png"
sips -z 80 80 "$SRC_BASE" --out "$DEST/icon_ipad_40x40_2x.png"
sips -z 76 76 "$SRC_BASE" --out "$DEST/icon_ipad_76x76_1x.png"
sips -z 152 152 "$SRC_BASE" --out "$DEST/icon_ipad_76x76_2x.png"
sips -z 167 167 "$SRC_BASE" --out "$DEST/icon_ipad_83.5x83.5_2x.png"
