package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.model.ZapretConfig
import org.olcbox.app.desktop.DesktopElevation
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Desktop Root/Admin DPI-bypass runner for [org.olcbox.app.data.model.EngineType.Zapret]:
 * - **Windows**: spawns `zapret-root-windows-<arch>.exe` using WinDivert (`WinDivert.dll` +
 *   `WinDivert64.sys`) when running elevated as Administrator.
 * - **Linux**: spawns `zapret-root-linux-<arch>` wrapped in an `iptables` `NFQUEUE` setup script
 *   via `LinuxPrivilege.command(...)` (`pkexec` / `sudo -n` / `root`), keeping stdin open so
 *   stopping or crashing YPtun automatically tears down iptables rules and terminates the helper.
 */
internal class DesktopZapretRoot(
    private val log: (String) -> Unit,
) {
    private var process: Process? = null

    fun isRunning(): Boolean = process?.isAlive == true

    suspend fun start(config: ZapretConfig): Boolean = withContext(Dispatchers.IO) {
        stop()
        val n = config.normalized()
        val binary = DesktopNativeAssets.resolveZapretRootBinary() ?: run {
            log("Zapret Root: нативный helper для ${DesktopPaths.os} недоступен, используется обычный ALT-режим")
            return@withContext false
        }

        when (DesktopPaths.os) {
            DesktopOs.Windows -> startWindows(binary, n)
            DesktopOs.Linux -> startLinux(binary, n)
            else -> {
                log("Zapret Root не поддерживается на ${DesktopPaths.os}")
                false
            }
        }
    }

    private suspend fun startWindows(binary: Path, config: ZapretConfig): Boolean {
        if (!DesktopElevation.isElevated()) {
            log("Zapret Root (WinDivert) на Windows требует прав администратора — запустите YPtun в TUN-режиме или от имени администратора; используется обычный ALT-режим")
            return false
        }
        val parentPid = ProcessHandle.current().pid()
        val cmd = buildList {
            add(binary.toString())
            addAll(buildHelperArgs(config))
            addAll(listOf("--parent-pid", parentPid.toString()))
        }
        return launchAndAwaitReady(
            cmd = cmd,
            readyMarker = "active windivert",
            timeoutMs = 5_000L,
            label = "Zapret Root (WinDivert)",
            workDir = binary.parent,
        )
    }

    private suspend fun startLinux(binary: Path, config: ZapretConfig): Boolean {
        val dir = DesktopPaths.appDataDir().resolve("zapret-root")
        Files.createDirectories(dir)
        val uid = resolveCurrentUid()
        val downScript = dir.resolve("zapret-root-down.sh")
        val runScript = dir.resolve("zapret-root-run.sh")
        Files.writeString(downScript, buildLinuxDownScript(uid))
        Files.writeString(runScript, buildLinuxRunScript(binary.toString(), config, uid, downScript.toString()))
        downScript.toFile().setExecutable(true, true)
        runScript.toFile().setExecutable(true, true)

        val cmd = LinuxPrivilege.command(listOf("sh", runScript.toString()))
        return launchAndAwaitReady(
            cmd = cmd,
            readyMarker = "active nfq=",
            timeoutMs = 30_000L, // allows pkexec authorization prompt
            label = "Zapret Root (NFQUEUE)",
            workDir = binary.parent,
        )
    }

    private suspend fun launchAndAwaitReady(
        cmd: List<String>,
        readyMarker: String,
        timeoutMs: Long,
        label: String,
        workDir: Path?,
    ): Boolean {
        val ready = CompletableDeferred<Boolean>()
        val started = runCatching {
            ProcessBuilder(cmd).apply {
                if (workDir != null) directory(workDir.toFile())
                redirectErrorStream(true)
            }.start()
        }.getOrElse { e ->
            log("$label не запустился: ${e.message}")
            return false
        }
        process = started

        Thread {
            runCatching {
                started.inputStream.bufferedReader().forEachLine { raw ->
                    val line = raw.trimEnd()
                    if (line.isNotBlank()) log(line)
                    if (!ready.isCompleted && line.contains(readyMarker)) {
                        ready.complete(true)
                    }
                }
            }
            if (!ready.isCompleted) ready.complete(false)
        }.apply {
            isDaemon = true
            name = "zapret-root-log"
            start()
        }

        val ok = withTimeoutOrNull(timeoutMs) { ready.await() } == true
        if (!ok) {
            log("$label не перешёл в активный режим, откат к userspace ALT")
            stop()
            return false
        }
        return true
    }

    fun stop() {
        val running = process ?: return
        process = null
        runCatching {
            // Closing stdin tells both Windows (stdin watchdog) and Linux wrapper (cat >/dev/null)
            // to unwind driver/iptables state and exit cleanly without another elevation prompt.
            running.outputStream.close()
        }
        if (!runCatching { running.waitFor(2_000, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
            runCatching { running.destroy() }
            if (!runCatching { running.waitFor(1_000, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
                runCatching { running.destroyForcibly() }
            }
        }
    }

    private fun resolveCurrentUid(): Int {
        return runCatching {
            val proc = ProcessBuilder("id", "-u").redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
            if (proc.waitFor(1, TimeUnit.SECONDS)) out.toIntOrNull() ?: -1 else -1
        }.getOrDefault(-1)
    }

    internal companion object {
        const val NFQ_NUM = 240
        const val BYPASS_MARK = "0x20000/0x20000"

        fun buildHelperArgs(config: ZapretConfig): List<String> {
            val n = config.normalized()
            return listOf(
                "--ttl", n.rootFakeTtl.toString(),
                "--sni", n.rootDecoySni,
                "--repeats", n.rootFakeCount.toString(),
                "--badsum", if (n.rootBadSum) "1" else "0",
                "--split-sni", if (n.rootSplitSni) "1" else "0",
            )
        }

        fun buildLinuxDownScript(uid: Int): String = buildString {
            appendLine("#!/bin/sh")
            if (uid > 0) {
                appendLine("while iptables -t mangle -D OUTPUT -m owner --uid-owner $uid -m mark ! --mark $BYPASS_MARK -m connbytes 1:6 --connbytes-dir original --connbytes-mode packets -p tcp -m multiport --dports 80,443 -j NFQUEUE --queue-num $NFQ_NUM --queue-bypass 2>/dev/null; do :; done")
                appendLine("while iptables -t mangle -D OUTPUT -m owner --uid-owner $uid -m mark ! --mark $BYPASS_MARK -p tcp -m multiport --dports 80,443 -j NFQUEUE --queue-num $NFQ_NUM --queue-bypass 2>/dev/null; do :; done")
            }
            appendLine("while iptables -t mangle -D OUTPUT -m mark ! --mark $BYPASS_MARK -m connbytes 1:6 --connbytes-dir original --connbytes-mode packets -p tcp -m multiport --dports 80,443 -j NFQUEUE --queue-num $NFQ_NUM --queue-bypass 2>/dev/null; do :; done")
            appendLine("while iptables -t mangle -D OUTPUT -m mark ! --mark $BYPASS_MARK -p tcp -m multiport --dports 80,443 -j NFQUEUE --queue-num $NFQ_NUM --queue-bypass 2>/dev/null; do :; done")
            appendLine("exit 0")
        }

        fun buildLinuxRunScript(
            binaryPath: String,
            config: ZapretConfig,
            uid: Int,
            downScriptPath: String,
        ): String {
            val ownerFlag = if (uid > 0) "-m owner --uid-owner $uid " else ""
            val argsStr = (listOf("--queue", NFQ_NUM.toString()) + buildHelperArgs(config))
                .joinToString(" ") { shellQuoted(it) }
            return buildString {
                appendLine("#!/bin/sh")
                appendLine("sh ${shellQuoted(downScriptPath)} >/dev/null 2>&1 || true")
                appendLine("if ! command -v iptables >/dev/null 2>&1; then")
                appendLine("  echo 'zapret_root: iptables not found' >&2")
                appendLine("  exit 1")
                appendLine("fi")
                appendLine("iptables -t mangle -I OUTPUT 1 ${ownerFlag}-m mark ! --mark $BYPASS_MARK -m connbytes 1:6 --connbytes-dir original --connbytes-mode packets -p tcp -m multiport --dports 80,443 -j NFQUEUE --queue-num $NFQ_NUM --queue-bypass 2>/dev/null || \\")
                appendLine("  iptables -t mangle -I OUTPUT 1 ${ownerFlag}-m mark ! --mark $BYPASS_MARK -p tcp -m multiport --dports 80,443 -j NFQUEUE --queue-num $NFQ_NUM --queue-bypass")
                appendLine("${shellQuoted(binaryPath)} $argsStr &")
                appendLine("ZR=\$!")
                appendLine("cat >/dev/null")
                appendLine("kill \$ZR 2>/dev/null || true")
                appendLine("wait \$ZR 2>/dev/null || true")
                appendLine("sh ${shellQuoted(downScriptPath)} >/dev/null 2>&1 || true")
                appendLine("exit 0")
            }
        }

        private fun shellQuoted(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
