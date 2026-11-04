package com.sosim.videocatcher.download

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import com.sosim.videocatcher.hls.HlsParseException
import com.sosim.videocatcher.hls.HlsParser
import com.sosim.videocatcher.hls.HlsPlaylist
import com.sosim.videocatcher.hls.HlsProbe
import com.sosim.videocatcher.net.HttpFetcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Inspects detected URLs in the background; results are delivered on the main thread. */
class MediaProber {

    /** What a URL turned out to be when its type could not be told from the URL itself. */
    sealed class SniffResult {
        data class Playlist(val probe: HlsProbe) : SniffResult()
        data class File(val contentType: String?) : SniffResult()
        data class Failed(val message: String) : SniffResult()
    }

    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Downloads and parses an HLS playlist. */
    fun probeHls(url: String, headers: Map<String, String>, callback: (HlsProbe) -> Unit) {
        submit(callback) {
            try {
                val text = fetcher(headers).fetchText(url)
                toProbe(HlsParser.parse(text.body, text.finalUrl), text.finalUrl)
            } catch (e: HlsParseException) {
                HlsProbe.Failed(e.message ?: "Invalid playlist")
            } catch (e: Exception) {
                HlsProbe.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Finds out whether [url] is an HLS playlist or a regular file. */
    fun sniff(url: String, headers: Map<String, String>, callback: (SniffResult) -> Unit) {
        submit(callback) {
            try {
                val text = fetcher(headers).sniff(url)
                if (HlsParser.looksLikePlaylist(text.body)) {
                    SniffResult.Playlist(toProbe(HlsParser.parse(text.body, text.finalUrl), text.finalUrl))
                } else {
                    SniffResult.File(text.contentType)
                }
            } catch (e: Exception) {
                SniffResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun <T> submit(callback: (T) -> Unit, work: () -> T) {
        if (executor.isShutdown) return
        executor.execute {
            val result = work()
            mainHandler.post { callback(result) }
        }
    }

    private fun toProbe(playlist: HlsPlaylist, finalUrl: String): HlsProbe = when (playlist) {
        is HlsPlaylist.Master -> HlsProbe.Master(playlist, finalUrl)
        is HlsPlaylist.Media -> HlsProbe.Media(playlist, finalUrl)
    }

    private fun fetcher(headers: Map<String, String>) =
        HttpFetcher(headers, cookieProvider = { CookieManager.getInstance().getCookie(it) })
}
