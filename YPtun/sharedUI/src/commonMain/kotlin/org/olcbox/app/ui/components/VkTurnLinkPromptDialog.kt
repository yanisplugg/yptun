package org.olcbox.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.i18n.LocalStrings

/**
 * A VK-TURN location can only connect once it has a VK call link. When connecting one that has none
 * the home screen asks for it here (the link can be added from location settings too); "Next" saves
 * it so the location can connect. Shared by Android and desktop — the desktop had no prompt at all,
 * so there a link-less VK-TURN location simply refused to connect with nothing on screen.
 */
@Composable
fun VkTurnLinkPromptDialog(
    locationName: String,
    onLater: () -> Unit,
    onNext: (String) -> Unit
) {
    var link by remember { mutableStateOf("") }
    val s = LocalStrings.current
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(s.vkCallLink) },
        text = {
            Column {
                Text(s.vkCallLinkBody(locationName))
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    placeholder = { Text("https://vk.com/call/join/…") },
                    isError = link.isNotBlank() && !link.contains("/call/join/"),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onNext(link) },
                enabled = link.contains("/call/join/")
            ) {
                Text(s.next)
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text(s.later)
            }
        }
    )
}
