package com.sosim.videocatcher.hls

/** Parser for HLS playlists (RFC 8216). URLs in the result are absolute. */
object HlsParser {

    fun looksLikePlaylist(text: String): Boolean = text.trimStart('﻿', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")

    fun parse(content: String, baseUrl: String): HlsPlaylist {
        val lines = content.removePrefix("﻿").lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || !lines[0].startsWith("#EXTM3U")) {
            throw HlsParseException("Not an HLS playlist (missing #EXTM3U)")
        }
        return if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            parseMaster(lines, baseUrl)
        } else {
            parseMedia(lines, baseUrl)
        }
    }

    private fun parseMaster(lines: List<String>, baseUrl: String): HlsPlaylist.Master {
        val variants = mutableListOf<HlsVariant>()
        val renditions = mutableListOf<HlsRendition>()
        var sessionKeyMethod: String? = null
        var pendingVariant: Map<String, String>? = null

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pendingVariant = parseAttributes(line.substringAfter(':'))
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = parseAttributes(line.substringAfter(':'))
                    renditions += HlsRendition(
                        type = a["TYPE"].orEmpty().uppercase(),
                        groupId = a["GROUP-ID"].orEmpty(),
                        name = a["NAME"],
                        language = a["LANGUAGE"],
                        url = a["URI"]?.let { UrlResolver.resolve(baseUrl, it) },
                        isDefault = a["DEFAULT"].equals("YES", ignoreCase = true),
                        autoSelect = a["AUTOSELECT"].equals("YES", ignoreCase = true),
                    )
                }
                line.startsWith("#EXT-X-SESSION-KEY:") -> {
                    val method = parseAttributes(line.substringAfter(':'))["METHOD"]
                    if (method != null && !method.equals("NONE", ignoreCase = true)) sessionKeyMethod = method
                }
                line.startsWith("#") -> Unit
                else -> {
                    val attributes = pendingVariant ?: continue
                    pendingVariant = null
                    val (width, height) = parseResolution(attributes["RESOLUTION"])
                    variants += HlsVariant(
                        url = UrlResolver.resolve(baseUrl, line),
                        bandwidth = attributes["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                        averageBandwidth = attributes["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0L,
                        width = width,
                        height = height,
                        frameRate = attributes["FRAME-RATE"]?.toDoubleOrNull() ?: 0.0,
                        codecs = attributes["CODECS"],
                        audioGroupId = attributes["AUDIO"],
                    )
                }
            }
        }
        if (variants.isEmpty()) throw HlsParseException("Master playlist without variants")
        return HlsPlaylist.Master(variants, renditions, sessionKeyMethod)
    }

    private fun parseMedia(lines: List<String>, baseUrl: String): HlsPlaylist.Media {
        val segments = mutableListOf<HlsSegment>()
        var targetDuration = 0.0
        var endList = false
        var playlistType: String? = null
        var encryptionMethod: String? = null
        var currentKeyMethod: String? = null
        var currentInit: InitSection? = null

        var pendingDuration = 0.0
        var pendingRange: Pair<Long, Long?>? = null
        var pendingDiscontinuity = false
        var pendingGap = false
        var lastRangeUrl: String? = null
        var lastRangeEnd = 0L

        for (line in lines) {
            when {
                line.startsWith("#EXTINF:") ->
                    pendingDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-TARGETDURATION:") ->
                    targetDuration = line.substringAfter(':').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-BYTERANGE:") -> pendingRange = parseByteRange(line.substringAfter(':'))
                line == "#EXT-X-DISCONTINUITY" -> pendingDiscontinuity = true
                line == "#EXT-X-GAP" -> pendingGap = true
                line == "#EXT-X-ENDLIST" -> endList = true
                line.startsWith("#EXT-X-PLAYLIST-TYPE:") -> playlistType = line.substringAfter(':').trim()
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = parseAttributes(line.substringAfter(':'))["METHOD"]
                    currentKeyMethod = if (method == null || method.equals("NONE", ignoreCase = true)) null else method
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = parseAttributes(line.substringAfter(':'))
                    val uri = a["URI"]
                    currentInit = if (uri == null) {
                        null
                    } else {
                        val range = a["BYTERANGE"]?.let { parseByteRange(it) }
                        InitSection(
                            url = UrlResolver.resolve(baseUrl, uri),
                            byteRange = range?.let { ByteRange(offset = it.second ?: 0L, length = it.first) },
                        )
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val url = UrlResolver.resolve(baseUrl, line)
                    var range: ByteRange? = null
                    val spec = pendingRange
                    if (spec != null) {
                        val start = spec.second ?: if (url == lastRangeUrl) lastRangeEnd else 0L
                        range = ByteRange(start, spec.first)
                        lastRangeUrl = url
                        lastRangeEnd = start + spec.first
                    }
                    if (currentKeyMethod != null && encryptionMethod == null) encryptionMethod = currentKeyMethod
                    segments += HlsSegment(
                        url = url,
                        durationSec = pendingDuration,
                        byteRange = range,
                        initSection = currentInit,
                        discontinuity = pendingDiscontinuity,
                        isGap = pendingGap,
                    )
                    pendingDuration = 0.0
                    pendingRange = null
                    pendingDiscontinuity = false
                    pendingGap = false
                }
            }
        }
        return HlsPlaylist.Media(segments, targetDuration, endList, playlistType, encryptionMethod)
    }

    /** Parses an attribute list: `KEY=value,KEY2="quoted, value"`. Keys are upper-cased. */
    fun parseAttributes(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var i = 0
        val n = text.length
        while (i < n) {
            while (i < n && (text[i] == ',' || text[i].isWhitespace())) i++
            val eq = text.indexOf('=', i)
            if (eq < 0) break
            val key = text.substring(i, eq).trim().uppercase()
            i = eq + 1
            val value: String
            if (i < n && text[i] == '"') {
                val close = text.indexOf('"', i + 1)
                if (close < 0) {
                    value = text.substring(i + 1)
                    i = n
                } else {
                    value = text.substring(i + 1, close)
                    i = close + 1
                }
            } else {
                val comma = text.indexOf(',', i)
                val end = if (comma < 0) n else comma
                value = text.substring(i, end).trim()
                i = end
            }
            if (key.isNotEmpty()) result[key] = value
        }
        return result
    }

    /** `length[@offset]` -> (length, offset or null). */
    private fun parseByteRange(spec: String): Pair<Long, Long?>? {
        val parts = spec.trim().split('@')
        val length = parts[0].trim().toLongOrNull() ?: return null
        val offset = parts.getOrNull(1)?.trim()?.toLongOrNull()
        return length to offset
    }

    private fun parseResolution(value: String?): Pair<Int, Int> {
        if (value == null) return 0 to 0
        val parts = value.lowercase().split('x')
        if (parts.size != 2) return 0 to 0
        return (parts[0].trim().toIntOrNull() ?: 0) to (parts[1].trim().toIntOrNull() ?: 0)
    }
}
