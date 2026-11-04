package com.sosim.videocatcher.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestHeadersTest {

    @Test
    fun keepsUsefulHeadersAndDropsUnsafeOnes() {
        val headers = RequestHeaders.forDownload(
            userAgent = "UA/1.0",
            referer = "https://site.example/watch",
            captured = mapOf(
                "Range" to "bytes=0-",
                "Origin" to "https://site.example",
                "X-Requested-With" to "com.sosim.videocatcher",
                "X-Playback-Session-Id" to "abc",
                "Cookie" to "secret=1",
                "Accept-Encoding" to "gzip",
            ),
        )
        assertEquals("UA/1.0", headers["User-Agent"])
        assertEquals("https://site.example/watch", headers["Referer"])
        assertEquals("https://site.example", headers["Origin"])
        assertEquals("abc", headers["X-Playback-Session-Id"])
        assertFalse(headers.containsKey("Range"))
        assertFalse(headers.containsKey("Cookie"))
        assertFalse(headers.containsKey("X-Requested-With"))
        assertFalse(headers.containsKey("Accept-Encoding"))
    }

    @Test
    fun fallsBackToCapturedReferer() {
        val headers = RequestHeaders.forDownload("UA", "", mapOf("referer" to "https://embed.example/p"))
        assertEquals("https://embed.example/p", headers["Referer"])
    }

    @Test
    fun validatesHeaders() {
        assertTrue(RequestHeaders.isValidHeader("X-Token", "abc"))
        assertFalse(RequestHeaders.isValidHeader("Bad:Name", "x"))
        assertFalse(RequestHeaders.isValidHeader("X-Inject", "a\r\nHost: evil"))
    }
}
