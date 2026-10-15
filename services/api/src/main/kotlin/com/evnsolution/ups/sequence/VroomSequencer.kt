package com.evnsolution.ups.sequence

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Sends one JSON body and returns the answer body. A failure to reach or an unusable status is an [IOException]. */
fun interface JsonPost {
    fun post(body: String): String
}

/** The real door to vroom-express: `POST /` with a long timeout, since solving may take a while. */
class HttpJsonPost(
    private val url: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_SECONDS)).build(),
) : JsonPost {
    override fun post(body: String): String {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(REQUEST_SECONDS))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted", e)
        }
        if (response.statusCode() !in OK_RANGE) {
            throw IOException("HTTP ${response.statusCode()}: ${response.body().take(ERROR_PREVIEW)}")
        }
        return response.body()
    }

    private companion object {
        const val CONNECT_SECONDS = 3L
        const val REQUEST_SECONDS = 60L
        const val ERROR_PREVIEW = 200
        val OK_RANGE = 200..299
    }
}

/**
 * Orders one driver's stops with VROOM, which reads road times and distances from OSRM (South Korea).
 *
 *  - source: EVNSolution/clever-route-server, apps/delivery-api/src/modules/route-plans/vroom-route-optimizer.client.ts
 *    and infra/vroom/config.korea.yml at aff739cfc07a05dab1d5322ee4db072eb102f7ed.
 *  - kept: one vehicle with the `car` profile, positions as [lng, lat], job steps read in order, unassigned jobs
 *    reported by ID, geometry asked with option `g`.
 *  - changed: Kotlin; one driver per call; no job queue or database; the first stop given as the start when the
 *    request has none (VROOM refuses a vehicle without a start or an end). DSV's servers and settings are not used.
 */
class VroomSequencer(private val engine: JsonPost) : StopSequencer {
    private val mapper = JsonMapper.builder().build()

    override fun sequence(request: SequenceRequest): SequenceResult {
        val startAt = requireNotNull(request.startAt) { "startAt is required" }
        val answer = ask(request)
        if (answer.path("code").asInt(-1) != 0) {
            val code = answer.path("code").asString("")
            throw EngineUnavailable("route engine error $code: ${answer.path("error").asString("")}")
        }
        // VROOM job IDs are numbers: the position of the stop in the request, plus one.
        val idOf = { node: JsonNode -> request.stops.getOrNull(node.path("id").asInt() - 1)?.id }
        val route = answer.path("routes").path(0)
        val stops = route.path("steps").filter { it.path("type").asString("") == "job" }.mapNotNull { step ->
            idOf(step)?.let { id ->
                SequencedStop(id, startAt.plusSeconds(step.path("arrival").asLong()), step.path("distance").asLong())
            }
        }
        return SequenceResult(
            stops = stops,
            unassigned = answer.path("unassigned").mapNotNull(idOf),
            distanceMeters = answer.path("summary").path("distance").asLong(),
            durationSeconds = answer.path("summary").path("duration").asLong(),
            geometry = route.path("geometry").asString(null),
        )
    }

    private fun ask(request: SequenceRequest): JsonNode =
        try {
            mapper.readTree(engine.post(mapper.writeValueAsString(body(request))))
        } catch (e: IOException) {
            throw EngineUnavailable("route engine did not answer: ${e.message}", e)
        } catch (e: JacksonException) {
            throw EngineUnavailable("route engine answer is not JSON", e)
        }

    private fun body(request: SequenceRequest): Map<String, Any> {
        val vehicle = buildMap<String, Any> {
            put("id", 1)
            put("profile", "car")
            // VROOM needs a start or an end. Without a start the driver leaves from the first stop given.
            val start = request.start ?: request.stops.first().let { Point(it.lng, it.lat) }
            put("start", listOf(start.lng, start.lat))
        }
        val jobs = request.stops.mapIndexed { index, stop ->
            mapOf("id" to index + 1, "location" to listOf(stop.lng, stop.lat), "service" to request.serviceSeconds)
        }
        return mapOf("vehicles" to listOf(vehicle), "jobs" to jobs, "options" to mapOf("g" to true))
    }
}
