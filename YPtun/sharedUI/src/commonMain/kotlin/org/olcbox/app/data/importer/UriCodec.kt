package org.olcbox.app.data.importer

/** Small URI helpers shared by the share-link parsers. */
internal object UriCodec {

    /** Percent- and plus-decode a URI component into a UTF-8 string. */
    fun percentDecode(value: String): String {
        if (!value.contains('%') && !value.contains('+')) return value
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '%' && i + 2 < value.length -> {
                    val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        bytes.add(hex.toByte())
                        i += 3
                    } else {
                        bytes.add(c.code.toByte())
                        i++
                    }
                }

                c == '+' -> {
                    bytes.add(' '.code.toByte())
                    i++
                }

                else -> {
                    c.toString().encodeToByteArray().forEach { bytes.add(it) }
                    i++
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    /** Split "host:port" or "[ipv6]:port" into host and port. */
    fun splitHostPort(value: String): Pair<String, Int>? {
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            if (close < 0) return null
            val host = value.substring(1, close)
            val rest = value.substring(close + 1)
            if (!rest.startsWith(":")) return null
            val port = rest.substring(1).toIntOrNull() ?: return null
            return host to port
        }
        val colon = value.lastIndexOf(':')
        if (colon <= 0) return null
        val host = value.substring(0, colon)
        val port = value.substring(colon + 1).toIntOrNull() ?: return null
        return host to port
    }

    /** Parse an `a=b&c=d` query string into a map (values are percent-decoded). */
    fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&')
            .mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val eq = pair.indexOf('=')
                if (eq < 0) {
                    percentDecode(pair) to ""
                } else {
                    percentDecode(pair.substring(0, eq)) to percentDecode(pair.substring(eq + 1))
                }
            }
            .toMap()
    }
}
