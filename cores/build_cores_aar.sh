#!/usr/bin/env bash
set -e

export ANDROID_HOME="/mnt/c/Users/Stanislav/AppData/Local/Android/sdk"
export ANDROID_NDK_HOME="/mnt/c/Users/Stanislav/AppData/Local/Android/sdk/ndk/28.2.13676358"
export PATH="$PATH:/home/ard/go/bin"

cd /mnt/d/yptun/cores

TAGS="with_gvisor,with_dhcp,with_wireguard,with_utls,with_clash_api,with_quic,with_naive_outbound,with_openvpn"
VERSION="1.14.4"
LDFLAGS="-X github.com/sagernet/sing-box/constant.Version=${VERSION} -s -w -checklinkname=0"
OUTPUT="/mnt/d/yptun/YPtun/sharedUI/build/generated/cores/cores.aar"

gomobile bind \
    -target=android/arm,android/arm64,android/amd64 \
    -androidapi 21 \
    -tags "${TAGS}" \
    -trimpath \
    -ldflags "${LDFLAGS}" \
    -o "${OUTPUT}" \
    github.com/openlibrecommunity/olcrtc/mobile \
    github.com/sagernet/sing-box/experimental/libbox \
    github.com/samosvalishe/free-turn-proxy/freeturn \
    wg-turn-client/wdttmobile \
    masterdnsvpn-go/mdnsmobile \
    github.com/olc/awgproxy/awg \
    kazcores/xraybridge

echo "BUILD SUCCESS: ${OUTPUT}"
