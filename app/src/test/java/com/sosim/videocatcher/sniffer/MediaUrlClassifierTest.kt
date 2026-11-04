package com.sosim.videocatcher.sniffer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaUrlClassifierTest {

    @Test
    fun classifiesByExtension() {
        assertEquals(MediaKind.HLS, MediaUrlClassifier.classify("https://x.com/a/master.m3u8?token=1"))
        assertEquals(MediaKind.DASH, MediaUrlClassifier.classify("https://x.com/a/manifest.mpd"))
        assertEquals(MediaKind.DIRECT, MediaUrlClassifier.classify("https://x.com/v/clip.MP4#t=10"))
        assertEquals(MediaKind.DIRECT, MediaUrlClassifier.classify("http://x.com/v/clip.webm"))
        assertNull(MediaUrlClassifier.classify("https://x.com/v/seg-12.ts"))
        assertNull(MediaUrlClassifier.classify("https://x.com/v/chunk.m4s"))
        assertNull(MediaUrlClassifier.classify("https://x.com/page.html"))
        assertNull(MediaUrlClassifier.classify("https://x.com/watch?v=video.mp4"))
        assertNull(MediaUrlClassifier.classify("blob:https://x.com/123"))
        assertNull(MediaUrlClassifier.classify("https://x.com"))
    }

    @Test
    fun classifiesByMimeType() {
        assertEquals(MediaKind.HLS, MediaUrlClassifier.classify("https://x.com/play", "application/vnd.apple.mpegurl"))
        assertEquals(MediaKind.HLS, MediaUrlClassifier.classify("https://x.com/play", "Application/X-MpegURL; charset=utf-8"))
        assertEquals(MediaKind.DASH, MediaUrlClassifier.classify("https://x.com/play", "application/dash+xml"))
        assertEquals(MediaKind.DIRECT, MediaUrlClassifier.classify("https://x.com/stream", "video/mp4"))
        assertNull(MediaUrlClassifier.classify("https://x.com/seg", "video/mp2t"))
        assertNull(MediaUrlClassifier.classify("https://x.com/seg", "audio/mp4"))
    }

    @Test
    fun extractsUrlParts() {
        assertEquals("mp4", MediaUrlClassifier.extensionOf("https://x.com/a/b.c/file.mp4?x=1.webm"))
        assertNull(MediaUrlClassifier.extensionOf("https://x.com/a/file"))
        assertNull(MediaUrlClassifier.extensionOf("https://x.com/a/.hidden"))
        assertEquals("file.mp4", MediaUrlClassifier.lastPathSegment("https://x.com/a/file.mp4?q"))
        assertEquals("x.com/a/b/", MediaUrlClassifier.directoryKey("https://x.com/a/b/seg1.mp4?t=1"))
    }
}
