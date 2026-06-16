<div align="center">

<img src="https://github.com/openlibrecommunity/material/blob/master/olcrtc.png" width="250" height="250">

![License](https://img.shields.io/badge/license-WTFPL-0D1117?style=flat-square&logo=open-source-initiative&logoColor=green&labelColor=0D1117)
![Golang](https://img.shields.io/badge/-Golang-0D1117?style=flat-square&logo=go&logoColor=00A7D0)

</div>

# Быстрый старт

> **Важно:** Обязательно проверяйте, есть ли сервис видеозвонков у вас в белых списках. Если его там нет - используйте другой.

Этот способ запускает `olcrtc` как обычный нативный бинарник. Нужны Go 1.26+, mage, git и curl.

## Установить зависимости

```sh
apt install git curl        # Debian / Ubuntu / Mint
pacman -S git curl          # Arch / CachyOS / Manjaro
dnf install git curl        # Fedora / RHEL / CentOS
```

Установи Go 1.26+ и mage:

```sh
go install github.com/magefile/mage@latest
echo 'export PATH="$HOME/go/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
```

Если на машине меньше 4 ГБ RAM, включи swap перед сборкой:

```sh
sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
```

## Собрать

```sh
git clone https://github.com/openlibrecommunity/olcrtc --recurse-submodules
cd olcrtc
mage build
```

Бинарник появится в `build/`, например:

```sh
./build/olcrtc-linux-amd64
```

## Сгенерировать ключ

Ключ должен совпадать на сервере и клиенте.

```sh
openssl rand -hex 32
```

## Запустить сервер

Создай `server.yaml`:

```yaml
mode: srv
auth:
  provider: jitsi
room:
  id: "https://meet.small-dm.ru/myroom"
crypto:
  key: "REPLACE_ME_WITH_64_HEX_CHARS"
net:
  transport: datachannel
  dns: "8.8.8.8:53"
data: data
```

Запусти:

```sh
./build/olcrtc-linux-amd64 server.yaml
```

## Запустить клиент

Создай `client.yaml` на клиентской машине. `auth.provider`, `room.id`, `crypto.key` и `net.transport` должны совпадать с сервером.

```yaml
mode: cnc
auth:
  provider: jitsi
room:
  id: "https://meet.small-dm.ru/myroom"
crypto:
  key: "REPLACE_ME_WITH_64_HEX_CHARS"
net:
  transport: datachannel
  dns: "8.8.8.8:53"
socks:
  host: "127.0.0.1"
  port: 8808
data: data
```

Запусти:

```sh
./build/olcrtc-linux-amd64 client.yaml
```

После запуска SOCKS5 будет слушать на `127.0.0.1:8808`.

## Проверить

```sh
curl --socks5-hostname 127.0.0.1:8808 https://icanhazip.com
```

Должен вернуться IP сервера.

## Управление

Остановка ручного запуска - `Ctrl+C`.

Если процесс запущен в фоне:

```sh
pgrep -af olcrtc
kill <pid>
```

Обновление:

```sh
git pull --recurse-submodules
mage build
```

Перезапусти сервер и клиент с теми же YAML-конфигами.

## Несколько инстансов

Можно запустить несколько серверов или клиентов на одной машине: создай отдельный YAML для каждого инстанса. Для клиентов используй разные SOCKS5-порты:

```yaml
socks:
  host: "127.0.0.1"
  port: 8809
```

Все настройки и матрица совместимости: [settings.md](settings.md). Подробная ручная сборка: [manual.md](manual.md).
