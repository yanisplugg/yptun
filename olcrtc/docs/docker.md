<div align="center">

<img src="https://github.com/openlibrecommunity/material/blob/master/olcrtc.png" width="250" height="250">

![License](https://img.shields.io/badge/license-WTFPL-0D1117?style=flat-square&logo=open-source-initiative&logoColor=green&labelColor=0D1117)
![Golang](https://img.shields.io/badge/-Golang-0D1117?style=flat-square&logo=go&logoColor=00A7D0)

</div>


# Локальная настройка Docker

> **Важно:** Обязательно проверяйте, есть ли сервис видеозвонков у вас в белых списках. Если его там нет - используйте другой. Список всех сервисов в белых списках скоро будет опубликован.

> **Jitsi-провайдер:** если используете `jitsi`, выбирайте сервер в зависимости от того, что доступно в вашей сети:
> - `https://meet.small-dm.ru/`
> - `https://meet1.arbitr.ru/`
> - `https://meet.handyweb.org/`
>
> Откройте в браузере и используйте тот, который работает.


Здесь описан один из способов запуска сервера olcrtc с локальной конфигурацией Docker.

## Идея

- держать изменяемые Docker-файлы в скрытой папке `.local`
- хранить конфигурационные файлы вне Git, в папке `.local`
- позволять пользователям обновлять репозиторий обычным `git pull`

---

## Шаг 1: Клонирование репозитория

```bash
git clone https://github.com/openlibrecommunity/olcrtc.git
cd olcrtc
```

---

## Шаг 2: Обновление до последней версии

Чтобы получить новую версию из upstream:

```bash
git pull https://github.com/openlibrecommunity/olcrtc.git --recurse-submodules
```

---

## Шаг 3: Папка для локальных конфигураций

Создайте директорию `.local` в корне репозитория:

```bash
mkdir -p .local
```

Эта папка должна содержать файлы, которые будут использоваться только на вашем сервере.

---

## Шаг 4: Скопируйте docker-compose.yml в `.local`

Скопируйте файл `docker-compose.server.yml`, чтобы ваша локальная версия не перезаписывалась при следующем обновлении репозитория через `git pull`:

```bash
cp docker-compose.server.yml .local/docker-compose.server.yml
```

Если файл `docker-compose.server.yml` позже изменится, скопируйте его снова этой же командой после `git pull`.

---

## Шаг 5: Создайте локальный файл окружения

Создайте `.local/.env` и заполните значения в соответствии с выбранным типом подключения.

Пример можно найти в `docs/examples/server/.env.telemost.server.example`.

---

## Шаг 6: Запуск OLCRTC

Запуск контейнеризированного сервера используя `docker-compose.server.yml` и локальный `.env`:

```bash
docker compose -f .local/docker-compose.server.yml --env-file .local/.env up -d
```

Проверка состояния контейнера:

```bash
docker compose -f .local/docker-compose.server.yml --env-file .local/.env ps
```

Просмотр логов контейнера:

```bash
docker compose -f .local/docker-compose.server.yml --env-file .local/.env logs -f
docker logs olcrtc-server
```

---

## Шаг 7: Обновление контейнера

Получите новую версию репозитория:

```bash
git pull https://github.com/openlibrecommunity/olcrtc.git
```

После каждого обновления сравните новый и старый файл:

```bash
diff -wy .local/docker-compose.server.yml docker-compose.server.yml
```

Если есть отличия, скопируйте файл из корня в папку `.local`:

```bash
cp docker-compose.server.yml .local/docker-compose.server.yml
```

Затем перезапустите контейнер:

```bash
docker compose -f .local/docker-compose.server.yml down
docker compose -f .local/docker-compose.server.yml --env-file .local/.env up -d
```

---

## Примечания

- Храните все локальные Docker-файлы внутри отдельной папки `.local`.
- Не добавляйте `.local` в репозиторий (она должна быть в `.gitignore`).
- Держите общую документацию в `docs/`, а специфичные настройки в `.local`.

---

Используешь скрипты вместо Docker? -> [Быстрый старт](fast.md)

Хочешь собрать руками без контейнеров? -> [Мануальная сборка](manual.md)

Все настройки и матрица совместимости -> [settings.md](settings.md)
