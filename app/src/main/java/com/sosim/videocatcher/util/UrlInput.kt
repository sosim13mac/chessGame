package com.sosim.videocatcher.util

import java.net.URLEncoder

/** Turns what the user typed in the address bar (or shared from another app) into a URL to load. */
object UrlInput {

    const val SEARCH_URL = "https://www.google.com/search?q="

    private const val MAX_SHARED_QUERY = 200

    private val HOST_LIKE = Regex("^(localhost|[^\\s/?#]+\\.[a-zA-Z0-9-]{2,}|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?([/?#].*)?$")
    private val URL_IN_TEXT = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")

    /**
     * A web address is opened as-is ("https://..."), something that looks like a
     * host name gets "https://" added ("example.com"), anything else is searched on Google.
     */
    fun toUrl(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (hasExplicitScheme(text)) return text
        if (isGuessedHost(text)) return "https://$text"
        return searchUrl(text)
    }

    /**
     * True when [toUrl] only guessed that [input] is a site ("node.js", "example.com").
     * If that host does not exist the browser searches the text instead.
     */
    fun isGuessedHost(input: String): Boolean {
        val text = input.trim()
        return text.isNotEmpty() && !hasExplicitScheme(text) && !text.contains(' ') && HOST_LIKE.matches(text)
    }

    fun searchUrl(query: String): String = SEARCH_URL + URLEncoder.encode(query.trim(), "UTF-8")

    /** First http(s) URL inside shared text, e.g. "Look at this https://..." */
    fun extractUrl(text: String): String? = URL_IN_TEXT.find(text)?.value?.trimEnd('.', ',', ')', ']')

    /** What to open for text shared from another app: the first link in it, or a Google search for the text. */
    fun fromSharedText(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return extractUrl(trimmed) ?: searchUrl(WHITESPACE.replace(trimmed, " ").take(MAX_SHARED_QUERY))
    }

    private fun hasExplicitScheme(text: String): Boolean {
        val lower = text.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("about:")
    }
}
