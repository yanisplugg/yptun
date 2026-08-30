package org.olcbox.app.vpn.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Готовность TUN проверяется по ДРУЖЕСТВЕННОМУ имени адаптера («YPtun», его же получает tun2socks
 * через `--device`), а не по описанию («tun2socks Tunnel»). `NetworkInterface` отдаёт только
 * описание, поэтому прежняя проверка не совпадала НИКОГДА: любой сеанс поверх tun2socks (olcRTC,
 * DNSTT — всё, где TUN не держит сам sing-box) досиживал таймаут и падал с «YPtun adapter was not
 * created», хотя адаптер был поднят. Ошибка тихая, ловится только тестом.
 */
class WindowsTunControllerTest {

    @Test
    fun adapterIsFoundByFriendlyNameAndNotByDescription() {
        val adapter = firstLiveWindowsAdapter() ?: return
        val (name, description) = adapter
        assertTrue(
            WindowsTunController.interfaceAliasIsUp(name),
            "живой адаптер '$name' не найден по дружественному имени"
        )
        if (!description.equals(name, ignoreCase = true)) {
            assertFalse(
                WindowsTunController.interfaceAliasIsUp(description),
                "описание '$description' не является именем адаптера и не должно совпадать"
            )
        }
    }

    @Test
    fun unknownAliasIsNotAnAdapter() {
        assertFalse(WindowsTunController.interfaceAliasIsUp("yptun-no-such-adapter-42"))
    }

    /** Имя и описание первого поднятого адаптера, или null вне Windows. */
    private fun firstLiveWindowsAdapter(): Pair<String, String>? {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return null
        val output = runCatching {
            ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                // Имена адаптеров локализованы («Беспроводная сеть»), поэтому вывод забираем в UTF-8:
                // в кодовой странице консоли имя приезжает битым и не находится по определению.
                "[Console]::OutputEncoding = [Text.Encoding]::UTF8; " +
                    "Get-NetAdapter | Where-Object { \$_.Status -eq 'Up' } | Select-Object -First 1 | " +
                    "ForEach-Object { \$_.Name; \$_.InterfaceDescription }"
            ).redirectErrorStream(true).start().inputStream
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull().orEmpty()
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return if (lines.size < 2) null else lines[0] to lines[1]
    }
}
