package com.sosim.videocatcher.util

/** Helpers for turning page titles into safe file names. */
object FileNames {

    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")
    private val WHITESPACE = Regex("\\s+")

    fun sanitize(name: String, maxLength: Int = 80): String {
        var s = ILLEGAL.replace(name, " ")
        s = WHITESPACE.replace(s, " ").trim().trim('.', ' ')
        if (s.length > maxLength) {
            var end = maxLength
            if (Character.isHighSurrogate(s[end - 1])) end--
            s = s.substring(0, end).trim().trim('.', ' ')
        }
        return s.ifEmpty { "video" }
    }

    /** Builds a base name (no extension) such as "My video_720p". */
    fun baseName(title: String, height: Int): String {
        val base = sanitize(title, maxLength = 72)
        return if (height > 0) "${base}_${height}p" else base
    }

    /** Removes a trailing extension the user may have typed (".mp4"). */
    fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name
        val ext = name.substring(dot + 1)
        return if (ext.length in 2..4 && ext.all { it.isLetterOrDigit() }) name.substring(0, dot) else name
    }
}
