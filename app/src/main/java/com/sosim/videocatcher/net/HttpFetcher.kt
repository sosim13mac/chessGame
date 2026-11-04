package com.sosim.videocatcher.net

import com.sosim.videocatcher.hls.ByteRange
import com.sosim.videocatcher.hls.UrlResolver
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

class HttpException(val statusCode: Int, message: String) : IOException(message)

class CancelledException : IOException("Cancelled")

/**
 * Minimal HTTP client on top of HttpURLConnection. It sends the browser's
 * headers (User-Agent, Referer, Cookie, ...) so servers treat the request like
 * the page's own player, and follows redirects across http/https.
 */
class HttpFetcher(
    private val headers: Map<String, String>,
    private val cookieProvider: ((String) -> String?)? = null,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {

    class Response(
        val connection: HttpURLConnection,
        val finalUrl: String,
        val statusCode: Int,
    ) : Closeable {
        val contentType: String? get() = connection.contentType
        val contentLength: Long get() = connection.contentLengthLong
        val body: InputStream get() = connection.inputStream
        override fun close() = connection.disconnect()
    }

    class TextResult(val finalUrl: String, val body: String, val contentType: String?)

    /** Opens a GET request, following up to [MAX_REDIRECTS] redirects. Throws [HttpException] on non-2xx. */
    // URL(String) is used on purpose: unlike URI it accepts the unescaped characters real playlists contain.
    @Suppress("DEPRECATION")
    fun open(url: String, range: ByteRange? = null): Response {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            for ((name, value) in headers) connection.setRequestProperty(name, value)
            val cookie = try {
                cookieProvider?.invoke(current)
            } catch (e: Exception) {
                null
            }
            if (!cookie.isNullOrBlank()) connection.setRequestProperty("Cookie", cookie)
            if (range != null) connection.setRequestProperty("Range", "bytes=${range.offset}-${range.endInclusive}")

            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                connection.disconnect()
                throw e
            }
            if (code in 300..399 && code != 304) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw HttpException(code, "HTTP $code without Location")
                current = UrlResolver.resolve(current, location)
                return@repeat
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw HttpException(code, "HTTP $code")
            }
            return Response(connection, current, code)
        }
        throw IOException("Too many redirects: $url")
    }

    /** Downloads a (small) text resource such as a playlist. */
    fun fetchText(url: String, maxBytes: Int = MAX_TEXT_BYTES): TextResult {
        open(url).use { response ->
            val bytes = response.body.use { readLimited(it, maxBytes) }
            val charset = charsetOf(response.contentType) ?: Charsets.UTF_8
            return TextResult(response.finalUrl, String(bytes, charset), response.contentType)
        }
    }

    /**
     * Reads only the start of [url] to find out what it is. If the body is an
     * HLS playlist the whole playlist is returned as [TextResult.body];
     * otherwise the body is empty and only the content type is meaningful.
     */
    fun sniff(url: String): TextResult {
        open(url).use { response ->
            response.body.use { input ->
                val head = ByteArray(16)
                val n = readUpTo(input, head)
                val start = String(head, 0, n, Charsets.UTF_8)
                if (start.trimStart('﻿', ' ', '\r', '\n', '\t').startsWith("#EXTM3U")) {
                    val rest = readLimited(input, MAX_TEXT_BYTES)
                    val charset = charsetOf(response.contentType) ?: Charsets.UTF_8
                    return TextResult(response.finalUrl, String(head, 0, n, charset) + String(rest, charset), response.contentType)
                }
                return TextResult(response.finalUrl, "", response.contentType)
            }
        }
    }

    /** Downloads [url] (optionally a byte range of it) into [dest]. Returns the number of bytes written. */
    fun downloadTo(url: String, range: ByteRange?, dest: File, isCancelled: () -> Boolean): Long {
        open(url, range).use { response ->
            var skip = 0L
            var limit = Long.MAX_VALUE
            if (range != null) {
                limit = range.length
                // The server ignored the Range header and sent the whole resource.
                if (response.statusCode != 206) skip = range.offset
            }
            response.body.use { input ->
                skipFully(input, skip)
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (total < limit) {
                        if (isCancelled()) throw CancelledException()
                        val wanted = minOf(buffer.size.toLong(), limit - total).toInt()
                        val read = input.read(buffer, 0, wanted)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                    }
                    if (range != null && total < range.length) {
                        throw IOException("Incomplete byte range ($total/${range.length}): $url")
                    }
                    val expected = response.contentLength
                    if (range == null && expected > 0 && total != expected) {
                        throw IOException("Incomplete download ($total/$expected): $url")
                    }
                    return total
                }
            }
        }
    }

    companion object {
        const val MAX_REDIRECTS = 8
        const val MAX_TEXT_BYTES = 8 * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024

        fun charsetOf(contentType: String?): Charset? {
            val value = contentType?.split(';')
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim('"', ' ')
                ?: return null
            return try {
                Charset.forName(value)
            } catch (e: Exception) {
                null
            }
        }

        private fun readUpTo(input: InputStream, buffer: ByteArray): Int {
            var total = 0
            while (total < buffer.size) {
                val n = input.read(buffer, total, buffer.size - total)
                if (n < 0) break
                total += n
            }
            return total
        }

        private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > maxBytes) throw IOException("Response too large")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        fun skipFully(input: InputStream, count: Long) {
            var remaining = count
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else {
                    if (input.read() < 0) throw IOException("Unexpected end of stream")
                    remaining--
                }
            }
        }
    }
}
