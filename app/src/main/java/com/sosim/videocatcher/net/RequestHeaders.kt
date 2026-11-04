package com.sosim.videocatcher.net

/** Builds the HTTP headers used to download a detected video like the page's player would. */
object RequestHeaders {

    /** Headers worth replaying from the original request (besides User-Agent / Referer). */
    private val REPLAYED = setOf("origin", "authorization")

    /** Headers that must never be copied from a captured request. */
    private val BLOCKED = setOf(
        "range", "cookie", "host", "connection", "content-length", "accept-encoding",
        "x-requested-with", "if-range", "if-none-match", "if-modified-since",
    )

    fun forDownload(userAgent: String, referer: String?, captured: Map<String, String>): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        if (userAgent.isNotBlank()) headers["User-Agent"] = userAgent
        headers["Accept"] = "*/*"
        val capturedReferer = captured.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
        val effectiveReferer = referer?.takeIf { isHttp(it) } ?: capturedReferer?.takeIf { isHttp(it) }
        if (effectiveReferer != null) headers["Referer"] = effectiveReferer
        for ((name, value) in captured) {
            val lower = name.lowercase()
            if (lower in BLOCKED || !isValidHeader(name, value)) continue
            if (lower in REPLAYED || lower.startsWith("x-")) headers[name] = value
        }
        return headers
    }

    fun isValidHeader(name: String, value: String): Boolean =
        name.isNotBlank() && name.none { it == ':' || it.isWhitespace() || it.code < 0x20 } &&
            value.none { it == '\r' || it == '\n' }

    private fun isHttp(url: String) =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
}
