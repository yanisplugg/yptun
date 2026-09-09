package org.olcbox.app.ui.features.locations.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.i18n.LocalStrings
import java.awt.FileDialog
import java.awt.Frame

/**
 * Desktop actual: picks a private-key file with the AWT file dialog and reads its text. Any file type
 * is allowed — private keys usually have no/varied extensions (id_ed25519, *.pem, *.key). The desktop
 * used to render nothing here, so a key could only be pasted.
 */
@Composable
actual fun SshKeyLoadButton(enabled: Boolean, onKeyLoaded: (String) -> Unit) {
    val s = LocalStrings.current
    OutlinedButton(
        enabled = enabled,
        onClick = {
            val dialog = FileDialog(null as Frame?, s.sshKeyLoadFromFile, FileDialog.LOAD)
            dialog.isVisible = true
            val file = dialog.files.firstOrNull() ?: return@OutlinedButton
            val text = runCatching { file.readText() }.getOrNull()
            if (!text.isNullOrBlank()) onKeyLoaded(text)
        }
    ) {
        Icon(Icons.Outlined.FileOpen, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(s.sshKeyLoadFromFile)
    }
}
