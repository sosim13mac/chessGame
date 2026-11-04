package com.sosim.videocatcher.download

import com.sosim.videocatcher.hls.ByteRange
import com.sosim.videocatcher.hls.HlsParseException
import com.sosim.videocatcher.hls.HlsParser
import com.sosim.videocatcher.hls.HlsPlaylist
import com.sosim.videocatcher.hls.InitSection
import com.sosim.videocatcher.net.CancelledException
import com.sosim.videocatcher.net.HttpException
import com.sosim.videocatcher.net.HttpFetcher
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class HlsDownloadException(
    val reason: Reason,
    message: String? = null,
    cause: Throwable? = null,
) : IOException(message ?: reason.name, cause) {
    enum class Reason { NOT_HLS, ENCRYPTED, LIVE, EMPTY, NETWORK, CANCELLED }
}

/** The playlists to download: video (possibly with muxed audio) and an optional separate audio track. */
class HlsStream(val video: HlsPlaylist.Media, val audio: HlsPlaylist.Media?)

/**
 * Downloads an HLS stream: every segment is fetched (several in parallel)
 * and the segments are joined, in order, into a single file.
 * Encrypted (DRM / AES) and live streams are refused.
 */
class HlsDownloader(
    private val fetcher: HttpFetcher,
    private val workDir: File,
    private val parallelism: Int = 4,
    private val isCancelled: () -> Boolean = { false },
) {

    /**
     * Loads [url]. For a master playlist the best variant is chosen together with
     * its separate audio rendition; [audioUrl] is used for a media playlist whose
     * audio lives in another playlist.
     */
    fun resolve(url: String, audioUrl: String?): HlsStream {
        return when (val playlist = load(url)) {
            is HlsPlaylist.Master -> {
                if (playlist.isEncrypted) throw HlsDownloadException(HlsDownloadException.Reason.ENCRYPTED, playlist.sessionKeyMethod)
                val best = playlist.bestVariant ?: throw HlsDownloadException(HlsDownloadException.Reason.EMPTY)
                val audio = playlist.audioFor(best)?.url ?: audioUrl
                HlsStream(checked(loadMedia(best.url)), audio?.let { checked(loadMedia(it)) })
            }
            is HlsPlaylist.Media -> HlsStream(checked(playlist), audioUrl?.let { checked(loadMedia(it)) })
        }
    }

    /**
     * Downloads all segments of [playlist] and joins them into `workDir/<name>.<ext>`.
     * [onSegmentDone] runs on a worker thread after each segment with its size in bytes.
     */
    fun download(playlist: HlsPlaylist.Media, name: String, onSegmentDone: (Long) -> Unit): File {
        val segments = playlist.segments.filter { !it.isGap }
        if (segments.isEmpty()) throw HlsDownloadException(HlsDownloadException.Reason.EMPTY)
        val dir = File(workDir, "$name-parts")
        dir.mkdirs()

        val initFiles = LinkedHashMap<InitSection, File>()
        segments.mapNotNull { it.initSection }.distinct().forEachIndexed { index, init ->
            val file = File(dir, "init-$index")
            fetchWithRetry(init.url, init.byteRange, file)
            initFiles[init] = file
        }

        val segmentFiles = segments.indices.map { File(dir, "seg-" + it.toString().padStart(6, '0')) }
        val pool = Executors.newFixedThreadPool(parallelism.coerceIn(1, 8))
        try {
            val futures = segments.mapIndexed { index, segment ->
                pool.submit(Callable {
                    fetchWithRetry(segment.url, segment.byteRange, segmentFiles[index])
                    onSegmentDone(segmentFiles[index].length())
                })
            }
            for (future in futures) await(future)
        } finally {
            pool.shutdownNow()
        }

        val extension = if (playlist.usesFmp4) "mp4" else detectExtension(segmentFiles.first())
        val output = File(workDir, "$name.$extension")
        BufferedOutputStream(FileOutputStream(output), JOIN_BUFFER).use { out ->
            var lastInit: InitSection? = null
            segments.forEachIndexed { index, segment ->
                checkCancelled()
                val init = segment.initSection
                if (init != null && init != lastInit) {
                    FileInputStream(initFiles.getValue(init)).use { it.copyTo(out, JOIN_BUFFER) }
                    lastInit = init
                }
                appendSegment(segmentFiles[index], out)
                segmentFiles[index].delete()
            }
        }
        dir.deleteRecursively()
        return output
    }

    private fun load(url: String): HlsPlaylist {
        checkCancelled()
        val text = try {
            fetcher.fetchText(url)
        } catch (e: CancelledException) {
            throw HlsDownloadException(HlsDownloadException.Reason.CANCELLED)
        } catch (e: IOException) {
            throw HlsDownloadException(HlsDownloadException.Reason.NETWORK, e.message, e)
        }
        return try {
            HlsParser.parse(text.body, text.finalUrl)
        } catch (e: HlsParseException) {
            throw HlsDownloadException(HlsDownloadException.Reason.NOT_HLS, e.message, e)
        }
    }

    private fun loadMedia(url: String): HlsPlaylist.Media = when (val playlist = load(url)) {
        is HlsPlaylist.Media -> playlist
        is HlsPlaylist.Master -> {
            val best = playlist.bestVariant ?: throw HlsDownloadException(HlsDownloadException.Reason.EMPTY)
            load(best.url) as? HlsPlaylist.Media
                ?: throw HlsDownloadException(HlsDownloadException.Reason.NOT_HLS, "Nested master playlist")
        }
    }

    private fun checked(media: HlsPlaylist.Media): HlsPlaylist.Media {
        if (media.isEncrypted) throw HlsDownloadException(HlsDownloadException.Reason.ENCRYPTED, media.encryptionMethod)
        if (media.isLive) throw HlsDownloadException(HlsDownloadException.Reason.LIVE)
        if (media.segments.none { !it.isGap }) throw HlsDownloadException(HlsDownloadException.Reason.EMPTY)
        return media
    }

    private fun fetchWithRetry(url: String, range: ByteRange?, dest: File) {
        var attempt = 0
        while (true) {
            checkCancelled()
            try {
                fetcher.downloadTo(url, range, dest, isCancelled)
                return
            } catch (e: CancelledException) {
                throw HlsDownloadException(HlsDownloadException.Reason.CANCELLED)
            } catch (e: HttpException) {
                attempt++
                if (e.statusCode == 404 || e.statusCode == 410 || attempt >= MAX_ATTEMPTS) {
                    throw HlsDownloadException(HlsDownloadException.Reason.NETWORK, "${e.message}: $url", e)
                }
            } catch (e: IOException) {
                attempt++
                if (attempt >= MAX_ATTEMPTS) throw HlsDownloadException(HlsDownloadException.Reason.NETWORK, e.message, e)
            }
            try {
                Thread.sleep(RETRY_DELAY_MS * attempt)
            } catch (e: InterruptedException) {
                throw HlsDownloadException(HlsDownloadException.Reason.CANCELLED)
            }
        }
    }

    private fun await(future: Future<*>) {
        while (true) {
            checkCancelled()
            try {
                future.get(POLL_MS, TimeUnit.MILLISECONDS)
                return
            } catch (e: TimeoutException) {
                // still running; poll again so cancellation stays responsive
            } catch (e: ExecutionException) {
                val cause = e.cause
                throw cause as? HlsDownloadException
                    ?: HlsDownloadException(HlsDownloadException.Reason.NETWORK, cause?.message, cause)
            }
        }
    }

    private fun checkCancelled() {
        if (isCancelled()) throw HlsDownloadException(HlsDownloadException.Reason.CANCELLED)
    }

    private fun appendSegment(file: File, out: OutputStream) {
        val skip = id3TagSize(file)
        BufferedInputStream(FileInputStream(file), JOIN_BUFFER).use { input ->
            HttpFetcher.skipFully(input, skip)
            input.copyTo(out, JOIN_BUFFER)
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 800L
        private const val POLL_MS = 300L
        private const val JOIN_BUFFER = 256 * 1024

        /**
         * Size of a leading ID3v2 tag, or 0. "Packed audio" segments (.aac) start
         * with one; removing them lets the joined file be read as a single stream.
         */
        fun id3TagSize(file: File): Long {
            val header = readHead(file, 10)
            if (header.size < 10) return 0
            if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return 0
            val size = ((header[6].toInt() and 0x7f) shl 21) or
                ((header[7].toInt() and 0x7f) shl 14) or
                ((header[8].toInt() and 0x7f) shl 7) or
                (header[9].toInt() and 0x7f)
            val footer = if ((header[5].toInt() and 0x10) != 0) 10 else 0
            return (10L + size + footer).coerceAtMost(file.length())
        }

        /** File extension matching the segment container: ts, aac or mp4. */
        fun detectExtension(firstSegment: File): String {
            val head = readHead(firstSegment, 12)
            if (head.isNotEmpty() && head[0] == 0x47.toByte()) return "ts"
            if (head.size >= 8) {
                val box = String(head, 4, 4, Charsets.ISO_8859_1)
                if (box == "ftyp" || box == "styp" || box == "moof" || box == "sidx") return "mp4"
            }
            if (head.size >= 3 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) return "aac"
            if (head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xF0) == 0xF0) return "aac"
            return "ts"
        }

        private fun readHead(file: File, count: Int): ByteArray {
            if (!file.exists()) return ByteArray(0)
            FileInputStream(file).use { input ->
                val buffer = ByteArray(count)
                var total = 0
                while (total < count) {
                    val n = input.read(buffer, total, count - total)
                    if (n < 0) break
                    total += n
                }
                return buffer.copyOf(total)
            }
        }
    }
}
