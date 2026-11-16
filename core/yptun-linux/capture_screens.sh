#!/bin/bash
set -e

export DISPLAY=127.0.0.1:96.0
export LIBGL_ALWAYS_SOFTWARE=1
export WINIT_UNIX_BACKEND=x11
unset WAYLAND_DISPLAY

pkill -f 'Xvfb :96' || true
sleep 0.5
Xvfb :96 -screen 0 1100x720x24 -listen tcp -ac &
XVFB_PID=$!
sleep 1.5

BIN=/mnt/d/yptun/core/target/release/yptun-linux
OUT=/mnt/c/Users/Stanislav/.gemini/antigravity/brain/dfa1b178-3450-416d-8350-875792a86ae5

capture() {
    local page="$1"
    local outname="$2"
    echo "Capturing $page -> $outname..."
    $BIN --page "$page" &
    local APP_PID=$!
    sleep 2.0
    import -display 127.0.0.1:96.0 -window root png32:"$OUT/$outname"
    kill $APP_PID 2>/dev/null || true
    wait $APP_PID 2>/dev/null || true
    sleep 0.3
}

capture home rust_ui_home.png
capture settings rust_ui_settings.png
capture connection rust_ui_connection.png
capture routing rust_ui_routing.png
capture traffic rust_ui_traffic.png
capture appearance rust_ui_appearance.png
capture language rust_ui_language.png
capture ping rust_ui_ping.png
capture privacy rust_ui_privacy.png
capture advanced rust_ui_advanced.png
capture logs rust_ui_logs.png
capture about rust_ui_about.png

kill $XVFB_PID 2>/dev/null || true
echo "All captured successfully!"
