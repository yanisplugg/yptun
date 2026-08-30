package org.olcbox.app.vpn.olcrtc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Скрипт уезжает на чужой VPS и выполняется там от root, а ссылки на комнаты приходят из поля ввода.
 * Проверяем то, что ломается молча: закрытие heredoc'ов, отступы после склейки кусков, по процессу на
 * комнату и отсечение всего, что в YAML-конфиге быть не должно.
 */
class OlcRtcInstallScriptTest {

    private fun options(rooms: List<String>) = OlcRtcInstallOptions(
        host = "203.0.113.7",
        sshPassword = "x",
        provider = "jitsi",
        transport = "vp8channel",
        rooms = rooms,
    )

    private val script = buildOlcRtcInstallScript(options(listOf("https://meet.example.org/olcabc", "https://meet.example.org/olcdef")))

    @Test
    fun heredocTerminatorsStartAtColumnZero() {
        for (text in listOf(script, buildOlcRtcUninstallScript())) {
            val lines = text.lines()
            for (terminator in listOf("YAML", "UNIT")) {
                val opens = lines.count { it.trimEnd().endsWith("<<$terminator") || it.trimEnd().endsWith("<<'$terminator'") }
                val closes = lines.count { it == terminator }
                assertEquals(opens, closes, "$terminator: открыт $opens раз, закрыт $closes")
            }
        }
    }

    @Test
    fun noStrayIndentation() {
        for (text in listOf(script, buildOlcRtcUninstallScript())) {
            // 8 пробелов = базовый отступ Kotlin raw string, то есть кусок склеили без trimIndent.
            // Меньшие отступы законны: это вложенность shell-блоков внутри самого скрипта.
            val indented = text.lines().filter { it.startsWith("        ") }
            assertTrue(indented.isEmpty(), "строки с лишним отступом: $indented")
        }
    }

    /** По процессу на комнату: клиент поднимает столько же, и они обязаны совпасть один в один. */
    @Test
    fun oneUnitPerRoom() {
        assertTrue(script.contains("/etc/olcrtc/srv-1.yaml"))
        assertTrue(script.contains("/etc/olcrtc/srv-2.yaml"))
        assertTrue(script.contains("systemctl enable --now olcrtc-srv-1"))
        assertTrue(script.contains("systemctl enable --now olcrtc-srv-2"))
        assertTrue(!script.contains("olcrtc-srv-3"))
        assertTrue(script.contains("""id: "https://meet.example.org/olcabc""""))
    }

    /**
     * Ключ кладётся в отдельный файл, а конфиг ссылается на него: только так heredoc можно закрыть
     * кавычками, то есть гарантировать, что шелл в конфиг вообще не заглядывает.
     */
    @Test
    fun keyGoesToItsOwnFileAndConfigHeredocIsQuoted() {
        assertTrue(script.contains("key_file: \"olcrtc.key\""))
        assertTrue(script.contains("<<'YAML'"))
        assertTrue(script.contains("chmod 600 /etc/olcrtc/olcrtc.key"))
        assertTrue(script.contains("RESULT::\$key"))
    }

    /** Комната из поля ввода не должна уметь ни сломать YAML, ни доехать до шелла. */
    @Test
    fun roomsAreValidatedNotEscaped() {
        for (bad in listOf(
            "https://host/room\nmode: cnc",
            "https://host/room\"",
            "https://host/room; rm -rf /",
            "\$(id)",
            "",
        )) {
            assertFailsWith<IllegalArgumentException>("пропущена комната «$bad»") {
                buildOlcRtcInstallScript(options(listOf(bad)))
            }
        }
    }

    @Test
    fun unknownProviderOrTransportIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            buildOlcRtcInstallScript(options(listOf("https://h/r")).copy(provider = "skype"))
        }
        assertFailsWith<IllegalArgumentException> {
            buildOlcRtcInstallScript(options(listOf("https://h/r")).copy(transport = "carrierpigeon"))
        }
    }

    /** Переустановка с меньшим числом комнат не должна оставить лишние процессы в старых комнатах. */
    @Test
    fun installReusesTheSameTeardown() {
        assertTrue(script.contains(olcRtcTeardown()))
        assertTrue(buildOlcRtcUninstallScript().contains(olcRtcTeardown()))
    }

    @Test
    fun generatedJitsiRoomsAreUniqueAndWellFormed() {
        val rooms = generateJitsiRooms("meet.example.org", 5)
        assertEquals(5, rooms.size)
        assertEquals(5, rooms.toSet().size)
        rooms.forEach { assertTrue(it.startsWith("https://meet.example.org/olc"), it) }
        // Сгенерированные имена обязаны проходить ту же проверку, что и введённые руками.
        assertTrue(buildOlcRtcInstallScript(options(rooms)).contains(rooms.last()))
    }
}
