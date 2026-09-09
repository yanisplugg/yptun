package org.olcbox.app.vpn.wdtt

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.olcbox.app.vpn.ssh.classpathServerBinaries

/** Desktop uses the very same SSH installer; the server binaries ride in the app's resources. */
@Composable
actual fun rememberWdttServerInstaller(): WdttServerInstaller =
    remember { SshWdttServerInstaller(classpathServerBinaries) }
