# Развёртывание сервера

Для работы сервера 24/7 его необходимо настроить как службу. Мы поддерживаем установку через скрипт (интерактивно или автоматически), а также ручную настройку через **Docker** или **systemd**.

---

## Автоматическая установка (Рекомендуется)

Проще всего использовать официальный скрипт. Он установит зависимости, сгенерирует ключи, настроит файрвол и запустит службу.

**Интерактивный режим** (задаст вопросы в терминале):
```bash
curl -fsSL https://raw.githubusercontent.com/samosvalishe/free-turn-proxy/master/scripts/install.sh | sudo bash
```
> Скрипт идемпотентен: при повторном запуске он предложит обновить, переконфигурировать или удалить сервер.

**Неинтерактивный режим** (для автоматизации):
```bash
# Установка через Docker (UDP, порт бэкенда 51820)
curl -fsSL https://raw.githubusercontent.com/samosvalishe/free-turn-proxy/master/scripts/install.sh | \
  sudo bash -s -- -y --method docker --mode udp --backend-port 51820

# Обновление до конкретной версии
sudo bash install.sh -y --update --version v1.2.3

# Полное удаление
sudo bash install.sh -y --uninstall --purge
```
*Все доступные флаги:* `sudo bash install.sh --help`

---

## Ручная установка

Если вы предпочитаете контролировать каждый шаг, используйте инструкции ниже.
Сначала сгенерируйте 64-символьный hex-ключ обфускации (он должен совпадать на сервере и клиенте):
```bash
openssl rand -hex 32
```
*В примерах ниже он обозначен как `<ВАШ_КЛЮЧ>`.*

### Способ 1: Docker Compose

1. Установите Docker:
   ```bash
   curl -fsSL https://get.docker.com | sudo sh
   ```
2. Создайте директорию и `docker-compose.yml`:
   ```bash
   mkdir -p /opt/free-turn-proxy && cd /opt/free-turn-proxy
   nano docker-compose.yml
   ```
3. Вставьте конфигурацию:
   ```yaml
   services:
     free-turn-proxy:
       image: ghcr.io/samosvalishe/free-turn-proxy:latest
       container_name: free-turn-proxy
       network_mode: "host" # Важно для доступа к локальному VPN (127.0.0.1)
       restart: unless-stopped
       environment:
         - CONNECT_ADDR=127.0.0.1:51820  # Порт ВАШЕГО VPN (WG/AmneziaWG/Xray)
         - LISTEN_ADDR=0.0.0.0:56000     # Внешний порт
         - MODE=udp                      # udp (WG/Amnezia) или tcp (Xray/VLESS)
         - OBF_PROFILE=rtpopus           # Обязательная маскировка
         - OBF_KEY=<ВАШ_КЛЮЧ>            # Ваш ключ
         # - CLIENTS_FILE=/opt/free-turn-proxy/clients.json # Для авторизации
       # volumes:
       #   - /opt/free-turn-proxy/clients.json:/opt/free-turn-proxy/clients.json
   ```
4. Запустите: `docker compose up -d`

### Способ 2: systemd (Без Docker)

1. Скачайте бинарник:
   ```bash
   sudo mkdir -p /opt/free-turn-proxy
   sudo curl -L -o /opt/free-turn-proxy/server https://github.com/samosvalishe/free-turn-proxy/releases/latest/download/server-linux-amd64
   sudo chmod +x /opt/free-turn-proxy/server
   ```
   *(Для ARM замените `-amd64` на `-arm64`)*
2. Создайте службу: `sudo nano /etc/systemd/system/free-turn-proxy.service`
3. Вставьте конфигурацию:
   ```ini
   [Unit]
   Description=Free TURN Proxy Server
   After=network.target

   [Service]
   Type=simple
   ExecStart=/opt/free-turn-proxy/server -listen 0.0.0.0:56000 -connect 127.0.0.1:51820 -obf-profile rtpopus -obf-key <ВАШ_КЛЮЧ>
   Restart=always
   RestartSec=5
   User=nobody
   Group=nogroup

   [Install]
   WantedBy=multi-user.target
   ```
4. Запустите: `sudo systemctl daemon-reload && sudo systemctl enable --now free-turn-proxy.service`

---

## Настройка Файрвола

Откройте внешний порт (сервер слушает по **UDP**, даже если `MODE=tcp`):
```bash
sudo ufw allow 56000/udp
# Или iptables: sudo iptables -I INPUT -p udp --dport 56000 -j ACCEPT
```

---

## Авторизация по Client ID (Опционально)

По умолчанию доступ открыт всем, кто знает `-obf-key`. Чтобы ограничить доступ:

1. Создайте пустой список: `echo "[]" | sudo tee /opt/free-turn-proxy/clients.json`
2. Включите авторизацию:
   - **Docker:** раскомментируйте `CLIENTS_FILE` и `volumes` в `docker-compose.yml`, затем `docker compose up -d`.
   - **systemd:** добавьте флаг `-clients-file /opt/free-turn-proxy/clients.json` в `ExecStart` и перезапустите службу.
3. Добавляйте ID в `clients.json` (см. [flags.md](flags.md#управление-client-id-команды-сервера)). На клиенте используйте флаг `-client-id <id>`.

---

## Переменные окружения Docker

| Переменная | По умолчанию | Описание |
| --- | --- | --- |
| `CONNECT_ADDR` | **обязательна** | IP и порт вашего VPN (бэкенда) |
| `LISTEN_ADDR` | `0.0.0.0:56000` | Внешний адрес прослушивания |
| `MODE` | `udp` | Режим туннеля: `udp` \| `tcp` |
| `OBF_PROFILE` | `none` | Маскировка: `none` \| `rtpopus` |
| `OBF_KEY` | пусто | Ключ маскировки |
| `CLIENTS_FILE`| пусто | Путь к JSON-файлу авторизации |
| `DEBUG` | `false` | Включить debug-логи |

> **Внимание при Bridge Mode:** Если вы уберете `network_mode: "host"` и пробросите порты (`-p 56000:56000/udp`), `CONNECT_ADDR=127.0.0.1:51820` будет указывать внутрь контейнера. В этом случае прокси не найдет VPN. Используйте IP хоста или оставляйте `network_mode: "host"`.
