package org.olcbox.app.vpn.turnable

import androidx.compose.runtime.Composable

@Composable
actual fun rememberTurnableServerInstaller(): TurnableServerInstaller = UnsupportedTurnableServerInstaller

private object UnsupportedTurnableServerInstaller : TurnableServerInstaller {
    override suspend fun install(options: TurnableInstallOptions, onLog: (String) -> Unit) =
        Result.failure<TurnableInstallResult>(UnsupportedOperationException("Turnable auto-install is Android-only"))
}
