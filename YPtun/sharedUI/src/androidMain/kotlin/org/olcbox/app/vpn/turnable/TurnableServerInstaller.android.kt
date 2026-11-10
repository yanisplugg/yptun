package org.olcbox.app.vpn.turnable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.olcbox.app.vpn.ssh.ServerBinarySource

@Composable
actual fun rememberTurnableServerInstaller(): TurnableServerInstaller {
    val context = LocalContext.current.applicationContext
    return remember {
        SshTurnableServerInstaller(
            ServerBinarySource { path ->
                runCatching { context.assets.open(path).use { it.readBytes() } }.getOrNull()
            }
        )
    }
}
