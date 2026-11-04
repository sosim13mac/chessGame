package com.sosim.videocatcher.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UtilTest {

    @Test
    fun sanitizesFileNames() {
        assertEquals("a b c", FileNames.sanitize("a/b:c"))
        assertEquals("Movie trailer", FileNames.sanitize("  Movie\t\ttrailer.. "))
        assertEquals("video", FileNames.sanitize("///"))
        assertEquals(80, FileNames.sanitize("x".repeat(200)).length)
        assertEquals("My clip_720p", FileNames.baseName("My clip", 720))
        assertEquals("My clip", FileNames.baseName("My clip", 0))
        assertEquals("clip", FileNames.stripExtension("clip.mp4"))
        assertEquals("v1.2 final", FileNames.stripExtension("v1.2 final"))
    }

    @Test
    fun formatsValues() {
        assertEquals("1:05", Formatters.duration(65.4))
        assertEquals("1:01:01", Formatters.duration(3661.0))
        assertEquals("", Formatters.duration(-1.0))
        assertEquals("1280×720", Formatters.resolution(1280, 720))
        assertEquals("5.2 Mbps", Formatters.bitrate(5_200_000))
        assertEquals("800 kbps", Formatters.bitrate(800_000))
        assertEquals("1.5 MB", Formatters.bytes(1_572_864))
        assertEquals("cdn.example.com", Formatters.host("https://user@cdn.example.com/a?b"))
    }

    @Test
    fun turnsInputIntoUrls() {
        assertEquals("https://example.com", UrlInput.toUrl("example.com"))
        assertEquals("https://example.com/path?q=1", UrlInput.toUrl(" example.com/path?q=1 "))
        assertEquals("http://example.com", UrlInput.toUrl("http://example.com"))
        assertEquals("https://localhost:8080/x", UrlInput.toUrl("localhost:8080/x"))
        assertEquals("https://192.168.0.10", UrlInput.toUrl("192.168.0.10"))
        assertEquals(UrlInput.SEARCH_URL + "cat+videos", UrlInput.toUrl("cat videos"))
        assertEquals(UrlInput.SEARCH_URL + "javascript%3Aalert%281%29", UrlInput.toUrl("javascript:alert(1)"))
        assertNull(UrlInput.toUrl("   "))
        assertEquals("https://x.com/v.mp4", UrlInput.extractUrl("look: https://x.com/v.mp4."))
    }
}
