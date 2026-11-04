package com.sosim.videocatcher.hls

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlResolverTest {

    private val base = "http://a/b/c/d;p?q"

    private fun check(reference: String, expected: String) =
        assertEquals("resolve(\"$reference\")", expected, UrlResolver.resolve(base, reference))

    @Test
    fun rfc3986NormalExamples() {
        check("g:h", "g:h")
        check("g", "http://a/b/c/g")
        check("./g", "http://a/b/c/g")
        check("g/", "http://a/b/c/g/")
        check("/g", "http://a/g")
        check("//g", "http://g")
        check("?y", "http://a/b/c/d;p?y")
        check("g?y", "http://a/b/c/g?y")
        check("#s", "http://a/b/c/d;p?q#s")
        check("g#s", "http://a/b/c/g#s")
        check("g?y#s", "http://a/b/c/g?y#s")
        check(";x", "http://a/b/c/;x")
        check("g;x", "http://a/b/c/g;x")
        check("g;x?y#s", "http://a/b/c/g;x?y#s")
        check("", "http://a/b/c/d;p?q")
        check(".", "http://a/b/c/")
        check("./", "http://a/b/c/")
        check("..", "http://a/b/")
        check("../", "http://a/b/")
        check("../g", "http://a/b/g")
        check("../..", "http://a/")
        check("../../", "http://a/")
        check("../../g", "http://a/g")
    }

    @Test
    fun rfc3986AbnormalExamples() {
        check("../../../g", "http://a/g")
        check("../../../../g", "http://a/g")
        check("/./g", "http://a/g")
        check("/../g", "http://a/g")
        check("g.", "http://a/b/c/g.")
        check(".g", "http://a/b/c/.g")
        check("g..", "http://a/b/c/g..")
        check("..g", "http://a/b/c/..g")
        check("./../g", "http://a/b/g")
        check("./g/.", "http://a/b/c/g/")
        check("g/./h", "http://a/b/c/g/h")
        check("g/../h", "http://a/b/c/h")
        check("g;x=1/./y", "http://a/b/c/g;x=1/y")
        check("g;x=1/../y", "http://a/b/c/y")
        check("g?y/./x", "http://a/b/c/g?y/./x")
        check("g?y/../x", "http://a/b/c/g?y/../x")
        check("g#s/./x", "http://a/b/c/g#s/./x")
        check("g#s/../x", "http://a/b/c/g#s/../x")
    }

    @Test
    fun typicalPlaylistReferences() {
        val playlist = "https://cdn.example.com/hls/movie/index.m3u8?token=abc"
        assertEquals("https://cdn.example.com/hls/movie/seg-1.ts", UrlResolver.resolve(playlist, "seg-1.ts"))
        assertEquals("https://cdn.example.com/hls/movie/720/index.m3u8", UrlResolver.resolve(playlist, "720/index.m3u8"))
        assertEquals("https://other.example.com/x.ts", UrlResolver.resolve(playlist, "//other.example.com/x.ts"))
        assertEquals("https://cdn.example.com/seg|1.ts", UrlResolver.resolve(playlist, "/seg|1.ts"))
        assertEquals("http://a/x y.ts", UrlResolver.resolve("http://a/list.m3u8", "x y.ts"))
    }
}
