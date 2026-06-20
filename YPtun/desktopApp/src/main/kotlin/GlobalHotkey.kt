package org.olcbox.app.desktop

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser

/**
 * A persisted global-hotkey binding. [modifiers] is a Win32 MOD_* mask, [vk] a virtual-key code
 * (we reuse AWT/Win32 key codes, which match for alphanumerics & F-keys). [label] is what we show.
 */
data class HotkeyBinding(val modifiers: Int, val vk: Int, val label: String) {
    companion object {
        const val MOD_ALT = 0x0001
        const val MOD_CONTROL = 0x0002
        const val MOD_SHIFT = 0x0004
        const val MOD_WIN = 0x0008
    }
}

/**
 * A single system-wide hotkey on Windows, implemented with Win32 RegisterHotKey. A daemon thread
 * registers the key and drains its own message queue with PeekMessage (polling, so re-binding and
 * stopping need only volatile flags — no PostThreadMessage / hidden window). Triggering calls back
 * on that thread; callers should hop to their own dispatcher.
 */
object GlobalHotkey {
    private const val WM_HOTKEY = 0x0312
    private const val PM_REMOVE = 0x0001
    private const val HOTKEY_ID = 0xB0B0

    @Volatile private var running = false
    @Volatile private var desired: HotkeyBinding? = null
    @Volatile private var rebind = false
    @Volatile private var onTrigger: (() -> Unit)? = null
    private var thread: Thread? = null

    val isSupported: Boolean
        get() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    fun start(initial: HotkeyBinding?, onTrigger: () -> Unit) {
        if (running || !isSupported) return
        this.onTrigger = onTrigger
        desired = initial
        running = true
        rebind = true
        thread = Thread {
            val user32 = runCatching { User32.INSTANCE }.getOrNull() ?: return@Thread
            val msg = WinUser.MSG()
            var registered = false
            while (running) {
                if (rebind) {
                    if (registered) {
                        runCatching { user32.UnregisterHotKey(null, HOTKEY_ID) }
                        registered = false
                    }
                    desired?.let { b ->
                        registered = runCatching {
                            user32.RegisterHotKey(null, HOTKEY_ID, b.modifiers, b.vk)
                        }.getOrDefault(false)
                    }
                    rebind = false
                }
                val hasMsg = runCatching { user32.PeekMessage(msg, null, 0, 0, PM_REMOVE) }
                    .getOrDefault(false)
                if (hasMsg) {
                    if (msg.message == WM_HOTKEY) runCatching { onTrigger.invoke() }
                } else {
                    runCatching { Thread.sleep(15) }
                }
            }
            if (registered) runCatching { user32.UnregisterHotKey(null, HOTKEY_ID) }
        }.apply { isDaemon = true; name = "YPtun-GlobalHotkey"; start() }
    }

    /** Update (or clear, with null) the bound combination; takes effect within ~15ms. */
    fun setBinding(binding: HotkeyBinding?) {
        desired = binding
        rebind = true
    }

    fun stop() {
        running = false
        thread = null
    }
}
