package com.evnsolution.ups.partner

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** A local stand-in for Ours24. It records every request and answers from a script. */
class FakeOurs24 : AutoCloseable {
    class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    class Seen(val path: String, val query: Map<String, String>, val key: String?, val agent: String?)

    val seen = CopyOnWriteArrayList<Seen>()
    private val replies = ConcurrentHashMap<String, (Map<String, String>) -> Reply>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val query = exchange.requestURI.rawQuery.orEmpty().split("&").filter { it.isNotEmpty() }
                .associate { it.substringBefore("=") to decode(it.substringAfter("=")) }
            val headers = exchange.requestHeaders
            seen += Seen(path, query, headers.getFirst("x-api-key"), headers.getFirst("User-Agent"))
            val reply = replies[path]?.invoke(query) ?: Reply(404, "{}")
            val bytes = reply.body.toByteArray()
            reply.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        server.start()
    }

    private fun decode(text: String): String = URLDecoder.decode(text, StandardCharsets.UTF_8)

    fun on(path: String, reply: (Map<String, String>) -> Reply) {
        replies[path] = reply
    }

    fun json(path: String, body: String, status: Int = 200) = on(path) { Reply(status, body) }

    override fun close() = server.stop(0)
}

/** A key file the way KeyFile wants it: mode 600, owned by this user. */
fun writeKeyFile(dir: Path, key: String = "test-key"): Path {
    val file = dir.resolve("key")
    Files.writeString(file, "$key\n")
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
    return file
}
