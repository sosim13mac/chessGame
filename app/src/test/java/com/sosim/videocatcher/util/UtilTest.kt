package com.sosim.videocatcher.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

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
        assertEquals(UrlInput.SEARCH_URL + URLEncoder.encode("고양이", "UTF-8"), UrlInput.toUrl("고양이"))
        assertEquals("https://x.com/v.mp4", UrlInput.extractUrl("look: https://x.com/v.mp4."))
    }

    @Test
    fun recognisesGuessedHosts() {
        assertTrue(UrlInput.isGuessedHost("node.js"))
        assertTrue(UrlInput.isGuessedHost(" example.com/path "))
        assertFalse(UrlInput.isGuessedHost("https://example.com"))
        assertFalse(UrlInput.isGuessedHost("cat videos"))
        assertFalse(UrlInput.isGuessedHost("고양이"))
        assertEquals(UrlInput.SEARCH_URL + "node.js", UrlInput.searchUrl(" node.js "))
    }

    @Test
    fun opensSharedTextAsLinkOrSearch() {
        // Browsers usually share "title + link"; the link wins.
        assertEquals("https://m.example.com/v/1", UrlInput.fromSharedText("재미있는 영상\nhttps://m.example.com/v/1"))
        // Text without a link is searched on Google (whitespace collapsed).
        assertEquals(UrlInput.SEARCH_URL + URLEncoder.encode("고양이 영상", "UTF-8"), UrlInput.fromSharedText("  고양이 \n 영상 "))
        assertEquals(UrlInput.SEARCH_URL + "x".repeat(200), UrlInput.fromSharedText("x".repeat(500)))
        assertNull(UrlInput.fromSharedText(" \n "))
    }
}
