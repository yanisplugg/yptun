package org.olcbox.app.ui.features.locations.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.olcbox.app.ui.i18n.LocalStrings

/**
 * Settings of a location from an encrypted subscription: only the name and a note, never its address, token
 * or key. The one thing the user may still change is the VK call link of a VK-TURN location ([vkLink] =
 * the user's own link, null when the location has no such setting; blank in [onSaveVkLink] = the subscription's again).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockedLocationNotice(
    name: String,
    onBack: () -> Unit,
    vkLink: String? = null,
    onSaveVkLink: (String) -> Unit = {},
) {
    val s = LocalStrings.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(s.lockedLocationNotice)
            if (vkLink != null) {
                var text by remember(vkLink) { mutableStateOf(vkLink) }
                Spacer(Modifier.height(24.dp))
                Text(s.vkCallLinksSection, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    placeholder = { Text("https://vk.com/call/join/…") },
                    supportingText = { Text(s.vkCallLinksSubtitle) },
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onSaveVkLink(text) }, enabled = text.trim() != vkLink.trim()) { Text(s.save) }
            }
        }
    }
}
