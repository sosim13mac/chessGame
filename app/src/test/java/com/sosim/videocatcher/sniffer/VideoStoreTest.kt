package com.sosim.videocatcher.sniffer

import com.sosim.videocatcher.hls.HlsParser
import com.sosim.videocatcher.hls.HlsPlaylist
import com.sosim.videocatcher.hls.HlsProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStoreTest {

    private var now = 1_000L
    private val store = VideoStore(clock = { now++ })

    private fun mediaReport(
        url: String = "",
        blob: Boolean = false,
        event: String = MediaReport.EVENT_PLAY,
        manifests: List<String> = emptyList(),
        width: Int = 1280,
        height: Int = 720,
        drm: Boolean = false,
    ) = MediaReport(
        event = event,
        url = url,
        isBlob = blob,
        sources = emptyList(),
        width = width,
        height = height,
        durationSec = 95.0,
        posterUrl = "",
        drm = drm,
        manifests = manifests,
        frameUrl = "https://site.example/watch/1",
        isTopFrame = true,
        title = "My clip",
    )

    @Test
    fun directVideoFromElement() {
        val video = store.onMediaReport(mediaReport(url = "https://cdn.example/v/clip.mp4"), "Page")
        assertNotNull(video)
        video!!
        assertEquals(MediaKind.DIRECT, video.kind)
        assertEquals("My clip", video.pageTitle)
        assertEquals("https://site.example/watch/1", video.pageUrl)
        assertEquals(1280, video.width)
        assertTrue(video.lastPlayedAt > 0)
        assertTrue(video.isDownloadable)
        assertEquals(listOf(video), store.visibleItems())
    }

    @Test
    fun elementUrlWithoutExtensionIsStillAVideo() {
        val video = store.onMediaReport(mediaReport(url = "https://cdn.example/stream?id=42"), "Page")
        assertEquals(MediaKind.DIRECT, video?.kind)
    }

    @Test
    fun metadataEventDoesNotMarkAsPlaying() {
        val video = store.onMediaReport(mediaReport(url = "https://cdn.example/a.webm", event = MediaReport.EVENT_META), "Page")
        assertEquals(0L, video?.lastPlayedAt)
    }

    @Test
    fun blobPlaybackIsAttachedToItsManifest() {
        store.onNetworkMedia("https://cdn.example/hls/master.m3u8", null, "https://site.example/", emptyMap(), "Page")
        val video = store.onMediaReport(
            mediaReport(blob = true, manifests = listOf("https://cdn.example/hls/master.m3u8")),
            "Page",
        )
        assertEquals("https://cdn.example/hls/master.m3u8", video?.url)
        assertEquals(MediaKind.HLS, video?.kind)
        assertTrue(video!!.lastPlayedAt > 0)
        assertEquals(1, store.visibleItems().size)
    }

    @Test
    fun blobPlaybackBeforeManifestCreatesPlaceholderThatIsAdoptedLater() {
        val placeholder = store.onMediaReport(mediaReport(blob = true), "Page")
        assertEquals(MediaKind.MSE, placeholder?.kind)
        assertFalse(placeholder!!.isDownloadable)

        val manifest = store.onNetworkMedia("https://cdn.example/x/index.m3u8", null, null, emptyMap(), "Page")!!
        val visible = store.visibleItems()
        assertEquals(listOf(manifest), visible)
        assertTrue(manifest.lastPlayedAt > 0)
        assertEquals(1280, manifest.width)
        assertEquals("https://site.example/watch/1", manifest.pageUrl)
    }

    @Test
    fun variantPlaylistsHideBehindTheirMaster() {
        val masterUrl = "https://cdn.example/hls/master.m3u8"
        store.onNetworkMedia(masterUrl, null, null, emptyMap(), "Page")
        store.onNetworkMedia("https://cdn.example/hls/720/index.m3u8", null, null, emptyMap(), "Page")
        assertEquals(2, store.visibleItems().size)
        assertEquals(2, store.pendingHlsProbes().size)

        val master = HlsParser.parse(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1280x720\n720/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2,RESOLUTION=1920x1080\n1080/index.m3u8\n",
            masterUrl,
        ) as HlsPlaylist.Master
        store.applyHlsProbe(masterUrl, HlsProbe.Master(master, masterUrl))

        val visible = store.visibleItems()
        assertEquals(listOf(masterUrl), visible.map { it.url })
        // A variant seen later is hidden right away.
        store.onNetworkMedia("https://cdn.example/hls/1080/index.m3u8", null, null, emptyMap(), "Page")
        assertEquals(1, store.visibleItems().size)
        assertTrue(store.pendingHlsProbes().isEmpty())

        // Playback reported against a variant is credited to the master.
        val played = store.onMediaReport(
            mediaReport(blob = true, manifests = listOf("https://cdn.example/hls/1080/index.m3u8")),
            "Page",
        )
        assertEquals(masterUrl, played?.url)
    }

    @Test
    fun segmentLikeFilesAreHidden() {
        for (i in 1..5) {
            store.onNetworkMedia("https://cdn.example/dash/seg-$i.mp4", "video/mp4", null, emptyMap(), "Page")
        }
        store.onNetworkMedia("https://cdn.example/files/whole.mp4", null, null, emptyMap(), "Page")
        assertEquals(listOf("https://cdn.example/files/whole.mp4"), store.visibleItems().map { it.url })
    }

    @Test
    fun drmAndEncryptionBlockDownloads() {
        val drm = store.onMediaReport(mediaReport(url = "https://cdn.example/p.mp4", drm = true), "Page")!!
        assertFalse(drm.isDownloadable)

        val url = "https://cdn.example/enc.m3u8"
        val hls = store.onNetworkMedia(url, null, null, emptyMap(), "Page")!!
        val media = HlsParser.parse("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n", url) as HlsPlaylist.Media
        store.applyHlsProbe(url, HlsProbe.Media(media, url))
        assertTrue(hls.isEncrypted)
        assertFalse(hls.isDownloadable)
    }

    @Test
    fun recentlyPlayedComesFirstAndClearEmptiesTheStore() {
        val first = store.onNetworkMedia("https://cdn.example/a.mp4", null, null, emptyMap(), "Page")!!
        val second = store.onMediaReport(mediaReport(url = "https://cdn.example/b.mp4"), "Page")!!
        assertEquals(listOf(second, first), store.visibleItems())

        var changes = 0
        store.onChanged = { changes++ }
        store.clear()
        assertEquals(0, store.size)
        assertEquals(1, changes)
    }
}
