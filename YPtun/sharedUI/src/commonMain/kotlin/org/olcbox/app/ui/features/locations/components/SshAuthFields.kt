package org.olcbox.app.ui.features.locations.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.i18n.LocalStrings

/**
 * Reusable SSH credentials block for the VPS installers: a toggle between password auth and
 * publickey auth. In key mode the user can paste a private key or load it from a file
 * ([SshKeyLoadButton]) and, if it's encrypted, enter its passphrase. All state is hoisted so each
 * install dialog owns the values it feeds into its install options.
 */
@Composable
fun SshAuthFields(
    useKey: Boolean,
    onUseKeyChange: (Boolean) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    privateKey: String,
    onPrivateKeyChange: (String) -> Unit,
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.sshAuthUseKey, modifier = Modifier.weight(1f))
            Switch(checked = useKey, onCheckedChange = onUseKeyChange, enabled = enabled)
        }
        if (!useKey) {
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(s.sshPasswordLabel) },
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            OutlinedTextField(
                value = privateKey,
                onValueChange = onPrivateKeyChange,
                label = { Text(s.sshKeyLabel) },
                enabled = enabled,
                minLines = 3,
                maxLines = 6,
                // Monospace + no auto-capitalisation/suggestions so a pasted key isn't mangled.
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false
                ),
                modifier = Modifier.fillMaxWidth()
            )
            SshKeyLoadButton(enabled = enabled, onKeyLoaded = onPrivateKeyChange)
            OutlinedTextField(
                value = passphrase,
                onValueChange = onPassphraseChange,
                label = { Text(s.sshKeyPassphraseLabel) },
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
