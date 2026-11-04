package com.sosim.videocatcher.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Copies the audio/video samples of downloaded streams (MPEG-TS, fragmented MP4,
 * ADTS audio) into a regular MP4 file without re-encoding, using the platform's
 * MediaExtractor and MediaMuxer. Several inputs can be combined, e.g. a video-only
 * stream with a separate audio stream.
 */
object MediaRemuxer {

    private val SUPPORTED_VIDEO = setOf("video/avc", "video/hevc", "video/mp4v-es", "video/3gpp")
    private val SUPPORTED_AUDIO = setOf("audio/mp4a-latm", "audio/3gpp", "audio/amr-wb")

    private const val DEFAULT_BUFFER_SIZE = 8 * 1024 * 1024

    private class Input(val extractor: MediaExtractor) {
        /** extractor track index -> muxer track index */
        val tracks = HashMap<Int, Int>()
    }

    /** Throws on any problem (unsupported codec, broken timestamps, ...); the caller keeps the original file. */
    fun remuxToMp4(inputs: List<File>, output: File, isCancelled: () -> Boolean) {
        val opened = ArrayList<Input>()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val formats = ArrayList<Triple<Input, Int, MediaFormat>>()
            var hasVideo = false
            var hasAudio = false
            var sawUnsupportedAudio = false
            var bufferSize = DEFAULT_BUFFER_SIZE
            for (file in inputs) {
                val extractor = MediaExtractor()
                val input = Input(extractor)
                opened += input
                extractor.setDataSource(file.absolutePath)
                for (track in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(track)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    when {
                        mime.startsWith("video/") && !hasVideo && mime in SUPPORTED_VIDEO -> hasVideo = true
                        mime.startsWith("audio/") && !hasAudio && mime in SUPPORTED_AUDIO -> hasAudio = true
                        mime.startsWith("audio/") && mime !in SUPPORTED_AUDIO -> {
                            sawUnsupportedAudio = true
                            continue
                        }
                        else -> continue
                    }
                    extractor.selectTrack(track)
                    formats += Triple(input, track, format)
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                }
            }
            if (!hasVideo) throw IOException("No supported video track")
            if (!hasAudio && sawUnsupportedAudio) throw IOException("Unsupported audio codec")

            val mp4 = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mp4
            for ((input, track, format) in formats) input.tracks[track] = mp4.addTrack(format)
            mp4.start()
            started = true

            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val info = MediaCodec.BufferInfo()
            val active = opened.filter { it.tracks.isNotEmpty() }
            var samples = 0L
            while (true) {
                // Interleave the inputs by timestamp.
                var next: Input? = null
                var nextTime = Long.MAX_VALUE
                for (input in active) {
                    if (input.extractor.sampleTrackIndex < 0) continue
                    val time = input.extractor.sampleTime
                    if (time < nextTime) {
                        nextTime = time
                        next = input
                    }
                }
                val input = next ?: break
                val extractor = input.extractor
                val outTrack = input.tracks[extractor.sampleTrackIndex]
                val size = extractor.readSampleData(buffer, 0)
                if (outTrack != null && size > 0) {
                    val keyFrame = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    info.set(0, size, maxOf(0L, extractor.sampleTime), if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mp4.writeSampleData(outTrack, buffer, info)
                }
                extractor.advance()
                if (++samples % 500 == 0L && isCancelled()) throw IOException("Cancelled")
            }
            mp4.stop()
            started = false
        } finally {
            if (started) {
                try {
                    muxer?.stop()
                } catch (e: Exception) {
                    // already failing
                }
            }
            try {
                muxer?.release()
            } catch (e: Exception) {
                // ignore
            }
            for (input in opened) input.extractor.release()
        }
    }
}
