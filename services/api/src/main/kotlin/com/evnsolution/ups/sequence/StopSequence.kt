package com.evnsolution.ups.sequence

import java.io.IOException
import java.time.OffsetDateTime

/** A position as the map gives it. */
data class Point(val lng: Double = 0.0, val lat: Double = 0.0)

/** One place a driver must visit. [id] is the caller's (an order ID). */
data class StopIn(val id: String = "", val lng: Double = 0.0, val lat: Double = 0.0)

/**
 * One driver's stops to put in order. [start] is where the driver leaves from; without it the driver leaves from
 * the first stop of [stops]. [serviceSeconds] is the time spent at each stop.
 */
data class SequenceRequest(
    val startAt: OffsetDateTime? = null,
    val start: Point? = null,
    val serviceSeconds: Int = DEFAULT_SERVICE_SECONDS,
    val stops: List<StopIn> = emptyList(),
) {
    companion object {
        const val DEFAULT_SERVICE_SECONDS = 120
    }
}

/** A stop in visiting order: when the driver arrives, and the road distance driven so far. */
data class SequencedStop(val id: String, val arrival: OffsetDateTime, val distanceMeters: Long)

/**
 * The order the engine found. [geometry] is the road line as an encoded polyline (precision 5), or null.
 * [unassigned] holds the stops the engine could not reach.
 */
data class SequenceResult(
    val stops: List<SequencedStop>,
    val unassigned: List<String>,
    val distanceMeters: Long,
    val durationSeconds: Long,
    val geometry: String?,
)

/** Thrown when the route engine cannot be reached or gives an unusable answer. */
class EngineUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Puts one driver's stops in the order to visit them. */
fun interface StopSequencer {
    fun sequence(request: SequenceRequest): SequenceResult
}
