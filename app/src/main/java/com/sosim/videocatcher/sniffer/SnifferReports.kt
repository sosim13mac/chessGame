package com.sosim.videocatcher.sniffer

/** A `<source>` child of a video element. */
data class MediaSourceInfo(val url: String, val mime: String)

/** Sent by the detection script when a `<video>` element loads metadata or starts playing. */
data class MediaReport(
    /** "play" when playback started, "meta" when metadata loaded / DRM init data was seen. */
    val event: String,
    /** Absolute http(s) URL of the video, or "" for blob:/MediaSource playback. */
    val url: String,
    val isBlob: Boolean,
    val sources: List<MediaSourceInfo>,
    val width: Int,
    val height: Int,
    /** Seconds; -1 for live streams, 0 when unknown. */
    val durationSec: Double,
    val posterUrl: String,
    val drm: Boolean,
    /** Manifests (.m3u8 / .mpd) the same frame requested shortly before, newest last. */
    val manifests: List<String>,
    val frameUrl: String,
    val isTopFrame: Boolean,
    val title: String,
) {
    val isPlayEvent: Boolean get() = event == EVENT_PLAY

    companion object {
        const val EVENT_PLAY = "play"
        const val EVENT_META = "meta"
    }
}

/** Sent by the detection script when fetch()/XMLHttpRequest loads something video-like. */
data class NetworkReport(
    val url: String,
    val mime: String,
    val frameUrl: String,
    val title: String,
)
