<div align="center">

<img src="https://github.com/openlibrecommunity/material/blob/master/olcrtc.png" width="250" height="250">

![License](https://img.shields.io/badge/license-WTFPL-0D1117?style=flat-square&logo=open-source-initiative&logoColor=green&labelColor=0D1117)
![Golang](https://img.shields.io/badge/-Golang-0D1117?style=flat-square&logo=go&logoColor=00A7D0)

**RU** / [EN](configuration.md)

</div>


# Настройка YAML

`olcrtc` читает runtime-настройки из одного YAML-файла. CLI принимает ровно один аргумент - путь к конфигу; отдельных CLI-флагов для режима, транспорта и провайдера больше нет.

```bash
olcrtc /etc/olcrtc/server.yaml
olcrtc /etc/olcrtc/client.yaml
```

Готовые примеры:

- [`server.jitsi.datachannel.yaml`](./examples/server/server.jitsi.datachannel.yaml) - jitsi + datachannel srv
- [`client.jitsi.datachannel.yaml`](./examples/client/client.jitsi.datachannel.yaml) - jitsi + datachannel cnc
- [`server.jitsi.videochannel.yaml`](./examples/server/server.jitsi.videochannel.yaml) - jitsi + videochannel srv
- [`client.jitsi.videochannel.yaml`](./examples/client/client.jitsi.videochannel.yaml) - jitsi + videochannel cnc
- [`server.jitsi.seichannel.yaml`](./examples/server/server.jitsi.seichannel.yaml) - jitsi + seichannel srv
- [`client.jitsi.seichannel.yaml`](./examples/client/client.jitsi.seichannel.yaml) - jitsi + seichannel cnc
- [`server.jitsi.vp8channel.yaml`](./examples/server/server.jitsi.vp8channel.yaml) - jitsi + vp8channel srv
- [`client.jitsi.vp8channel.yaml`](./examples/client/client.jitsi.vp8channel.yaml) - jitsi + vp8channel cnc
- [`server.telemost.datachannel.yaml`](./examples/server/server.telemost.datachannel.yaml) - telemost + datachannel srv
- [`client.telemost.datachannel.yaml`](./examples/client/client.telemost.datachannel.yaml) - telemost + datachannel cnc
- [`server.telemost.videochannel.yaml`](./examples/server/server.telemost.videochannel.yaml) - telemost + videochannel srv
- [`client.telemost.videochannel.yaml`](./examples/client/client.telemost.videochannel.yaml) - telemost + videochannel cnc
- [`server.telemost.seichannel.yaml`](./examples/server/server.telemost.seichannel.yaml) - telemost + seichannel srv
- [`client.telemost.seichannel.yaml`](./examples/client/client.telemost.seichannel.yaml) - telemost + seichannel
- [`server.telemost.vp8channel.yaml`](./examples/server/server.telemost.vp8channel.yaml) - telemost + vp8channel srv
- [`client.telemost.vp8channel.yaml`](./examples/client/client.telemost.vp8channel.yaml) - telemost + vp8channel cnc
- [`server.wbstream.datachannel.yaml`](./examples/server/server.wbstream.datachannel.yaml) - wbstream + datachannel srv
- [`client.wbstream.datachannel.yaml`](./examples/client/client.wbstream.datachannel.yaml) - wbstream + datachannel cnc
- [`server.wbstream.videochannel.yaml`](./examples/server/server.wbstream.videochannel.yaml) - wbstream + videochannel srv
- [`client.wbstream.videochannel.yaml`](./examples/client/client.wbstream.videochannel.yaml) - wbstream + videochannel cnc
- [`server.wbstream.seichannel.yaml`](./examples/server/server.wbstream.seichannel.yaml) - wbstream + seichannel srv
- [`client.wbstream.seichannel.yaml`](./examples/client/client.wbstream.seichannel.yaml) - wbstream + seichannel cnc
- [`server.wbstream.vp8channel.yaml`](./examples/server/server.wbstream.vp8channel.yaml) - wbstream + vp8channel srv
- [`client.wbstream.vp8channel.yaml`](./examples/client/client.wbstream.vp8channel.yaml) - wbstream + vp8channel cnc
- [`failover.yaml`](./examples/failover.yaml) - failover

## Схема

| YAML path | Значение |
|---|---|
| `mode` | `srv`, `cnc` или `gen` |
| `auth.provider` | `jitsi`, `telemost`, `wbstream`, `salutejazz`, `vkcalls`, `mtslink`, `none` |
| `auth.token` | необязательный заранее выданный токен аккаунта провайдера |
| `room.id` | ID/URL комнаты для выбранного провайдера |
| `room.channel` | необязательный ID канала для peer-routing сценариев |
| `crypto.key` / `crypto.key_file` | общий ключ: 64 hex-символа, напрямую или из файла |
| `crypto.keys` / `crypto.keys_file` | только сервер: список ключей по 64 hex-символа, в YAML или по одному в строке файла; ключ, которым открылась первая запись клиента, закрепляется за этим клиентом |
| `net.transport` | `datachannel`, `vp8channel`, `seichannel`, `videochannel` |
| `net.dns` | DNS resolver в формате `host:port` |
| `dtls.profile` | фиксированный профиль ClientHello в DTLS-рукопожатии движка: пусто или `off` — штатное рукопожатие; поставляемый идентификатор — `chrome-linux-138-compat-v1` (список шифров Chrome 138 без того, что не умеет Pion); неизвестное имя отвергается до подключения; профиль failover может переопределить, а явный `off` перекрывает унаследованное |
| `socks.host` / `socks.port` | локальный SOCKS5 listener в `mode: cnc` |
| `socks.user` / `socks.pass` | необязательная auth для входящих SOCKS5-подключений |
| `socks.proxy_addr` / `socks.proxy_port` | исходящий SOCKS5-прокси на серверной стороне |
| `socks.proxy_user` / `socks.proxy_pass` | необязательная auth для upstream-прокси (RFC 1929) |
| `engine.name` / `engine.url` / `engine.token` | прямой engine-режим, только при `auth.provider: none` |
| `video.*` | настройки `videochannel` |
| `vp8.*` | настройки `vp8channel` |
| `sei.*` | настройки `seichannel` |
| `liveness.interval` | интервал ping по control stream, по умолчанию `10s` |
| `liveness.timeout` | таймаут pong, по умолчанию `15s` |
| `liveness.failures` | сколько pong можно пропустить до rebuild, по умолчанию `4` |
| `lifecycle.max_session_duration` | плановый rebuild сессии, например `6h`; пусто = выключено |
| `traffic.max_payload_size` | лимит зашифрованного wire-message; `0` = лимит транспорта |
| `traffic.min_delay` / `traffic.max_delay` | необязательный pacing отправки, например `5ms` / `30ms` |
| `udp.enabled` | включает lossy-релей SOCKS5 UDP ASSOCIATE (звонки, игры); выключен, пока не `true` |
| `udp.disabled` | `true` перевешивает `enabled` |
| `udp.max_flows` | одновременных UDP-потоков на сторону, по умолчанию 1024 |
| `route.direct` | только клиент: правила для адресатов, к которым клиент подключается напрямую, минуя туннель - `domain:<имя>`, `full:<имя>`, адрес или CIDR-префикс, по одному на элемент; пусто = всё через туннель |
| `route.direct_file` | те же правила из файла, по одному на строку, комментарии через `#`; путь относительно YAML-файла, добавляется к `route.direct` |
| `gen.amount` | режим `gen`: сколько комнат создать |
| `profiles[]` | список failover-профилей для `srv`/`cnc` |
| `failover.retry_delay` | пауза перед следующим профилем, например `2s` |
| `failover.max_cycles` | сколько полных проходов по профилям сделать; `0` = бесконечно |
| `data` | опционально: каталог с файлами `names`/`surnames`, переопределяющими встроенные словари имён. Путь резолвится относительно YAML-файла |
| `debug` | подробное логирование |
| `stats.listen` | loopback-адрес, на котором `GET /stats` отдаёт байты по каждому ключу, например `127.0.0.1:9464`; пустое значение выключает |

`crypto.key_file` читается относительно YAML-файла. Нельзя одновременно задавать `crypto.key` и `crypto.key_file`.

`crypto.keys` и `crypto.keys_file` (читается относительно YAML-файла, строки с `#` и пустые пропускаются) задают серверу кольцо ключей; их нельзя сочетать с `crypto.key`, `crypto.key_file` и друг с другом, а клиент (`mode: cnc`) всегда использует один `crypto.key`:

```yaml
crypto:
  keys:
    - "0011...eeff"   # ключ комнаты
    - "aabb...8899"   # ключ отдельного пользователя
```

`mode: cnc` запрещает слушать не-loopback адрес (`0.0.0.0`, LAN IP и т.п.), если не заданы оба поля `socks.user` и `socks.pass`.

Каталог `data` должен содержать файлы `names` и `surnames`, по одному компоненту display name на строку. Если `data` не задан, используются словари, встроенные в бинарник.

## Прямые маршруты

`route.direct` и `route.direct_file` (только клиент) перечисляют адресатов, идущих мимо туннеля. CONNECT к имени или адресу из списка клиент открывает сам, через тот же защищённый сокет, что и соединение с провайдером - на телефоне он уходит через физический интерфейс. CONNECT к адресу, которого в правилах нет, сначала получает ответ, а первые байты клиента читаются в поисках TLS server name или HTTP `Host`: найденное там имя из списка набирается напрямую, по имени; всё остальное уходит в туннель, а прочитанные байты повторяются в него. При `udp.enabled` датаграммы к адресату из списка клиент тоже релеит сам, а на DNS-запрос (порт 53) к имени из списка отвечает собственный резолвер клиента (`net.dns` и серверы сети), а не выход туннеля. `domain:` совпадает с именем и всем под ним, `full:` - только с именем; строка, которая не является правилом, не даёт запуститься.

```yaml
route:
  direct:
    - domain:ru
    - full:api.example.com
    - 10.0.0.0/8
```

## Исходящие соединения сервера

Сервер никогда не открывает для клиента TCP-соединение или UDP-поток к собственному хосту и его сетям: loopback, частные сети, link-local (включая метаданные облака), разделяемое пространство CGNAT (`100.64.0.0/10`), multicast, зарезервированные и остальные диапазоны, которые по умолчанию отклоняет freedom-outbound Xray. Имя резолвится один раз и проверяется по всем адресам, которые оно вернуло - один запрещённый адрес запрещает всю цель, - и подключение идёт к уже проверенному адресу, так что второй раз имя не резолвится. IPv4-mapped IPv6-адрес проверяется как IPv4-адрес, к которому он ведёт. С `socks.proxy_addr` имя уходит прокси как есть и резолвится прокси по его собственной политике; здесь проверяется только адрес-литерал.

Без `debug` лог сервера не называет ни одного адресата: неудачное подключение пишет причину (`blocked target`, `no such host`, `connection refused`, ...), но не цель, а строка `traffic:` содержит сессию и счётчики байт. `debug: true` добавляет цель к каждому подключению.

## Миграция схемы конфига

На один цикл миграции строгий загрузчик принимает устаревшие поля `link`, `ffmpeg`, `video.bitrate` и `video.hw`. Текущий runtime игнорирует все четыре поля, а следующая схема конфига удалит их. Удали их из сохраненных конфигов. Остальные неизвестные поля и опечатки по-прежнему приводят к ошибке загрузки.

## Совместимость wire-форматов

Текущие сборки используют зашифрованный record layer OLC2. Направленные ключи HKDF-SHA256, разные AEAD associated data для data/control и общее replay-окно на 64 записи делают его несовместимым со старым форматом. Legacy fallback в декодере отсутствует.

`seichannel` и `videochannel` используют формат кадров OLVC версии 5: у каждого фрагмента есть своя контрольная сумма, поэтому повреждённый фрагмент переспрашивается, а не подтверждается. Старые кадры отклоняются по magic или версии. Обновляй обе стороны туннеля одновременно. В рамках версии 5 `seichannel` держит несколько сообщений в полёте к пиру, чей hello объявляет упорядоченную доставку, и откатывается на одно сообщение за раз с любым другим пиром.

## Обязательный минимум

### Сервер

> **Jitsi-провайдер:** берите инстансы из файла [`jitsi.instances.yaml`](./jitsi.instances.yaml), а не из голого текста. Проверьте хост в браузере и выберите рабочий.

```yaml
mode: srv
auth:
  provider: jitsi
room:
  # Хост берите из docs/jitsi.instances.yaml:
  # https://HOST/ROOM
  id: "https://REPLACE_ME_WITH_HOST/REPLACE_ME_WITH_ROOM_ID"
crypto:
  key: "REPLACE_ME_WITH_64_HEX_CHARS"
net:
  transport: datachannel
  dns: "8.8.8.8:53"
```

### Клиент

```yaml
mode: cnc
auth:
  provider: jitsi
room:
  # Хост берите из docs/jitsi.instances.yaml:
  # https://HOST/ROOM
  id: "https://REPLACE_ME_WITH_HOST/REPLACE_ME_WITH_ROOM_ID"
crypto:
  key: "REPLACE_ME_WITH_64_HEX_CHARS"
net:
  transport: datachannel
  dns: "8.8.8.8:53"
socks:
  host: "127.0.0.1"
  port: 8808
```

## Liveness

После `CLIENT_HELLO` / `SERVER_WELCOME` первый smux stream остаётся открытым как зашифрованный control stream. По нему `olcrtc` отправляет `CONTROL_PING` / `CONTROL_PONG`, чтобы проверять именно рабочий путь туннеля, а не только статус WebRTC-соединения.

```yaml
liveness:
  interval: 10s
  timeout: 15s
  failures: 4
```

Когда порог пропущенных pong достигнут, текущая smux-сессия пересоздаётся. В failover-режиме профиль, который завершился после неудачного reconnect, отдаёт управление supervisor, и тот пробует следующий профиль.

## Lifecycle Rotation

`lifecycle.max_session_duration` задаёт плановый верхний предел длительности одного звонка/сессии у провайдера. Когда время истекает, активная `srv` или `cnc` сессия закрывается и запускается заново с тем же конфигом.

```yaml
lifecycle:
  max_session_duration: 6h
```

Поле необязательное. Формат - Go duration: `30m`, `2h`, `6h`. Ноль и отрицательные значения не принимаются.

## Traffic Shaping

`traffic` добавляет общий wrapper вокруг выбранного транспорта. Он может ограничить размер зашифрованного сообщения и добавить небольшую задержку перед отправкой. Данные не обрезаются: если payload не помещается в эффективный лимит, отправка завершается явной ошибкой.

```yaml
traffic:
  max_payload_size: 4096
  min_delay: 5ms
  max_delay: 30ms
```

Лимит сжимается до `MaxPayloadSize`, который заявляет выбранный транспорт. Клиент и сервер также уменьшают smux frame size с учётом crypto overhead. Значение `0` не добавляет лимит сверх лимита транспорта. Если задан только `min_delay`, задержка фиксированная. Используй одинаковые `traffic`-настройки на обеих сторонах.

## Failover Profiles

`mode: srv` и `mode: cnc` могут задавать `profiles`. Верхнеуровневые поля становятся общими defaults, а каждый профиль переопределяет только то, что указано внутри него.

```yaml
mode: srv
crypto:
  key_file: ./olcrtc.key
net:
  dns: "8.8.8.8:53"

profiles:
  - name: wb-vp8
    auth:
      provider: wbstream
    room:
      id: "WB_ROOM_ID"
    net:
      transport: vp8channel

  - name: jitsi-dc
    auth:
      provider: jitsi
    room:
      id: "https://meet.example.org/olcrtc-room"
    net:
      transport: datachannel

failover:
  retry_delay: 2s
  max_cycles: 0
```

Порядок профилей и параметры комнаты должны быть совместимы на сервере и клиенте. Активные smux streams между профилями не мигрируют; новые подключения смогут восстановиться на следующем профиле.

Файл конфига перечитывается на каждом переходе, поэтому профили, добавленные или убранные при живой сессии, вступают в силу в тот момент, когда она кончается, без перезапуска. Supervisor ищет в новом списке профиль, который только что отработал, и берёт следующий за ним; если этого профиля в списке уже нет, значит окно прокатилось дальше - обход продолжается с головы и полный проход не засчитывается. Невалидный профиль пропускается с предупреждением, а не роняет перечитывание; ошибочный или пустой результат оставляет последний хороший список.

Клиент под supervisor'ом отказывается от комнаты, в которой никого нет - handshake не прошёл, и за это время оттуда не пришло ни одного кадра, - чтобы supervisor перешёл к следующему профилю. Молчащий, но присутствующий пир и конфиг с одним профилем повторяют попытки столько, сколько работает olcrtc.

## mode: gen

`gen` оставлен для провайдеров, которые реализуют создание комнат через API.

`salutejazz` — единственный встроенный провайдер, который это умеет: `-mode gen`
создаёт комнату одним анонимным POST и печатает её как `<code>:<password>` —
это и принимает `room.id`.

Остальные не создают комнаты через `olcrtc`: для `telemost` и `wbstream` создай
комнату на сайте сервиса и вставь её в `room.id`; для `jitsi` укажи URL комнаты;
для `vkcalls` создай звонок в приложении VK и вставь ссылку входа
(`https://vk.ru/call/join/<id>`) — `room.id` принимает её как есть;
для `mtslink` создай постоянную встречу в кабинете MTS Link и вставь ссылку
входа (`https://my.mts-link.ru/j/<owner>/<event>`): гость входит в неё без
организатора внутри.

Свежая комната VK держит первых участников в топологии DIRECT: двое сами её
не переключают, третий одновременный участник — переключает, и после этого
SERVER сохраняется за комнатой. Движок делает это сам: пока он ждёт, он
добирает сигналинг-гостей (не больше двух, закрывает их в момент
переключения), так что одинокий сервер в свежей комнате продолжит, как
только его рекруты — или клиент плюс один рекрут — станут третьим
участником.
