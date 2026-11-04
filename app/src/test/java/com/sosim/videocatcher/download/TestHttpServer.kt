package com.sosim.videocatcher.download

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * Minimal HTTP/1.1 server for unit tests. (Android unit tests compile against
 * android.jar, which has no com.sun.net.httpserver.)
 */
class TestHttpServer : AutoCloseable {

    class Request(val method: String, val path: String, private val headers: Map<String, String>) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    class Response(
        val status: Int,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
    )

    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val pool = Executors.newCachedThreadPool()

    /** path (without query) -> handler */
    val routes = ConcurrentHashMap<String, (Request) -> Response>()
    val requests = ConcurrentLinkedQueue<Request>()
    val baseUrl: String get() = "http://127.0.0.1:${serverSocket.localPort}"

    init {
        pool.execute {
            while (!serverSocket.isClosed) {
                val client = try {
                    serverSocket.accept()
                } catch (e: Exception) {
                    break
                }
                pool.execute { handle(client) }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            val headers = HashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val request = Request(parts[0], parts.getOrElse(1) { "/" }.substringBefore('?'), headers)
            requests += request
            val response = routes[request.path]?.invoke(request) ?: Response(404)

            val head = StringBuilder("HTTP/1.1 ${response.status} X\r\n")
            for ((name, value) in response.headers) head.append(name).append(": ").append(value).append("\r\n")
            head.append("Content-Length: ").append(response.body.size).append("\r\n")
            head.append("Connection: close\r\n\r\n")
            val out = socket.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            out.write(response.body)
            out.flush()
        }
    }

    override fun close() {
        serverSocket.close()
        pool.shutdownNow()
    }
}
