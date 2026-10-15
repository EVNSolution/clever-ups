package com.evnsolution.ups.sequence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.web.server.ResponseStatusException
import java.io.IOException
import java.time.OffsetDateTime

/** What goes to VROOM and what comes back, without a running engine. */
class VroomSequencerTest {
    private val startAt = OffsetDateTime.parse("2026-10-14T09:30:00+09:00")
    private val request = SequenceRequest(
        startAt = startAt,
        start = Point(127.10, 37.40),
        stops = listOf(StopIn("A", 127.12, 37.42), StopIn("B", 127.15, 37.46), StopIn("C", 127.0, 37.0)),
    )

    private val answer = """
        {"code":0,"summary":{"distance":9100,"duration":1500},
         "unassigned":[{"id":3,"location":[127.0,37.0]}],
         "routes":[{"geometry":"_p~iF~ps|U","steps":[
           {"type":"start","arrival":0,"distance":0},
           {"type":"job","id":2,"arrival":600,"distance":4000},
           {"type":"job","id":1,"arrival":900,"distance":6000},
           {"type":"end","arrival":1500,"distance":9100}]}]}
    """.trimIndent()

    @Test
    fun `stops come back in visiting order with arrival times from the start time`() {
        var sent = ""
        val result = VroomSequencer { body -> sent = body; answer }.sequence(request)
        assertEquals(listOf("B", "A"), result.stops.map { it.id })
        assertEquals(startAt.plusSeconds(600), result.stops[0].arrival)
        assertEquals(6000, result.stops[1].distanceMeters)
        assertEquals(listOf("C"), result.unassigned)
        assertEquals(9100, result.distanceMeters)
        assertEquals("_p~iF~ps|U", result.geometry)
        // VROOM reads [lng, lat], never [lat, lng].
        assertTrue(sent.contains("\"start\":[127.1,37.4]"), sent)
        assertTrue(sent.contains("\"location\":[127.12,37.42]"), sent)
        assertTrue(sent.contains("\"service\":120"), sent)
    }

    @Test
    fun `without a start the driver leaves from the first stop given`() {
        var sent = ""
        VroomSequencer { body -> sent = body; answer }.sequence(request.copy(start = null))
        assertTrue(sent.contains("\"start\":[127.12,37.42]"), sent)
    }

    @Test
    fun `an engine that cannot answer is EngineUnavailable`() {
        val refused = VroomSequencer { throw IOException("refused") }
        val failed = VroomSequencer { """{"code":3,"error":"bad"}""" }
        assertThrows(EngineUnavailable::class.java) { refused.sequence(request) }
        assertThrows(EngineUnavailable::class.java) { failed.sequence(request) }
    }

    @Test
    fun `the endpoint answers 503 without an engine and 400 for a bad request`() {
        val none = StopSequenceController(DefaultListableBeanFactory().getBeanProvider(StopSequencer::class.java))
        assertEquals(503, assertThrows(ResponseStatusException::class.java) { none.post(request) }.statusCode.value())
        val bad = request.copy(stops = listOf(StopIn("A", 127.0, 37.0), StopIn("A", 127.1, 37.1)))
        assertEquals(400, assertThrows(ResponseStatusException::class.java) { none.post(bad) }.statusCode.value())
    }
}
