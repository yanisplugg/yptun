# Быстрый Старт

## Требования
- **VPS с публичным IP**, на котором установлен VPN-сервер (например, WireGuard). VPN должен слушать локальный порт (например, `127.0.0.1:51820/udp`).
- **Активная ссылка VK Calls**: `https://vk.ru/call/join/...` (создайте сами, звонок не завершайте).

---

## Шаг 1: Запуск Сервера (VPS)

Интерактивный скрипт установит Docker или systemd, настроит файрвол и запустит сервер. Запускать от root:

```bash
curl -fsSL https://raw.githubusercontent.com/samosvalishe/free-turn-proxy/master/scripts/install.sh | sudo bash
```
> Скопируйте параметры клиента, которые скрипт выдаст в конце. Для ручной установки или запуска скрипта без вопросов (non-interactive) см. [Развёртывание (deploy.md)](deploy.md).

---

## Шаг 2: Запуск Клиента (ПК)

Клиент автоматически создаёт маршруты-исключения для IP TURN-серверов, чтобы VPN не перехватывал TURN-трафик. Запускайте от администратора (Windows) / sudo (Linux, macOS).

**Linux:**
```bash
curl -L -o client https://github.com/samosvalishe/free-turn-proxy/releases/latest/download/client-linux-amd64
chmod +x client
sudo ./client -listen 127.0.0.1:9000 -peer <vps_ip>:56000 -link "<vk-link>" -obf-profile rtpopus -obf-key <ВАШ_КЛЮЧ> -client-id <ВАШ_CLIENT_ID> -routes
```

**Windows (от администратора):**
```
Invoke-WebRequest -Uri https://github.com/samosvalishe/free-turn-proxy/releases/latest/download/client-windows-amd64.exe -OutFile client.exe
.\client.exe -peer <vps_ip>:56000 -provider vk -link "<vk-link>" -listen 127.0.0.1:9000 -n 12 -streams-per-cred 12 -obf-profile rtpopus3 -obf-key <ВАШ_КЛЮЧ> -dns-servers 192.168.31.1 -dns-mode doh -client-id <ВАШ_CLIENT_ID> -routes
```

**macOS:**
```bash
# Apple Silicon (M1/M2): client-darwin-arm64 | Intel: client-darwin-amd64
sudo ./client -listen 127.0.0.1:9000 -peer <vps_ip>:56000 -link "<vk-link>" -obf-profile rtpopus -obf-key <ВАШ_КЛЮЧ> -client-id <ВАШ_CLIENT_ID> -routes
```

> **Важно:** В настройках вашего VPN-клиента (например, WireGuard) укажите `Endpoint = 127.0.0.1:9000` и `MTU = 1280`. Включайте VPN *только после того*, как клиент выведет `Ensuring route to ...`.

> [!TIP]
> **Маршруты:** Для автоматического добавления маршрутов к TURN-серверам используйте флаг `-routes` (отключен по умолчанию). Если вы настраиваете сеть иначе или используете старые скрипты из `scripts/`, этот флаг не нужен.

> [!TIP]
> **Упрощение:** Вместо длинных флагов можно использовать ссылку `freeturn://` или подписку (`-sub`). Подробнее в [uri.md](uri.md) и [sub.md](sub.md).

---

## Шаг 3: Мобильные Устройства (Termux)

На мобильных сетях маршруты не нужны, но есть своя специфика (блокировка DNS, добавление в исключения VPN).

```bash
termux-wake-lock
# Скачивание: curl -L -o client https://github.com/samosvalishe/free-turn-proxy/releases/latest/download/client-android-arm64 && chmod +x client

# Обязательно укажите ваш ключ и DNS оператора (можно узнать в настройках APN)
./client -listen 127.0.0.1:9000 -peer <vps_ip>:56000 -link "<vk-link>" -obf-profile rtpopus -obf-key <ВАШ_КЛЮЧ> -dns-servers <ip_dns_оператора> -client-id <ВАШ_CLIENT_ID>
```

> Обязательно добавьте приложение Termux в исключения вашего VPN-клиента. Подробнее в [mobile.md](mobile.md).
