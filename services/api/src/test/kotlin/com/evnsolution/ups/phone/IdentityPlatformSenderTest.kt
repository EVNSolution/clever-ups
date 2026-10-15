package com.evnsolution.ups.phone

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/** Talks to a local stand-in for the provider. No test here can reach the real one. */
class IdentityPlatformSenderTest {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val seenPath = AtomicReference<String>()
    private val seenBody = AtomicReference<String>()
    private var status = 200
    private var answer = """{"sessionInfo":"session-1"}"""

    init {
        server.createContext("/") { exchange ->
            seenPath.set(exchange.requestURI.toString())
            seenBody.set(exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8))
            val bytes = answer.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun sender() = IdentityPlatformSender("web-key", "http://127.0.0.1:${server.address.port}")

    @Test
    fun `a good answer returns the session and posts the phone, the token and the key`() {
        assertEquals("session-1", sender().send("+821012345678", "captcha"))
        assertEquals("/v1/accounts:sendVerificationCode?key=web-key", seenPath.get())
        assertTrue(seenBody.get().contains("\"phoneNumber\":\"+821012345678\""))
        assertTrue(seenBody.get().contains("\"recaptchaToken\":\"captcha\""))
    }

    @Test
    fun `a 4xx answer is a refusal that names the code and never the number`() {
        status = 400
        answer = """{"error":{"message":"TOO_MANY_ATTEMPTS_TRY_LATER : +821012345678"}}"""
        val e = assertThrows(PhoneSendRejected::class.java) { sender().send("+821012345678", "captcha") }
        assertTrue(e.message!!.endsWith("TOO_MANY_ATTEMPTS_TRY_LATER"))
        assertFalse(e.message!!.contains("8210"))
    }

    @Test
    fun `a 5xx answer is uncertain, not a refusal`() {
        status = 503
        answer = "{}"
        val e = assertThrows(IllegalStateException::class.java) { sender().send("+821012345678", "captcha") }
        assertFalse(e is PhoneSendRejected)
    }

    @Test
    fun `an answer without a session is uncertain`() {
        answer = "{}"
        assertThrows(IllegalStateException::class.java) { sender().send("+821012345678", "captcha") }
    }

    @Test
    fun `a connection that fails is uncertain`() {
        server.stop(0)
        assertThrows(IllegalStateException::class.java) { sender().send("+821012345678", "captcha") }
    }
}
