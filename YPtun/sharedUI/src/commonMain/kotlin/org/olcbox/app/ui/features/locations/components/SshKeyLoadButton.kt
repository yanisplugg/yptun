package org.olcbox.app.ui.features.locations.components

import androidx.compose.runtime.Composable

/**
 * A small "load key from a file" button for the SSH-key auth field. Picking a file is platform
 * specific, so this is an [expect]: Android opens the system document picker and reads the chosen
 * file's text; the desktop/iOS targets (which don't ship the VPS installers) render nothing.
 * [onKeyLoaded] receives the file's full text content.
 */
@Composable
expect fun SshKeyLoadButton(enabled: Boolean, onKeyLoaded: (String) -> Unit)
