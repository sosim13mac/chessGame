package com.sosim.videocatcher.ui

import android.content.Context
import com.sosim.videocatcher.R
import com.sosim.videocatcher.hls.HlsProbe
import com.sosim.videocatcher.sniffer.DetectedVideo
import com.sosim.videocatcher.sniffer.MediaKind
import com.sosim.videocatcher.sniffer.MediaUrlClassifier
import com.sosim.videocatcher.util.Formatters

/** Texts shown for a detected video. */
object VideoLabels {

    private val KNOWN_EXTENSIONS = setOf("mp4", "m4v", "webm", "mov", "mkv", "3gp", "ogv", "flv", "avi", "wmv", "mpg", "mpeg", "ts")

    fun typeLabel(video: DetectedVideo): String = when (video.kind) {
        MediaKind.HLS -> "HLS"
        MediaKind.DASH -> "DASH"
        MediaKind.MSE -> "MSE"
        MediaKind.DIRECT -> {
            val ext = MediaUrlClassifier.extensionOf(video.url)?.takeIf { it in KNOWN_EXTENSIONS }
                ?: video.mimeType?.substringAfter('/', "")?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
            (ext ?: "video").uppercase()
        }
    }

    fun title(video: DetectedVideo): String {
        if (video.pageTitle.isNotBlank()) return video.pageTitle
        val segment = MediaUrlClassifier.lastPathSegment(video.url)?.substringBeforeLast('.')
        return segment?.takeIf { it.isNotBlank() } ?: Formatters.host(video.url).ifEmpty { "video" }
    }

    fun details(context: Context, video: DetectedVideo, isPlaying: Boolean): String {
        val parts = ArrayList<String>()
        if (isPlaying) parts += context.getString(R.string.label_playing)
        val probe = video.hlsProbe
        if (probe is HlsProbe.Master) {
            val maxHeight = probe.playlist.maxHeight
            if (maxHeight > 0) parts += context.getString(R.string.label_max_quality, maxHeight)
            parts += context.getString(R.string.label_quality_count, probe.playlist.variants.size)
        } else {
            Formatters.resolution(video.width, video.height).takeIf { it.isNotEmpty() }?.let { parts += it }
        }
        Formatters.duration(video.durationSec).takeIf { it.isNotEmpty() }?.let { parts += it }
        status(context, video)?.let { parts += it }
        if (video.kind != MediaKind.MSE) Formatters.host(video.url).takeIf { it.isNotEmpty() }?.let { parts += it }
        return parts.joinToString(" · ")
    }

    /** Why a video cannot be downloaded (or what is happening to it), or null. */
    fun status(context: Context, video: DetectedVideo): String? = when {
        video.drmProtected -> context.getString(R.string.label_drm)
        video.isEncrypted -> context.getString(R.string.label_encrypted)
        video.isLive -> context.getString(R.string.label_live)
        video.kind == MediaKind.DASH -> context.getString(R.string.label_dash)
        video.kind == MediaKind.MSE -> context.getString(R.string.label_mse)
        video.probing -> context.getString(R.string.label_analyzing)
        video.hlsProbe is HlsProbe.Failed -> context.getString(R.string.label_probe_failed)
        else -> null
    }
}
