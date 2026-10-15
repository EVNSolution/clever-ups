package com.evnsolution.ups.partner

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class Ours24ClientTest {
    @TempDir
    lateinit var dir: Path
    private val fake = FakeOurs24()
    private lateinit var keyFile: Path
    private lateinit var client: Ours24Client

    @BeforeEach
    fun setUp() {
        keyFile = writeKeyFile(dir)
        client = Ours24Client(fake.url, keyFile)
    }

    @AfterEach
    fun tearDown() = fake.close()

    @Test
    fun `sends the key and its own name, and joins the query with ampersands`() {
        fake.json("/jobs", """{"ok":true}""")
        val body = client.get("/jobs", mapOf("a" to "1", "b" to "x y"))
        assertTrue(body.path("ok").asBoolean())
        val seen = fake.seen.single()
        assertEquals("test-key", seen.key)
        assertEquals("CLEVER-UPS-API/0.0.1", seen.agent)
        assertEquals(mapOf("a" to "1", "b" to "x y"), seen.query)
    }

    @Test
    fun `an error status fails without the key or the body`() {
        fake.json("/jobs", """{"echo":"test-key"}""", status = 500)
        val e = assertThrows<Ours24Exception> { client.get("/jobs") }
        assertEquals("Ours24 answered HTTP 500", e.message)
    }

    @Test
    fun `a redirect is not followed`() {
        fake.on("/jobs") { FakeOurs24.Reply(302, "", mapOf("Location" to "/other")) }
        fake.json("/other", "{}")
        assertThrows<Ours24Exception> { client.get("/jobs") }
        assertEquals(listOf("/jobs"), fake.seen.map { it.path })
    }

    @Test
    fun `an answer that is not JSON fails`() {
        fake.json("/jobs", "<html>")
        assertThrows<Ours24Exception> { client.get("/jobs") }
    }

    @Test
    fun `a server that is not there fails`() {
        assertThrows<Ours24Exception> { Ours24Client("http://127.0.0.1:1", keyFile).get("/jobs") }
    }

    @Test
    fun `plain http is refused unless the address is local`() {
        assertThrows<IllegalArgumentException> { Ours24Client("http://example.com", keyFile) }
        Ours24Client("https://api.ours-24.com", keyFile)
    }
}
