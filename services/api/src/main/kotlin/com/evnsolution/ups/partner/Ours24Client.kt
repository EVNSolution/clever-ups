package com.evnsolution.ups.partner

import org.slf4j.LoggerFactory
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration

/**
 * Read-only access to the Ours24 partner API. Every call is a bounded GET to one fixed origin:
 * no redirects, no environment proxy, no retries. The key is read from its file on every call,
 * so a replaced key works without a restart. Error bodies from Ours24 are never read or passed on.
 */
class Ours24Client(
    baseUrl: String,
    private val keyFile: Path,
    private val http: HttpClient = newHttpClient(),
) {
    private class Answer(val status: Int, val body: ByteArray)

    private val base: String = baseUrl.trimEnd('/')
    private val mapper = JsonMapper.builder().build()

    init {
        val uri = URI.create(base)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in LOOPBACK_HOSTS)) {
            "ours24.base-url must use https. Plain http is for a local fake server only."
        }
    }

    fun get(path: String, query: Map<String, String> = emptyMap()): JsonNode {
        val queryText = query.entries.joinToString("&", prefix = if (query.isEmpty()) "" else "?") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        val request = HttpRequest.newBuilder(URI.create(base + path + queryText))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("x-api-key", KeyFile.read(keyFile))
            .GET()
            .build()
        val answer = exchange(request)
        if (answer.status !in HTTP_OK_RANGE) {
            log.warn("Ours24 GET {} answered HTTP {}", path, answer.status)
            fail("Ours24 answered HTTP ${answer.status}")
        }
        if (answer.body.size > MAX_BODY_BYTES) fail("The Ours24 answer is too large")
        return parse(answer.body)
    }

    private fun encode(text: String): String = URLEncoder.encode(text, StandardCharsets.UTF_8)

    private fun exchange(request: HttpRequest): Answer = try {
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { stream ->
            val ok = response.statusCode() in HTTP_OK_RANGE
            Answer(response.statusCode(), if (ok) stream.readNBytes(MAX_BODY_BYTES + 1) else ByteArray(0))
        }
    } catch (e: IOException) {
        fail("Ours24 could not be reached", e)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        fail("The Ours24 request was interrupted", e)
    }

    private fun parse(body: ByteArray): JsonNode = try {
        mapper.readTree(body)
    } catch (e: JacksonException) {
        fail("The Ours24 answer is not JSON", e)
    }

    private companion object {
        // Ours24 refused the default Python agent with HTTP 403 (docs/ours24-partner-validation.md),
        // so this client names itself.
        const val USER_AGENT = "CLEVER-UPS-API/0.0.1"
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
        val HTTP_OK_RANGE = 200..299
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(20)
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
        val log = LoggerFactory.getLogger(Ours24Client::class.java)

        fun newHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(HttpClient.Builder.NO_PROXY)
            .build()
    }
}
