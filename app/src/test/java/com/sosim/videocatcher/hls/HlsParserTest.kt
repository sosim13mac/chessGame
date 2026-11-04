package com.sosim.videocatcher.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsParserTest {

    @Test
    fun parsesMasterPlaylist() {
        val text = """
            #EXTM3U
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,AVERAGE-BANDWIDTH=700000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2",AUDIO="aac"
            360p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,FRAME-RATE=29.970,CODECS="avc1.640028,mp4a.40.2",AUDIO="aac"
            1080p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="aac"
            https://cdn2.example.com/720p.m3u8
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=90000,URI="iframes.m3u8"
        """.trimIndent()
        val master = HlsParser.parse(text, "https://example.com/v/master.m3u8") as HlsPlaylist.Master

        assertEquals(3, master.variants.size)
        val v360 = master.variants[0]
        assertEquals("https://example.com/v/360p/index.m3u8", v360.url)
        assertEquals(800_000L, v360.bandwidth)
        assertEquals(700_000L, v360.averageBandwidth)
        assertEquals(640, v360.width)
        assertEquals(360, v360.height)
        assertEquals("avc1.4d401e,mp4a.40.2", v360.codecs)
        assertEquals("aac", v360.audioGroupId)
        assertEquals(29.97, master.variants[1].frameRate, 0.001)

        assertEquals(listOf(1080, 720, 360), master.sortedVariants.map { it.height })
        assertEquals(1080, master.bestVariant?.height)
        assertEquals(1080, master.maxHeight)
        assertEquals("https://example.com/v/audio/en.m3u8", master.audioFor(v360)?.url)
        assertTrue("https://cdn2.example.com/720p.m3u8" in master.childUrls)
        assertTrue("https://example.com/v/audio/en.m3u8" in master.childUrls)
        assertFalse(master.isEncrypted)
    }

    @Test
    fun parsesMediaPlaylist() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:9.009,
            seg0.ts
            #EXTINF:9.009, title with, commas
            seg1.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:3.003,
            /abs/seg2.ts?sig=1
            #EXT-X-ENDLIST
        """.trimIndent()
        val media = HlsParser.parse(text, "https://example.com/v/720/index.m3u8") as HlsPlaylist.Media

        assertEquals(3, media.segments.size)
        assertEquals("https://example.com/v/720/seg0.ts", media.segments[0].url)
        assertEquals("https://example.com/abs/seg2.ts?sig=1", media.segments[2].url)
        assertTrue(media.segments[2].discontinuity)
        assertFalse(media.segments[1].discontinuity)
        assertEquals(21.021, media.totalDurationSec, 0.0001)
        assertEquals(10.0, media.targetDurationSec, 0.0)
        assertTrue(media.isEndList)
        assertFalse(media.isLive)
        assertFalse(media.isEncrypted)
        assertFalse(media.usesFmp4)
    }

    @Test
    fun detectsLiveAndVodWithoutEndList() {
        val live = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\na.ts\n#EXTINF:4,\nb.ts\n"
        assertTrue((HlsParser.parse(live, "https://x/l.m3u8") as HlsPlaylist.Media).isLive)

        val vod = "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:4,\na.ts\n"
        assertFalse((HlsParser.parse(vod, "https://x/l.m3u8") as HlsPlaylist.Media).isLive)
    }

    @Test
    fun detectsEncryption() {
        val encrypted = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example.com/k1",IV=0x1234
            #EXTINF:4,
            a.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertEquals("AES-128", (HlsParser.parse(encrypted, "https://x/l.m3u8") as HlsPlaylist.Media).encryptionMethod)

        val clear = "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n"
        assertNull((HlsParser.parse(clear, "https://x/l.m3u8") as HlsPlaylist.Media).encryptionMethod)

        val session = "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n"
        assertTrue((HlsParser.parse(session, "https://x/m.m3u8") as HlsPlaylist.Master).isEncrypted)
    }

    @Test
    fun parsesFmp4ByteRangesAndGaps() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:6
            #EXT-X-MAP:URI="main.mp4",BYTERANGE="720@0"
            #EXTINF:6,
            #EXT-X-BYTERANGE:1000@720
            main.mp4
            #EXTINF:6,
            #EXT-X-BYTERANGE:2000
            main.mp4
            #EXT-X-GAP
            #EXTINF:6,
            missing.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val media = HlsParser.parse(text, "https://x/v/list.m3u8") as HlsPlaylist.Media

        assertTrue(media.usesFmp4)
        val init = media.segments[0].initSection
        assertEquals("https://x/v/main.mp4", init?.url)
        assertEquals(ByteRange(0, 720), init?.byteRange)
        assertEquals(ByteRange(720, 1000), media.segments[0].byteRange)
        assertEquals(ByteRange(1720, 2000), media.segments[1].byteRange)
        assertTrue(media.segments[2].isGap)
        assertEquals(ByteRange(1720, 2000).endInclusive, 3719L)
    }

    @Test
    fun handlesBomCrlfAndRejectsNonPlaylists() {
        val text = "﻿#EXTM3U\r\n#EXTINF:2.5,\r\nchunk.ts\r\n#EXT-X-ENDLIST\r\n"
        val media = HlsParser.parse(text, "https://x/a/b.m3u8") as HlsPlaylist.Media
        assertEquals("https://x/a/chunk.ts", media.segments.single().url)
        assertTrue(HlsParser.looksLikePlaylist(text))
        assertFalse(HlsParser.looksLikePlaylist("<html>"))
        try {
            HlsParser.parse("<html></html>", "https://x/")
            throw AssertionError("expected HlsParseException")
        } catch (expected: HlsParseException) {
            // ok
        }
    }

    @Test
    fun parsesAttributeLists() {
        val attributes = HlsParser.parseAttributes("BANDWIDTH=1,CODECS=\"a, b\",resolution=10x20, NAME=\"x=y\"")
        assertEquals("1", attributes["BANDWIDTH"])
        assertEquals("a, b", attributes["CODECS"])
        assertEquals("10x20", attributes["RESOLUTION"])
        assertEquals("x=y", attributes["NAME"])
    }
}
