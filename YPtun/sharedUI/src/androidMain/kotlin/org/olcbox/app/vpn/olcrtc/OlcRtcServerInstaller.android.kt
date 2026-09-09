package org.olcbox.app.vpn.olcrtc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.olcbox.app.vpn.ssh.ServerBinarySource

/** Android actual: the installer itself is shared with the desktop; assets are the binary source. */
@Composable
actual fun rememberOlcRtcServerInstaller(): OlcRtcServerInstaller {
    val context = LocalContext.current.applicationContext
    return remember {
        SshOlcRtcServerInstaller(
            ServerBinarySource { path ->
                runCatching { context.assets.open(path).use { it.readBytes() } }.getOrNull()
            }
        )
    }
}
