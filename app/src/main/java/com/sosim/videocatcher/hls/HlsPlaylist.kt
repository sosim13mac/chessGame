package com.sosim.videocatcher.hls

/** A byte sub-range of a resource (EXT-X-BYTERANGE). */
data class ByteRange(val offset: Long, val length: Long) {
    val endInclusive: Long get() = offset + length - 1
}

/** Media initialization section (EXT-X-MAP), used by fragmented-MP4 streams. */
data class InitSection(val url: String, val byteRange: ByteRange?)

data class HlsSegment(
    val url: String,
    val durationSec: Double,
    val byteRange: ByteRange?,
    val initSection: InitSection?,
    val discontinuity: Boolean,
    val isGap: Boolean,
)

data class HlsVariant(
    val url: String,
    val bandwidth: Long,
    val averageBandwidth: Long,
    val width: Int,
    val height: Int,
    val frameRate: Double,
    val codecs: String?,
    val audioGroupId: String?,
)

data class HlsRendition(
    val type: String,
    val groupId: String,
    val name: String?,
    val language: String?,
    val url: String?,
    val isDefault: Boolean,
    val autoSelect: Boolean,
)

sealed class HlsPlaylist {

    /** A master (multivariant) playlist: a list of qualities. */
    data class Master(
        val variants: List<HlsVariant>,
        val renditions: List<HlsRendition>,
        /** METHOD of an EXT-X-SESSION-KEY other than NONE, if any. */
        val sessionKeyMethod: String?,
    ) : HlsPlaylist() {

        val isEncrypted: Boolean get() = sessionKeyMethod != null

        /** Variants ordered best first (resolution, then bandwidth). */
        val sortedVariants: List<HlsVariant>
            get() = variants.sortedWith(
                compareByDescending<HlsVariant> { it.height }.thenByDescending { it.bandwidth }
            )

        val bestVariant: HlsVariant? get() = sortedVariants.firstOrNull()

        val maxHeight: Int get() = variants.maxOfOrNull { it.height } ?: 0

        /** URLs of every playlist this master points to (variants and renditions). */
        val childUrls: Set<String>
            get() = (variants.map { it.url } + renditions.mapNotNull { it.url }).toSet()

        /** The separate audio playlist a variant needs, if its audio is not muxed in. */
        fun audioFor(variant: HlsVariant): HlsRendition? {
            val group = variant.audioGroupId ?: return null
            val candidates = renditions.filter {
                it.type == "AUDIO" && it.groupId == group && it.url != null && it.url != variant.url
            }
            return candidates.firstOrNull { it.isDefault }
                ?: candidates.firstOrNull { it.autoSelect }
                ?: candidates.firstOrNull()
        }
    }

    /** A media playlist: the actual list of segments of one stream. */
    data class Media(
        val segments: List<HlsSegment>,
        val targetDurationSec: Double,
        val isEndList: Boolean,
        val playlistType: String?,
        /** METHOD of the first EXT-X-KEY other than NONE, if any segment is encrypted. */
        val encryptionMethod: String?,
    ) : HlsPlaylist() {
        val isEncrypted: Boolean get() = encryptionMethod != null
        val isLive: Boolean get() = !isEndList && !playlistType.equals("VOD", ignoreCase = true)
        val usesFmp4: Boolean get() = segments.any { it.initSection != null }
        val totalDurationSec: Double get() = segments.sumOf { it.durationSec }
    }
}

class HlsParseException(message: String) : Exception(message)

/** Result of inspecting a detected .m3u8 URL. */
sealed class HlsProbe {
    data class Master(val playlist: HlsPlaylist.Master, val finalUrl: String) : HlsProbe()
    data class Media(val playlist: HlsPlaylist.Media, val finalUrl: String) : HlsProbe()
    data class Failed(val message: String) : HlsProbe()
}
