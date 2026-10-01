package org.olcbox.app.vpn.snolc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.olcbox.app.vpn.ssh.classpathServerBinaries

@Composable
actual fun rememberSnolcServerInstaller(): SnolcServerInstaller = remember { SshSnolcServerInstaller(classpathServerBinaries) }
