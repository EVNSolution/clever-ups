package com.evnsolution.ups.sequence

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Turns on the route engine. It is off unless `ups.vroom.url` is set (environment variable UPS_VROOM_URL), for
 * example http://127.0.0.1:3005/ from infra/route. Off, the endpoint answers 503.
 */
@Configuration
@ConditionalOnProperty("ups.vroom.url")
class StopSequenceConfig {
    @Bean
    fun stopSequencer(@Value("\${ups.vroom.url}") url: String): StopSequencer = VroomSequencer(HttpJsonPost(url))
}

/** Puts one driver's stops in visiting order, with arrival times and the road line. Any active member may ask. */
@RestController
@RequestMapping("/api/stop-sequence")
class StopSequenceController(private val sequencers: ObjectProvider<StopSequencer>) {
    @PostMapping
    fun post(@RequestBody body: SequenceRequest): SequenceResult {
        val problem = problem(body)
        val sequencer = sequencers.getIfAvailable()
        if (problem != null || sequencer == null) {
            val status = if (problem != null) HttpStatus.BAD_REQUEST else HttpStatus.SERVICE_UNAVAILABLE
            throw ResponseStatusException(status, problem ?: "route engine is not set up")
        }
        return try {
            sequencer.sequence(body)
        } catch (e: EngineUnavailable) {
            log.warn("Route engine failed: {}", e.message)
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "route engine did not answer")
        }
    }

    private fun problem(body: SequenceRequest): String? {
        val ids = body.stops.map { it.id }
        val points = body.stops.map { Point(it.lng, it.lat) } + listOfNotNull(body.start)
        return when {
            body.startAt == null -> "startAt is required"
            body.stops.size !in 1..MAX_STOPS -> "stops must be 1..$MAX_STOPS"
            ids.any { it.isBlank() } || ids.toSet().size != ids.size -> "stop ids must be present and unique"
            body.serviceSeconds !in 0..MAX_SERVICE_SECONDS -> "serviceSeconds must be 0..$MAX_SERVICE_SECONDS"
            points.any { it.lat !in -MAX_LAT..MAX_LAT || it.lng !in -MAX_LNG..MAX_LNG } -> "a position is out of range"
            else -> null
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(StopSequenceController::class.java)
        const val MAX_STOPS = 150
        const val MAX_SERVICE_SECONDS = 3600
        const val MAX_LAT = 90.0
        const val MAX_LNG = 180.0
    }
}
