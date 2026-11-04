package com.sosim.videocatcher.sniffer

import com.sosim.videocatcher.hls.HlsProbe

/** A video found on the current page. Mutated only on the main thread. */
class DetectedVideo(
    val id: Long,
    /** Download URL: the file itself, or the playlist / manifest for streams. */
    val url: String,
    val kind: MediaKind,
    val detectedAt: Long,
) {
    /** URL of the frame that plays the video; sent as Referer when downloading. */
    var pageUrl: String = ""
    var pageTitle: String = ""
    var mimeType: String? = null
    var width: Int = 0
    var height: Int = 0
    /** Seconds; -1 for live streams, 0 when unknown. */
    var durationSec: Double = 0.0
    var posterUrl: String? = null
    /** True when a `<video>` element reported it (not only seen on the network). */
    var fromElement: Boolean = false
    /** When the video last started playing (0 = never seen playing). */
    var lastPlayedAt: Long = 0L
    var drmProtected: Boolean = false
    /** Headers of the original request, captured by WebViewClient.shouldInterceptRequest. */
    var requestHeaders: Map<String, String> = emptyMap()
    /** Result of inspecting an HLS playlist (null until inspected). */
    var hlsProbe: HlsProbe? = null
    var probing: Boolean = false

    val isLive: Boolean
        get() = durationSec < 0 || (hlsProbe as? HlsProbe.Media)?.playlist?.isLive == true

    val isEncrypted: Boolean
        get() = when (val probe = hlsProbe) {
            is HlsProbe.Master -> probe.playlist.isEncrypted
            is HlsProbe.Media -> probe.playlist.isEncrypted
            else -> false
        }

    /** Whether the app can try to download it. */
    val isDownloadable: Boolean
        get() = !drmProtected && when (kind) {
            MediaKind.DIRECT -> true
            MediaKind.HLS -> !isEncrypted && (hlsProbe as? HlsProbe.Media)?.playlist?.isLive != true
            MediaKind.DASH, MediaKind.MSE -> false
        }
}
