package org.olcbox.app.vpn.freeturn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Скрипт установки уезжает на чужой VPS и выполняется там от root — сломанную кавычку или уехавший
 * терминатор heredoc там ловить уже поздно, а запустить его тут негде. Поэтому проверяем ровно то,
 * что ломается молча: отступы (Kotlin trimIndent на склеенных кусках), закрытие heredoc'ов и
 * согласованность порта бэкенда между туннелем и `-connect` сервера.
 */
class FreeturnInstallScriptTest {

    private fun script(exit: FreeturnExit, port: Int = 56000) =
        buildInstallScript(FreeturnInstallOptions(host = "203.0.113.7", freeturnPort = port, exit = exit))

    /** Терминатор heredoc закрывает документ ТОЛЬКО в первой колонке — иначе всё, что ниже, съедается. */
    @Test
    fun heredocTerminatorsStartAtColumnZero() {
        val scripts = FreeturnExit.entries.map { it.name to script(it) } + ("uninstall" to buildUninstallScript(56000))
        for ((exit, text) in scripts) {
            val lines = text.lines()
            for (terminator in listOf("EOF", "UNIT", "RUNNER", "AWGUNIT")) {
                val opens = lines.count { it.trimEnd().endsWith("<<$terminator") }
                val closes = lines.count { it == terminator }
                assertEquals(opens, closes, "$exit: $terminator открыт $opens раз, закрыт $closes")
            }
        }
    }

    /** Куски склеиваются после trimIndent — ни одна строка не должна уехать вправо. */
    @Test
    fun noStrayIndentation() {
        val scripts = FreeturnExit.entries.map { it.name to script(it) } + ("uninstall" to buildUninstallScript(56000))
        for ((exit, text) in scripts) {
            val indented = text.lines().filter { it.startsWith("    ") }
            assertTrue(indented.isEmpty(), "$exit: строки с лишним отступом: $indented")
        }
    }

    /** Порт бэкенда в -connect обязан совпадать с портом, который слушает туннель. */
    @Test
    fun backendPortMatchesConnect() {
        val port = 56000
        val expected = 51820 + (port % 250 + 1)
        assertTrue(script(FreeturnExit.WireGuard).contains("-connect 127.0.0.1:$expected"))
        assertTrue(script(FreeturnExit.WireGuard).contains("ListenPort = $expected"))
        assertTrue(script(FreeturnExit.AmneziaWG).contains("-connect 127.0.0.1:$expected"))
        assertTrue(script(FreeturnExit.AmneziaWG).contains("listen_port=%s"))
        assertTrue(script(FreeturnExit.AmneziaWG).contains("\"$expected\""))
    }

    /** Выход AmneziaWG обязан вернуть параметры обфускации — без них клиент соберёт обычный WG. */
    @Test
    fun amneziaExitReportsObfuscationParams() {
        val awg = script(FreeturnExit.AmneziaWG)
        assertTrue(awg.contains("RESULT::\$key|\$spub|\$cpriv|10.7.1.2/32|\$jc,\$jmin,\$jmax,\$s1,\$s2,\$h1,\$h2,\$h3,\$h4"))
        // jc/s1/h1 знает только UAPI: wg setconf их отвергает, поэтому конфиг идёт через сокет.
        assertTrue(awg.contains("iface=ftawg56000"))
        // $iface разворачивает внешний shell, когда пишет runner-скрипт, поэтому здесь он ещё строкой.
        assertTrue(awg.contains("socat - UNIX-CONNECT:/var/run/amneziawg/\$iface.sock"))
        assertFalse(awg.contains("wg setconf"))
        // WireGuard-выход остаётся без хвоста параметров.
        val wg = script(FreeturnExit.WireGuard)
        assertTrue(wg.contains("RESULT::\$key|\$spub|\$cpriv|10.7.1.2/32\""))
        assertFalse(wg.contains("\$jc"))
    }

    /** Удаление обязано снести ОБА возможных бэкенда: тип выхода по порту не восстановить. */
    @Test
    fun uninstallRemovesBothBackends() {
        val s = buildUninstallScript(56000)
        assertTrue(s.contains("wg-quick down ftwg56000"))
        assertTrue(s.contains("systemctl disable --now ftawg56000"))
        assertTrue(s.contains("rm -f /etc/wireguard/ftwg56000.conf"))
        assertTrue(s.contains("ufw delete allow 56000/udp"))
        assertTrue(s.contains("REMOVED::56000"))
    }

    /**
     * Бинарники общие на все порты: снести их безусловно = убить соседние серверы на том же VPS.
     * Удаление обязано спрашивать, остались ли другие службы.
     */
    @Test
    fun uninstallKeepsSharedBinariesWhileOtherServersRemain() {
        val s = buildUninstallScript(56000)
        val guard = s.indexOf("ls /etc/systemd/system/freeturn-server-*.service")
        val removal = s.indexOf("rm -f /usr/local/bin/freeturn-server")
        assertTrue(guard in 0 until removal, "удаление бинарников не под проверкой других служб")
    }

    /** Установка переиспользует тот же teardown — иначе переустановка и удаление разъедутся. */
    @Test
    fun installReusesTheSameTeardown() {
        val teardown = teardownBlock(56000)
        assertTrue(script(FreeturnExit.WireGuard).contains(teardown))
        assertTrue(buildUninstallScript(56000).contains(teardown))
    }

    /** Разные порты на одном VPS не должны делить ни подсеть, ни интерфейс. */
    @Test
    fun portsGetTheirOwnSubnetAndInterface() {
        val a = script(FreeturnExit.AmneziaWG, port = 56000)
        val b = script(FreeturnExit.AmneziaWG, port = 56001)
        assertTrue(a.contains("ftawg56000") && b.contains("ftawg56001"))
        assertTrue(a.contains("10.7.1.1/24") && b.contains("10.7.2.1/24"))
    }
}
