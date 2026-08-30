package org.olcbox.app.vpn.olcrtc

import androidx.compose.runtime.Composable
import kotlin.random.Random

/**
 * Настройки одноклик-установки olcRTC-сервера (`mode: srv`) на VPS.
 *
 * Сервер olcRTC не слушает порт: он сам заходит в комнату выбранного сервиса видеозвонков и уже
 * оттуда открывает соединения наружу. Поэтому «сервер» здесь — это набор процессов, по одному на
 * комнату: столько же комнат клиент поднимает у себя (мультирум), и они должны совпадать один в один.
 */
data class OlcRtcInstallOptions(
    val host: String,
    val sshPort: Int = 22,
    val login: String = "root",
    val sshPassword: String = "",
    /** PEM/OpenSSH private key for SSH publickey auth; when set it is used instead of [sshPassword]. */
    val sshKey: String = "",
    /** Passphrase for an encrypted [sshKey]; empty for an unencrypted key. */
    val sshKeyPassphrase: String = "",
    /** Сервис видеозвонков: `jitsi` | `telemost` | `wbstream` (что умеет вендоренный бинарник). */
    val provider: String = "jitsi",
    /** Транспорт внутри звонка: `datachannel` | `vp8channel` | `seichannel`. */
    val transport: String = "vp8channel",
    /** Комнаты, по одной на процесс. Ровно их же клиент поднимает у себя. */
    val rooms: List<String>,
    /** DNS-резолвер сервера в формате `host:port`. */
    val dns: String = "8.8.8.8:53",
)

/**
 * Что вернула установка: общий ключ шифрования (сгенерирован на VPS) и комнаты, которые реально
 * подняты. Этого хватает, чтобы собрать локацию на клиенте — больше в olcRTC ничего не нужно:
 * ни адреса, ни порта, весь стык идёт через комнату.
 */
data class OlcRtcInstallResult(
    val cryptoKey: String,
    val rooms: List<String>,
    val provider: String,
    val transport: String,
    val status: String,
)

interface OlcRtcServerInstaller {
    /** Ставит сервер и стримит лог в [onLog]; на успехе возвращает ключ и список комнат. */
    suspend fun install(options: OlcRtcInstallOptions, onLog: (String) -> Unit): Result<OlcRtcInstallResult>

    /**
     * Сносит с VPS всё, что поставил [install]: службы всех комнат, конфиги, ключ и бинарник.
     * Реализация по умолчанию отвечает отказом — платформы без SSH-клиента удалять тоже не умеют.
     */
    suspend fun uninstall(options: OlcRtcInstallOptions, onLog: (String) -> Unit): Result<String> =
        Result.failure(UnsupportedOperationException("Удаление olcRTC-сервера доступно только в Android-приложении"))
}

/** Платформенная фабрика: настоящий SSH-установщик есть только на Android. */
@Composable
expect fun rememberOlcRtcServerInstaller(): OlcRtcServerInstaller

/** Сервисы, которые умеет вендоренный бинарник (`internal/auth`). */
val OLCRTC_PROVIDERS = listOf("jitsi", "telemost", "wbstream")

/** Транспорты, общие для сервера и нашего клиента. */
val OLCRTC_TRANSPORTS = listOf("datachannel", "vp8channel", "seichannel")

/** Сколько комнат максимум — столько же тянет мультирум на клиенте. */
const val OLCRTC_MAX_ROOMS = 5

/**
 * Комнаты уезжают в YAML на чужой машине, поэтому пропускаем только то, из чего состоит ссылка на
 * комнату. Пробелы, кавычки и переводы строк отсекаются здесь, а не «экранируются» позже: любой из
 * них в конфиге — это либо сломанный YAML, либо инъекция.
 */
private val ROOM_ALLOWED = Regex("^[A-Za-z0-9._~:/?#\\[\\]@!&*+,;=%-]{1,300}$")

internal fun sanitizeRoom(room: String): String {
    val trimmed = room.trim()
    require(ROOM_ALLOWED.matches(trimmed)) { "Недопустимая ссылка на комнату: «$room»" }
    return trimmed
}

/** `host:port` — тоже уезжает в конфиг, тоже проверяем, а не надеемся. */
internal fun sanitizeDns(dns: String): String {
    val trimmed = dns.trim().ifBlank { "8.8.8.8:53" }
    require(Regex("^[A-Za-z0-9._:-]{3,64}:[0-9]{1,5}$").matches(trimmed)) { "DNS должен быть host:port" }
    return trimmed
}

/**
 * Генерирует имена комнат для jitsi: там комната создаётся самим фактом захода, отдельного API нет
 * (в вендоренном дереве ни один провайдер не реализует room-creation, `mode: gen` для них не работает).
 * Для telemost/wbstream ссылку выдаёт сам сервис — её пользователь вставляет руками.
 */
fun generateJitsiRooms(instance: String, count: Int): List<String> {
    val host = instance.trim().trimEnd('/').ifBlank { "https://meet.jit.si" }
        .let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    return List(count.coerceIn(1, OLCRTC_MAX_ROOMS)) {
        val name = (1..16).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
        "$host/olc$name"
    }
}

/**
 * Скрипт установки (одна команда для SSH). Идемпотентен: сначала полностью сносит предыдущую
 * установку (см. [olcRtcTeardown]), потом раскладывает по процессу на комнату.
 *
 * Ключ шифрования генерируется НА VPS и кладётся в отдельный `olcrtc.key`, а конфиги ссылаются на
 * него через `crypto.key_file`. Так heredoc с конфигом закрыт кавычками ('YAML'), то есть шелл в него
 * не заглядывает вообще — ни ключ, ни ссылка на комнату не могут ничего развернуть или сломать.
 */
internal fun buildOlcRtcInstallScript(options: OlcRtcInstallOptions): String {
    val provider = options.provider.lowercase().also {
        require(it in OLCRTC_PROVIDERS) { "Неизвестный сервис: $it" }
    }
    val transport = options.transport.lowercase().also {
        require(it in OLCRTC_TRANSPORTS) { "Неизвестный транспорт: $it" }
    }
    val dns = sanitizeDns(options.dns)
    val rooms = options.rooms.map { sanitizeRoom(it) }
    require(rooms.isNotEmpty()) { "Нужна хотя бы одна комната" }
    require(rooms.size <= OLCRTC_MAX_ROOMS) { "Максимум $OLCRTC_MAX_ROOMS комнат" }
    val d = "$"

    val head = """
        set -e
        gunzip -f /tmp/olcrtc.gz
        install -m 0755 /tmp/olcrtc /usr/local/bin/olcrtc
        rm -f /tmp/olcrtc
    """.trimIndent()

    val key = """
        mkdir -p /etc/olcrtc
        umask 077
        key=${d}(openssl rand -hex 32 2>/dev/null || od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
        printf '%s' "${d}key" > /etc/olcrtc/olcrtc.key
        chmod 600 /etc/olcrtc/olcrtc.key
    """.trimIndent()

    val units = rooms.mapIndexed { index, room ->
        val n = index + 1
        """
        cat > /etc/olcrtc/srv-$n.yaml <<'YAML'
        mode: srv
        auth:
          provider: $provider
        room:
          id: "$room"
        crypto:
          key_file: "olcrtc.key"
        net:
          transport: $transport
          dns: "$dns"
        liveness:
          interval: 10s
          timeout: 15s
          failures: 4
        YAML
        cat > /etc/systemd/system/olcrtc-srv-$n.service <<UNIT
        [Unit]
        Description=olcRTC server (room $n)
        After=network-online.target
        Wants=network-online.target
        [Service]
        ExecStart=/usr/local/bin/olcrtc /etc/olcrtc/srv-$n.yaml
        Restart=always
        RestartSec=5
        LimitNOFILE=1048576
        [Install]
        WantedBy=multi-user.target
        UNIT
        systemctl enable --now olcrtc-srv-$n
        """.trimIndent()
    }

    val tail = """
        systemctl daemon-reload
        sleep 2
        up=0
        for n in ${rooms.indices.joinToString(" ") { (it + 1).toString() }}; do
          if systemctl is-active olcrtc-srv-${d}n >/dev/null 2>&1; then
            up=${d}(( up + 1 ))
          else
            echo "Служба olcrtc-srv-${d}n не поднялась:"
            journalctl -u olcrtc-srv-${d}n -n 10 --no-pager 2>/dev/null || true
          fi
        done
        echo "Запущено комнат: ${d}up из ${rooms.size}"
        [ "${d}up" -gt 0 ] || { echo "Ни одна комната не поднялась"; exit 1; }
        echo "RESULT::${d}key"
    """.trimIndent()

    return (listOf(head, olcRtcTeardown(), key) + units + listOf(tail)).joinToString("\n")
}

/**
 * Снос всех служб olcRTC и их конфигов. Один блок на установку и на удаление: иначе переустановка с
 * меньшим числом комнат оставила бы лишние процессы сидеть в старых комнатах и жечь трафик.
 */
internal fun olcRtcTeardown(): String {
    val d = "$"
    return """
        for f in /etc/systemd/system/olcrtc-srv-*.service; do
          [ -e "${d}f" ] || continue
          u=${d}(basename "${d}f" .service)
          systemctl disable --now "${d}u" 2>/dev/null || true
          rm -f "${d}f"
        done
        rm -f /etc/olcrtc/srv-*.yaml
        systemctl daemon-reload
    """.trimIndent()
}

/**
 * Полное удаление: службы, конфиги, ключ и бинарник. Бинарник лежит по нашему собственному пути
 * (`/usr/local/bin/olcrtc`), панель olcrtc-inbound держит свой отдельно, так что здесь мы никому
 * чужому не мешаем.
 */
internal fun buildOlcRtcUninstallScript(): String {
    val tail = """
        rm -rf /etc/olcrtc
        rm -f /usr/local/bin/olcrtc
        echo "REMOVED::olcrtc"
    """.trimIndent()
    return listOf("set -e", olcRtcTeardown(), tail).joinToString("\n")
}
