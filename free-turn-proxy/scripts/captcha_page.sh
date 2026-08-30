#!/usr/bin/env bash
# Снимает живую страницу VK captcha: анонимный токен -> вызов, который отдаёт
# error 14 -> redirect_uri -> HTML. Нужен, чтобы диффать разметку после
# очередной смены формата PoW.
#
# usage: scripts/captcha_page.sh [out.html]
set -euo pipefail

OUT="${1:-captcha_page.html}"
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
CLIENT_ID="6287487"
CLIENT_SECRET="QbYic1K3lEV5kTGiqlq2"

req() { curl -sf --compressed -A "$UA" -H "Origin: https://vk.ru" -H "Referer: https://vk.ru/" "$@"; }

token=$(req "https://login.vk.ru/?act=get_anonym_token" \
  -d "client_secret=$CLIENT_SECRET&client_id=$CLIENT_ID&scopes=audio_anonymous&isApiOauthAnonymEnabled=false&version=1&app_id=$CLIENT_ID" |
  sed -E 's/.*"access_token":"([^"]+)".*/\1/')

# Ссылка заведомо несуществующая: captcha прилетает раньше её проверки.
uri=$(req "https://api.vk.ru/method/calls.getAnonymousToken?v=5.282&client_id=$CLIENT_ID" \
  -d "vk_join_link=https://vk.ru/call/join/aaaaaaaaaaaa&name=Test&access_token=$token" |
  sed -E 's/.*"redirect_uri":"([^"]+)".*/\1/; s/\\u0026/\&/g')

case "$uri" in
https://*not_robot_captcha*) ;;
*)
  echo "captcha не показана, повтори позже: $uri" >&2
  exit 1
  ;;
esac

curl -sf --compressed -A "$UA" -H "Referer: https://vk.com/" \
  -H "Sec-Fetch-Dest: document" -H "Sec-Fetch-Mode: navigate" \
  -H "Sec-Fetch-Site: cross-site" -H "Upgrade-Insecure-Requests: 1" \
  "$uri" >"$OUT"

echo "$OUT ($(wc -c <"$OUT") bytes)"
