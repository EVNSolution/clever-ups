package com.evnsolution.ups.sequence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.OffsetDateTime

/** Against a running engine (infra/route). Runs only when UPS_TEST_VROOM_URL is set, e.g. http://127.0.0.1:3005/. */
@EnabledIfEnvironmentVariable(named = "UPS_TEST_VROOM_URL", matches = ".+")
class LiveVroomTest {
    @Test
    fun `the engine orders three stops in Seongnam and returns times and a road line`() {
        val startAt = OffsetDateTime.parse("2026-10-14T09:30:00+09:00")
        val result = VroomSequencer(HttpJsonPost(System.getenv("UPS_TEST_VROOM_URL"))).sequence(
            SequenceRequest(
                startAt = startAt,
                stops = listOf(
                    StopIn("A", 127.1265, 37.4201),
                    StopIn("B", 127.1500, 37.4400),
                    StopIn("C", 127.1300, 37.4250),
                ),
            ),
        )
        assertEquals(setOf("A", "B", "C"), result.stops.map { it.id }.toSet())
        assertEquals(emptyList<String>(), result.unassigned)
        assertTrue(result.stops.zipWithNext().all { (a, b) -> !b.arrival.isBefore(a.arrival) })
        assertTrue(result.stops.first().arrival.isAfter(startAt.minusSeconds(1)))
        assertTrue(result.distanceMeters > 0 && !result.geometry.isNullOrEmpty())
        val order = result.stops.map { "${it.id}@${it.arrival.toLocalTime()}" }
        println("live order=$order meters=${result.distanceMeters}")
    }
}
