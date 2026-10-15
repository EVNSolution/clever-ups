package com.evnsolution.ups.phone

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

/**
 * Asks Firebase Authentication (Identity Platform) to text a one-time code: `accounts:sendVerificationCode`.
 * The browser has already solved the reCAPTCHA; this server only forwards that token, after the ledger has
 * agreed to the send. The web API key is public by design, but it comes from a file so that it is one setting.
 *
 * Outcome rules follow [PhoneSendService]: a 4xx answer means "not sent" ([PhoneSendRejected]); everything
 * else (timeout, 5xx, unreadable answer, connection loss) escapes as an exception and counts as sent.
 * One attempt only. This class never retries.
 */
class IdentityPlatformSender(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
) : PhoneSender {
    private val mapper = JsonMapper.builder().build()

    override fun send(phone: String, challenge: String): String? {
        val body = mapper.writeValueAsString(mapOf("phoneNumber" to phone, "recaptchaToken" to challenge))
        val key = URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(URI("$baseUrl/v1/accounts:sendVerificationCode?key=$key"))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = exchange(request)
        return when (response.statusCode()) {
            in OK_RANGE -> sessionInfo(response.body())
            in REFUSED_RANGE -> throw PhoneSendRejected("The provider refused the send: " + errorCode(response.body()))
            else -> error("The provider answered ${response.statusCode()}")
        }
    }

    private fun exchange(request: HttpRequest): HttpResponse<String> =
        try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw IllegalStateException("The provider could not be reached", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("The send was interrupted", e)
        }

    private fun sessionInfo(body: String): String =
        try {
            mapper.readTree(body).path("sessionInfo").asString("").ifEmpty { error("The answer has no session") }
        } catch (e: JacksonException) {
            throw IllegalStateException("The provider's answer is unreadable", e)
        }

    /** Only the provider's error code (for example TOO_MANY_ATTEMPTS_TRY_LATER). Never the body, never a number. */
    private fun errorCode(body: String): String =
        try {
            val message = mapper.readTree(body).path("error").path("message").asString("")
            CODE.find(message)?.value ?: "UNKNOWN"
        } catch (_: JacksonException) {
            "UNKNOWN"
        }

    private companion object {
        const val DEFAULT_BASE_URL = "https://identitytoolkit.googleapis.com"
        const val OK_FROM = 200
        const val OK_TO = 299
        const val REFUSED_FROM = 400
        const val REFUSED_TO = 499
        val OK_RANGE = OK_FROM..OK_TO
        val REFUSED_RANGE = REFUSED_FROM..REFUSED_TO
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
        val CODE = Regex("^[A-Z][A-Z0-9_]{2,60}")
    }
}
