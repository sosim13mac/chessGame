package com.sosim.videocatcher.util

import java.net.URLEncoder

/** Turns what the user typed in the address bar into a URL to load. */
object UrlInput {

    const val SEARCH_URL = "https://www.google.com/search?q="

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val HOST_LIKE = Regex("^(localhost|[^\\s/?#]+\\.[a-zA-Z0-9-]{2,}|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?([/?#].*)?$")
    private val URL_IN_TEXT = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)

    fun toUrl(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (SCHEME.containsMatchIn(text)) {
            val lower = text.lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("about:")) return text
        }
        if (!text.contains(' ') && HOST_LIKE.matches(text)) return "https://$text"
        return SEARCH_URL + URLEncoder.encode(text, "UTF-8")
    }

    /** First http(s) URL inside shared text, e.g. "Look at this https://..." */
    fun extractUrl(text: String): String? = URL_IN_TEXT.find(text)?.value?.trimEnd('.', ',', ')', ']')
}
