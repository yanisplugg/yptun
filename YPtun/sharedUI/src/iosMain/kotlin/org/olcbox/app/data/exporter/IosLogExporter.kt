package org.olcbox.app.data.exporter

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.olcbox.app.ios.IosMessageCallback
import org.olcbox.app.ios.IosPlatformBridge

class IosLogExporter(
    private val platformBridge: IosPlatformBridge
) : LogExporter {
    override suspend fun writeLogs(target: Any, content: String): Result<String> {
        val defaultName = (target as? String)?.ifBlank { DEFAULT_LOG_FILE_NAME } ?: DEFAULT_LOG_FILE_NAME
        return platformBridge.awaitMessage { callback ->
            saveLogs(defaultName, content, callback)
        }
    }

    override suspend fun shareLogs(content: String): Result<String> {
        return platformBridge.awaitMessage { callback ->
            shareLogs(DEFAULT_LOG_FILE_NAME, content, callback)
        }
    }

    private suspend fun IosPlatformBridge.awaitMessage(
        block: IosPlatformBridge.(IosMessageCallback) -> Unit
    ): Result<String> = suspendCancellableCoroutine { continuation ->
        block(object : IosMessageCallback {
            override fun onSuccess(message: String) {
                if (continuation.isActive) {
                    continuation.resume(Result.success(message))
                }
            }

            override fun onError(message: String) {
                if (continuation.isActive) {
                    continuation.resume(Result.failure(IllegalStateException(message)))
                }
            }
        })
    }

    private companion object {
        const val DEFAULT_LOG_FILE_NAME = "olcbox-logs.txt"
    }
}
