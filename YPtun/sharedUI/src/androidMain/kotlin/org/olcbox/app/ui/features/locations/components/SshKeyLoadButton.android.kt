package org.olcbox.app.ui.features.locations.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.i18n.LocalStrings

/**
 * Android actual: opens the system document picker and reads the chosen file's text into [onKeyLoaded].
 * Any file type is allowed — private keys usually have no/varied extensions (id_ed25519, *.pem, *.key).
 */
@Composable
actual fun SshKeyLoadButton(enabled: Boolean, onKeyLoaded: (String) -> Unit) {
    val context = LocalContext.current
    val s = LocalStrings.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (!text.isNullOrBlank()) onKeyLoaded(text)
    }
    OutlinedButton(enabled = enabled, onClick = { launcher.launch(arrayOf("*/*")) }) {
        Icon(Icons.Outlined.FileOpen, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(s.sshKeyLoadFromFile)
    }
}
