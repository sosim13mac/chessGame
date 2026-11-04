package com.sosim.videocatcher.util

import java.util.Locale
import kotlin.math.roundToLong

object Formatters {

    fun duration(seconds: Double): String {
        if (seconds.isNaN() || seconds.isInfinite() || seconds <= 0.0) return ""
        val total = seconds.roundToLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }

    fun resolution(width: Int, height: Int): String =
        if (width > 0 && height > 0) "${width}×${height}" else ""

    fun bitrate(bitsPerSecond: Long): String = when {
        bitsPerSecond >= 1_000_000 -> String.format(Locale.ROOT, "%.1f Mbps", bitsPerSecond / 1_000_000.0)
        bitsPerSecond >= 1_000 -> "${bitsPerSecond / 1_000} kbps"
        bitsPerSecond > 0 -> "$bitsPerSecond bps"
        else -> ""
    }

    fun bytes(count: Long): String = when {
        count >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GB", count / (1L shl 30).toDouble())
        count >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", count / (1L shl 20).toDouble())
        count >= 1L shl 10 -> String.format(Locale.ROOT, "%.0f KB", count / (1L shl 10).toDouble())
        count >= 0 -> "$count B"
        else -> ""
    }

    fun host(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        return afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
    }
}
