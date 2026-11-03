package org.olcbox.app.data.datasource

import kotlin.concurrent.Volatile

/**
 * What the last subscription download actually got, for the import error the user sees: "No valid config"
 * alone does not tell a failed request from an empty or unexpected answer. Only a safe summary is kept —
 * status, content type, size and the link schemes found — never the links, keys or tokens themselves.
 */
object ImportDiagnostics {
    // The importer may try several request variants (User-Agents, header sets); keep each distinct answer.
    @Volatile
    private var entries: List<String> = emptyList()

    val last: String get() = entries.joinToString(" | ")

    fun reset() {
        entries = emptyList()
    }

    private fun add(entry: String) {
        if (entry !in entries) entries = (entries + entry).takeLast(4)
    }

    fun requestFailed(error: Throwable) {
        add("запрос не выполнен: ${error::class.simpleName ?: "error"}" +
            (error.message?.take(120)?.let { ": $it" } ?: ""))
    }

    fun httpStatus(code: Int) {
        add("сервер ответил HTTP $code")
    }

    fun body(code: Int, contentType: String?, body: String) {
        // A base64 subscription body: count the links inside it.
        val decoded = if ("://" !in body) {
            runCatching { org.olcbox.app.data.importer.SubscriptionDecoder.decodeBase64Chunk(body.trim()) }.getOrNull()
        } else null
        val text = decoded ?: body
        val schemes = Regex("""(?m)^\s*([a-z][a-z0-9+.-]{1,15})://""")
            .findAll(text)
            .map { it.groupValues[1].lowercase() }
            .groupingBy { it }
            .eachCount()
        val head = body.trimStart().take(1)
        val kind = when {
            schemes.isNotEmpty() -> (if (decoded != null) "base64, " else "") + "ссылки: " + schemes.entries.joinToString { "${it.key}×${it.value}" }
            head == "<" -> "HTML-страница вместо подписки"
            head == "{" || head == "[" -> "JSON"
            else -> "без ссылок (возможно base64)"
        }
        add("HTTP $code, ${contentType ?: "без типа"}, ${body.length} симв., $kind")
    }
}
