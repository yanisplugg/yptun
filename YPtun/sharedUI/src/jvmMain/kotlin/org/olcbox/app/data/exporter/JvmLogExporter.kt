package org.olcbox.app.data.exporter

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class JvmLogExporter : LogExporter {
    override suspend fun writeLogs(target: Any, content: String): Result<String> {
        val path = when (target) {
            is Path -> target
            is File -> target.toPath()
            is String -> Path.of(target)
            else -> return Result.failure(IllegalArgumentException("Desktop log export target must be a file path"))
        }

        return runCatching {
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, content)
            path.toAbsolutePath().toString()
        }
    }

    override suspend fun shareLogs(content: String): Result<String> {
        return runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(content), null)
            "Logs copied to clipboard"
        }
    }
}
