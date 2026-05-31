package org.olcbox.app.data.importer

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class JvmConfigImporter : ConfigImporter {
    override fun getFromClipboard(): String? {
        return runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return null
            clipboard.getData(DataFlavor.stringFlavor) as? String
        }.getOrNull()?.ifBlank { null }
    }

    override fun copyToClipboard(text: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    override suspend fun readTextFromSource(source: Any): String? {
        val path = when (source) {
            is Path -> source
            is File -> source.toPath()
            is String -> Path.of(source)
            else -> return null
        }
        return runCatching { Files.readString(path) }.getOrNull()
    }
}
