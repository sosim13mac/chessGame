package com.sosim.videocatcher.sniffer

import com.sosim.videocatcher.hls.HlsProbe

/**
 * Collects the videos detected on the current page from two sources:
 * the injected script (`<video>` playback, fetch/XHR) and WebView's
 * network interception. It merges duplicates and hides noise such as the
 * per-quality sub-playlists of an HLS stream or the segments of a stream.
 *
 * Not thread-safe: use it from the main thread only.
 */
class VideoStore(private val clock: () -> Long = { System.currentTimeMillis() }) {

    private val items = LinkedHashMap<String, DetectedVideo>()
    private val hlsChildKeys = HashSet<String>()
    private var nextId = 1L

    /** Called after every change. */
    var onChanged: (() -> Unit)? = null

    val size: Int get() = items.size

    fun clear() {
        if (items.isEmpty() && hlsChildKeys.isEmpty()) return
        items.clear()
        hlsChildKeys.clear()
        changed()
    }

    fun find(url: String): DetectedVideo? = items[keyOf(url)]

    fun findById(id: Long): DetectedVideo? = items.values.firstOrNull { it.id == id }

    /** Videos to show, the one that played most recently first. */
    fun visibleItems(): List<DetectedVideo> {
        val groupSizes = HashMap<String, Int>()
        for (video in items.values) {
            if (video.kind == MediaKind.DIRECT && !video.fromElement) {
                val key = MediaUrlClassifier.directoryKey(video.url)
                groupSizes[key] = (groupSizes[key] ?: 0) + 1
            }
        }
        val visible = items.values.filter { video ->
            when (video.kind) {
                MediaKind.HLS -> !isHlsChild(video)
                // Many "files" from one folder that no <video> played are stream segments.
                MediaKind.DIRECT -> video.fromElement ||
                    (groupSizes[MediaUrlClassifier.directoryKey(video.url)] ?: 0) < SEGMENT_GROUP_THRESHOLD
                MediaKind.DASH, MediaKind.MSE -> true
            }
        }
        val hasManifest = visible.any { it.kind == MediaKind.HLS || it.kind == MediaKind.DASH }
        return visible
            .filter { !(it.kind == MediaKind.MSE && hasManifest) }
            .sortedWith(DISPLAY_ORDER)
    }

    /** HLS entries that still need to be inspected. */
    fun pendingHlsProbes(): List<DetectedVideo> =
        items.values.filter { it.kind == MediaKind.HLS && it.hlsProbe == null && !it.probing && !isHlsChild(it) }

    /**
     * Handles a `<video>` report. Returns the entry that represents the video,
     * or null when the report carries nothing usable.
     */
    fun onMediaReport(report: MediaReport, pageTitle: String): DetectedVideo? {
        val now = clock()
        val entry: DetectedVideo
        if (!report.isBlob) {
            val url = report.url.takeIf { MediaUrlClassifier.isHttpUrl(it) }
                ?: report.sources.firstOrNull { MediaUrlClassifier.isHttpUrl(it.url) }?.url
                ?: return null
            val mime = report.sources.firstOrNull { it.url == url }?.mime?.takeIf { it.isNotBlank() }
            // A <video> element plays it, so anything unrecognised is a direct video file.
            val kind = MediaUrlClassifier.classify(url, mime) ?: MediaKind.DIRECT
            entry = upsert(url, kind, now)
            if (mime != null) entry.mimeType = mime
        } else {
            // MediaSource playback: the downloadable thing is the manifest the player loaded.
            entry = findManifestFor(report) ?: upsert(MSE_PREFIX + report.frameUrl, MediaKind.MSE, now)
        }
        entry.fromElement = true
        applyReport(entry, report, pageTitle, now)
        changed()
        return entry
    }

    /** Handles a media-like request seen by the script (fetch/XHR) or by WebView's interception. */
    fun onNetworkMedia(
        url: String,
        mime: String?,
        referer: String?,
        headers: Map<String, String>,
        pageTitle: String,
    ): DetectedVideo? {
        val kind = MediaUrlClassifier.classify(url, mime) ?: return null
        val isNew = find(url) == null
        val entry = upsert(url, kind, clock())
        if (entry.mimeType == null && !mime.isNullOrBlank()) entry.mimeType = mime.substringBefore(';').trim()
        if (entry.pageUrl.isEmpty() && !referer.isNullOrBlank()) entry.pageUrl = referer
        if (entry.pageTitle.isEmpty() && pageTitle.isNotBlank()) entry.pageTitle = pageTitle.trim()
        if (entry.requestHeaders.isEmpty() && headers.isNotEmpty()) entry.requestHeaders = headers
        if (isNew && (kind == MediaKind.HLS || kind == MediaKind.DASH)) adoptPlaceholder(entry)
        changed()
        return entry
    }

    /** Stores the result of inspecting an HLS playlist. */
    fun applyHlsProbe(url: String, probe: HlsProbe) {
        val entry = find(url) ?: return
        entry.probing = false
        entry.hlsProbe = probe
        when (probe) {
            is HlsProbe.Master -> {
                // The quality sub-playlists are hidden behind their master playlist.
                val children = probe.playlist.childUrls.map(::keyOf)
                hlsChildKeys.addAll(children)
                for (key in children) {
                    val child = items[key]
                    if (child != null && child !== entry) transfer(child, entry)
                }
            }
            is HlsProbe.Media -> {
                val total = probe.playlist.totalDurationSec
                if (entry.durationSec == 0.0 && total > 0 && !probe.playlist.isLive) entry.durationSec = total
            }
            is HlsProbe.Failed -> Unit
        }
        changed()
    }

    private fun findManifestFor(report: MediaReport): DetectedVideo? {
        for (url in report.manifests.asReversed()) {
            val entry = items[keyOf(url)] ?: continue
            return masterOf(entry) ?: entry
        }
        return items.values
            .filter { (it.kind == MediaKind.HLS && !isHlsChild(it)) || it.kind == MediaKind.DASH }
            .maxByOrNull { it.detectedAt }
    }

    private fun masterOf(entry: DetectedVideo): DetectedVideo? {
        val key = keyOf(entry.url)
        if (key !in hlsChildKeys) return null
        return items.values.firstOrNull { candidate ->
            val probe = candidate.hlsProbe as? HlsProbe.Master
            probe != null && probe.playlist.childUrls.any { keyOf(it) == key }
        }
    }

    private fun isHlsChild(video: DetectedVideo): Boolean =
        video.kind == MediaKind.HLS && video.hlsProbe !is HlsProbe.Master && keyOf(video.url) in hlsChildKeys

    /** A MediaSource video played before its manifest was seen: move its state to the manifest. */
    private fun adoptPlaceholder(manifest: DetectedVideo) {
        val placeholder = items.values.filter { it.kind == MediaKind.MSE }.maxByOrNull { it.lastPlayedAt } ?: return
        transfer(placeholder, manifest)
        items.remove(keyOf(placeholder.url))
    }

    private fun transfer(from: DetectedVideo, to: DetectedVideo) {
        if (from.lastPlayedAt > to.lastPlayedAt) to.lastPlayedAt = from.lastPlayedAt
        if (from.fromElement) to.fromElement = true
        if (to.width == 0 && from.width > 0) {
            to.width = from.width
            to.height = from.height
        }
        if (to.durationSec == 0.0) to.durationSec = from.durationSec
        if (to.posterUrl == null) to.posterUrl = from.posterUrl
        if (to.pageTitle.isEmpty()) to.pageTitle = from.pageTitle
        if (to.pageUrl.isEmpty()) to.pageUrl = from.pageUrl
        if (to.requestHeaders.isEmpty()) to.requestHeaders = from.requestHeaders
        if (from.drmProtected) to.drmProtected = true
    }

    private fun applyReport(entry: DetectedVideo, report: MediaReport, pageTitle: String, now: Long) {
        if (report.width > 0 && report.height > 0) {
            entry.width = report.width
            entry.height = report.height
        }
        if (report.durationSec != 0.0) entry.durationSec = report.durationSec
        if (report.posterUrl.isNotEmpty()) entry.posterUrl = report.posterUrl
        if (MediaUrlClassifier.isHttpUrl(report.frameUrl)) entry.pageUrl = report.frameUrl
        val title = if (report.isTopFrame && report.title.isNotBlank()) report.title else pageTitle.ifBlank { report.title }
        if (title.isNotBlank()) entry.pageTitle = title.trim()
        if (report.drm) entry.drmProtected = true
        if (report.isPlayEvent) entry.lastPlayedAt = now
    }

    private fun upsert(url: String, kind: MediaKind, now: Long): DetectedVideo {
        val key = keyOf(url)
        items[key]?.let { return it }
        if (items.size >= MAX_ITEMS) evictOne()
        val video = DetectedVideo(nextId++, url, kind, now)
        items[key] = video
        return video
    }

    private fun evictOne() {
        val victim = items.values.filter { !it.fromElement }.minByOrNull { it.detectedAt }
            ?: items.values.minByOrNull { it.lastPlayedAt }
            ?: return
        items.remove(keyOf(victim.url))
    }

    private fun changed() {
        onChanged?.invoke()
    }

    companion object {
        const val MSE_PREFIX = "mse:"
        const val MAX_ITEMS = 300
        const val SEGMENT_GROUP_THRESHOLD = 3

        private val DISPLAY_ORDER = compareByDescending<DetectedVideo> { it.lastPlayedAt }
            .thenByDescending { it.fromElement }
            .thenByDescending { it.detectedAt }

        fun keyOf(url: String): String = url.substringBefore('#')
    }
}
