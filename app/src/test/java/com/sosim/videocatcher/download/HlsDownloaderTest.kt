package com.sosim.videocatcher.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sosim.videocatcher.net.HttpFetcher
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class HlsDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val routes = ConcurrentHashMap<String, (HttpExchange) -> Unit>()
    private val requestHeaders = ConcurrentHashMap<String, String>()

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            exchange.requestHeaders.getFirst("Referer")?.let { requestHeaders["Referer"] = it }
            val handler = routes[path]
            if (handler == null) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            } else {
                handler(exchange)
            }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stopServer() = server.stop(0)

    private fun serve(path: String, body: ByteArray, contentType: String = "application/octet-stream", honorRange: Boolean = true) {
        routes[path] = { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            exchange.responseHeaders.add("Content-Type", contentType)
            if (range != null && honorRange) {
                val (start, end) = range.removePrefix("bytes=").split('-').map { it.toInt() }
                val slice = body.copyOfRange(start, end + 1)
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${body.size}")
                exchange.sendResponseHeaders(206, slice.size.toLong())
                exchange.responseBody.use { it.write(slice) }
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
    }

    private fun serveText(path: String, text: String) = serve(path, text.toByteArray(), "application/vnd.apple.mpegurl")

    private fun bytes(seed: Int, size: Int) = ByteArray(size) { ((it * 31 + seed * 7) and 0xff).toByte() }

    /** A fake MPEG-TS segment: starts with the 0x47 sync byte. */
    private fun tsSegment(seed: Int, size: Int = 1880) = bytes(seed, size).also { it[0] = 0x47 }

    private fun downloader(cancelled: () -> Boolean = { false }) = HlsDownloader(
        HttpFetcher(mapOf("Referer" to "https://site.example/page", "User-Agent" to "test")),
        temp.newFolder(),
        parallelism = 3,
        isCancelled = cancelled,
    )

    @Test
    fun downloadsAndJoinsTsSegmentsInOrder() {
        val segments = (0 until 7).map { tsSegment(it, 1880 + it * 188) }
        val playlist = buildString {
            append("#EXTM3U\n#EXT-X-TARGETDURATION:4\n")
            segments.indices.forEach { append("#EXTINF:4.0,\nseg$it.ts\n") }
            append("#EXT-X-ENDLIST\n")
        }
        serveText("/v/index.m3u8", playlist)
        segments.forEachIndexed { i, data -> serve("/v/seg$i.ts", data) }

        val dl = downloader()
        val stream = dl.resolve("$base/v/index.m3u8", null)
        assertNull(stream.audio)
        val calls = AtomicInteger()
        val output = dl.download(stream.video, "video") { calls.incrementAndGet() }

        assertEquals("ts", output.extension)
        assertEquals(7, calls.get())
        val expected = ByteArrayOutputStream().apply { segments.forEach { write(it) } }.toByteArray()
        assertArrayEquals(expected, output.readBytes())
        assertEquals("https://site.example/page", requestHeaders["Referer"])
    }

    @Test
    fun followsRedirectsAndResolvesAgainstTheFinalUrl() {
        routes["/start.m3u8"] = { exchange ->
            exchange.responseHeaders.add("Location", "/real/path/list.m3u8")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        serveText("/real/path/list.m3u8", "#EXTM3U\n#EXTINF:2,\na.ts\n#EXT-X-ENDLIST\n")
        val segment = tsSegment(9)
        serve("/real/path/a.ts", segment)

        val dl = downloader()
        val output = dl.download(dl.resolve("$base/start.m3u8", null).video, "video") {}
        assertArrayEquals(segment, output.readBytes())
    }

    @Test
    fun handlesFmp4WithInitSectionAndByteRanges() {
        val init = bytes(1, 64).also { it[4] = 'f'.code.toByte(); it[5] = 't'.code.toByte(); it[6] = 'y'.code.toByte(); it[7] = 'p'.code.toByte() }
        val media = bytes(2, 3000)
        val file = init + media
        serve("/f/main.mp4", file)
        serveText(
            "/f/list.m3u8",
            """
            #EXTM3U
            #EXT-X-MAP:URI="main.mp4",BYTERANGE="64@0"
            #EXTINF:4,
            #EXT-X-BYTERANGE:1000@64
            main.mp4
            #EXTINF:4,
            #EXT-X-BYTERANGE:2000
            main.mp4
            #EXT-X-ENDLIST
            """.trimIndent(),
        )
        val dl = downloader()
        val output = dl.download(dl.resolve("$base/f/list.m3u8", null).video, "video") {}
        assertEquals("mp4", output.extension)
        assertArrayEquals(file, output.readBytes())
    }

    @Test
    fun byteRangesWorkWhenTheServerIgnoresRangeRequests() {
        val file = tsSegment(3, 4000)
        serve("/r/all.ts", file, honorRange = false)
        serveText("/r/list.m3u8", "#EXTM3U\n#EXTINF:1,\n#EXT-X-BYTERANGE:1500@0\nall.ts\n#EXTINF:1,\n#EXT-X-BYTERANGE:2500@1500\nall.ts\n#EXT-X-ENDLIST\n")
        val dl = downloader()
        val output = dl.download(dl.resolve("$base/r/list.m3u8", null).video, "video") {}
        assertArrayEquals(file, output.readBytes())
    }

    @Test
    fun stripsId3TagsFromPackedAudio() {
        // ID3v2 header with a 6-byte body, then ADTS-looking data.
        val id3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 6) + ByteArray(6)
        val aac1 = byteArrayOf(0xFF.toByte(), 0xF1.toByte()) + bytes(5, 300)
        val aac2 = byteArrayOf(0xFF.toByte(), 0xF1.toByte()) + bytes(6, 300)
        serve("/a/1.aac", id3 + aac1)
        serve("/a/2.aac", id3 + aac2)
        serveText("/a/audio.m3u8", "#EXTM3U\n#EXTINF:2,\n1.aac\n#EXTINF:2,\n2.aac\n#EXT-X-ENDLIST\n")
        val dl = downloader()
        val output = dl.download(dl.resolve("$base/a/audio.m3u8", null).video, "audio") {}
        assertEquals("aac", output.extension)
        assertArrayEquals(aac1 + aac2, output.readBytes())
    }

    @Test
    fun masterPlaylistPicksBestVariantAndItsAudio() {
        serveText(
            "/m/master.m3u8",
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="main",DEFAULT=YES,URI="audio.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360,AUDIO="a"
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720,AUDIO="a"
            high.m3u8
            """.trimIndent(),
        )
        serveText("/m/high.m3u8", "#EXTM3U\n#EXTINF:4,\nh0.ts\n#EXT-X-ENDLIST\n")
        serveText("/m/audio.m3u8", "#EXTM3U\n#EXTINF:4,\na0.aac\n#EXT-X-ENDLIST\n")
        val stream = downloader().resolve("$base/m/master.m3u8", null)
        assertEquals("$base/m/h0.ts", stream.video.segments.single().url)
        assertEquals("$base/m/a0.aac", stream.audio?.segments?.single()?.url)
    }

    @Test
    fun retriesTransientErrors() {
        serveText("/t/list.m3u8", "#EXTM3U\n#EXTINF:2,\nflaky.ts\n#EXT-X-ENDLIST\n")
        val data = tsSegment(4)
        val attempts = AtomicInteger()
        routes["/t/flaky.ts"] = { exchange ->
            if (attempts.incrementAndGet() < 3) {
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(200, data.size.toLong())
                exchange.responseBody.use { it.write(data) }
            }
        }
        val dl = downloader()
        val output = dl.download(dl.resolve("$base/t/list.m3u8", null).video, "video") {}
        assertArrayEquals(data, output.readBytes())
        assertEquals(3, attempts.get())
    }

    @Test
    fun refusesEncryptedLiveAndMissingStreams() {
        serveText("/e/list.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:2,\na.ts\n#EXT-X-ENDLIST\n")
        serveText("/l/list.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\na.ts\n")
        serve("/h/page", "<html></html>".toByteArray(), "text/html")
        expectFailure(HlsDownloadException.Reason.ENCRYPTED) { downloader().resolve("$base/e/list.m3u8", null) }
        expectFailure(HlsDownloadException.Reason.LIVE) { downloader().resolve("$base/l/list.m3u8", null) }
        expectFailure(HlsDownloadException.Reason.NOT_HLS) { downloader().resolve("$base/h/page", null) }
        expectFailure(HlsDownloadException.Reason.NETWORK) { downloader().resolve("$base/missing.m3u8", null) }

        serveText("/g/list.m3u8", "#EXTM3U\n#EXTINF:2,\ngone.ts\n#EXT-X-ENDLIST\n")
        val dl = downloader()
        val stream = dl.resolve("$base/g/list.m3u8", null)
        expectFailure(HlsDownloadException.Reason.NETWORK) { dl.download(stream.video, "video") {} }
    }

    @Test
    fun cancellationStopsTheDownload() {
        serveText("/c/list.m3u8", "#EXTM3U\n#EXTINF:2,\na.ts\n#EXTINF:2,\nb.ts\n#EXT-X-ENDLIST\n")
        serve("/c/a.ts", tsSegment(1))
        serve("/c/b.ts", tsSegment(2))
        var cancelled = false
        val dl = downloader { cancelled }
        val stream = dl.resolve("$base/c/list.m3u8", null)
        cancelled = true
        expectFailure(HlsDownloadException.Reason.CANCELLED) { dl.download(stream.video, "video") {} }
    }

    private fun expectFailure(reason: HlsDownloadException.Reason, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: HlsDownloadException) {
            assertEquals(reason, e.reason)
        }
    }
}
