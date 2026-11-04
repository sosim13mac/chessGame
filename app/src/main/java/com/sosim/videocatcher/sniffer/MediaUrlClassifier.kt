package com.sosim.videocatcher.sniffer

/** What kind of media a URL points to. */
enum class MediaKind {
    /** A complete video file (mp4, webm, ...) that can be downloaded as-is. */
    DIRECT,

    /** An HLS playlist (.m3u8). */
    HLS,

    /** A DASH manifest (.mpd). */
    DASH,

    /** A Media Source Extensions stream (blob: URL) whose source has not been found yet. */
    MSE,
}

/**
 * Classifies URLs (and optional MIME types) as video media.
 * Keep the rules in sync with `classify()` in assets/video_sniffer.js.
 */
object MediaUrlClassifier {

    private val DIRECT_EXTENSIONS = setOf(
        "mp4", "m4v", "webm", "mov", "mkv", "3gp", "ogv", "flv", "avi", "wmv", "mpeg", "mpg",
    )

    private val HLS_MIME_TYPES = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "application/mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
    )

    private const val DASH_MIME_TYPE = "application/dash+xml"

    /** Video MIME types used for stream segments rather than whole files. */
    private val SEGMENT_MIME_TYPES = setOf("video/mp2t", "video/iso.segment", "video/vnd.dlna.mpeg-tts")

    fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /** Returns the media kind of [url], or null if it does not look like a video resource. */
    fun classify(url: String, mimeType: String? = null): MediaKind? {
        if (!isHttpUrl(url)) return null
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (mime.isNotEmpty()) {
            if (mime in HLS_MIME_TYPES) return MediaKind.HLS
            if (mime == DASH_MIME_TYPE) return MediaKind.DASH
            if (mime.startsWith("video/") && mime !in SEGMENT_MIME_TYPES) return MediaKind.DIRECT
        }
        return when (val ext = extensionOf(url)) {
            "m3u8" -> MediaKind.HLS
            "mpd" -> MediaKind.DASH
            null -> null
            else -> if (ext in DIRECT_EXTENSIONS) MediaKind.DIRECT else null
        }
    }

    /** Lower-cased file extension of the URL path (query and fragment ignored), or null. */
    fun extensionOf(url: String): String? {
        val lastSegment = lastPathSegment(url) ?: return null
        val dot = lastSegment.lastIndexOf('.')
        if (dot <= 0 || dot == lastSegment.length - 1) return null
        return lastSegment.substring(dot + 1).lowercase()
    }

    /** The last segment of the URL path (may be empty), or null if the URL has no path. */
    fun lastPathSegment(url: String): String? {
        var end = url.length
        val query = url.indexOf('?')
        if (query in 0 until end) end = query
        val fragment = url.indexOf('#')
        if (fragment in 0 until end) end = fragment
        val withoutQuery = url.substring(0, end)
        val schemeEnd = withoutQuery.indexOf("://")
        val pathStart = if (schemeEnd >= 0) withoutQuery.indexOf('/', schemeEnd + 3) else 0
        if (pathStart < 0) return null
        return withoutQuery.substring(pathStart).substringAfterLast('/')
    }

    /** "host/dir/" of a URL; used to group requests that belong to the same stream. */
    fun directoryKey(url: String): String {
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        val schemeEnd = withoutQuery.indexOf("://")
        val rest = if (schemeEnd >= 0) withoutQuery.substring(schemeEnd + 3) else withoutQuery
        return rest.substringBeforeLast('/', rest) + "/"
    }
}
