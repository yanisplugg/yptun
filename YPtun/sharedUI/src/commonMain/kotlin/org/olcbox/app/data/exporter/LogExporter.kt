package org.olcbox.app.data.exporter

interface LogExporter {
    suspend fun writeLogs(target: Any, content: String): Result<String>
    suspend fun shareLogs(content: String): Result<String> {
        return Result.failure(UnsupportedOperationException("Log sharing is not available on this platform"))
    }
}
