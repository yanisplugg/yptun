package org.olcbox.app.data.importer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader

class AndroidConfigImporter(private val context: Context) : ConfigImporter {
    override fun getFromClipboard(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()
            if (text.isNullOrBlank()) {
                Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
            return text
        }
        Toast.makeText(context, "No clipboard data found", Toast.LENGTH_SHORT).show()
        return null
    }

    override fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("YPtun Locations", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Config copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    override suspend fun readTextFromSource(source: Any): String? {
        if (source is Uri) {
            return try {
                val text = context.contentResolver.openInputStream(source)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        reader.readText()
                    }
                }
                org.olcbox.app.vpn.service.OlcboxVpnState.addLog(
                    "import: read file ${text?.length ?: -1} chars, head=\"" +
                        (text?.take(40)?.replace("\n", " ") ?: "<null>") + "\""
                )
                text
            } catch (e: Exception) {
                org.olcbox.app.vpn.service.OlcboxVpnState.addLog("import: read file FAILED: ${e.message}")
                null
            }
        }
        org.olcbox.app.vpn.service.OlcboxVpnState.addLog("import: source is not a Uri (${source::class.simpleName})")
        return null
    }
}
