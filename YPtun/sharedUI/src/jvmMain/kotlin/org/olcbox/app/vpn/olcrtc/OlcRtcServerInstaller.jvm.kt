package org.olcbox.app.vpn.olcrtc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.olcbox.app.vpn.ssh.classpathServerBinaries

/** Desktop uses the very same SSH installer; the server binaries ride in the app's resources. */
@Composable
actual fun rememberOlcRtcServerInstaller(): OlcRtcServerInstaller =
    remember { SshOlcRtcServerInstaller(classpathServerBinaries) }
