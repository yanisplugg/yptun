package org.olcbox.app.vpn.snolc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.olcbox.app.vpn.ssh.ServerBinarySource

@Composable
actual fun rememberSnolcServerInstaller(): SnolcServerInstaller {
    val context = LocalContext.current.applicationContext
    return remember {
        SshSnolcServerInstaller(
            ServerBinarySource { path -> runCatching { context.assets.open(path).use { it.readBytes() } }.getOrNull() }
        )
    }
}
