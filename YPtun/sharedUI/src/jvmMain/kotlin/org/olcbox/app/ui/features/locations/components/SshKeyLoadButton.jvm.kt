package org.olcbox.app.ui.features.locations.components

import androidx.compose.runtime.Composable

/** Desktop has no VPS installer UI — render nothing. */
@Composable
actual fun SshKeyLoadButton(enabled: Boolean, onKeyLoaded: (String) -> Unit) {
}
