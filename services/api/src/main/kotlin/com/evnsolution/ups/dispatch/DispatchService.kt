package com.evnsolution.ups.dispatch

import com.evnsolution.ups.centers.DepotPlaces
import com.evnsolution.ups.sequence.EngineUnavailable
import com.evnsolution.ups.sequence.Point
import com.evnsolution.ups.sequence.SequenceRequest
import com.evnsolution.ups.sequence.SequenceResult
import com.evnsolution.ups.sequence.StopIn
import com.evnsolution.ups.sequence.StopSequencer
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Saves a dispatch. Each driver's orders are put in order by the route engine from the center's depot (or the
 * first order when the depot has no place), and the result is kept as computed. An order without a position, or
 * one the engine could not reach, is kept as unassigned. A new run of the same center and day replaces the
 * earlier draft.
 */
class DispatchService(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val sequencer: StopSequencer?,
    private val depots: DepotPlaces,
) {
    /** Reads what was saved. */
    val reader = DispatchReader(jdbc)

    /** Sends or cancels what was saved. */
    val states = DispatchStates(jdbc, transactions)

    /** Changes a draft: another driver for a route, or unassigned orders added to a route. */
    val edits = DispatchEdits(jdbc, transactions, sequencer)

    private data class Task(val key: String, val lng: Double?, val lat: Double?)
    private data class Driver(val id: String, val code: String, val routeCode: String?)
    private data class Planned(val driver: Driver, val result: SequenceResult?, val start: Point?)

    fun save(actorId: String, input: DispatchIn): DispatchSummary {
        val day = input.day ?: throw bad("day is required")
        val startAt = startAt(day, input.departAt)
        val tasks = tasks(input)
        val drivers = drivers(input)
        val depot = depot(input.centerId)
        val planned = input.routes.map { route ->
            val stops = route.orderIds.map(tasks::getValue).mapNotNull { task ->
                if (task.lng == null || task.lat == null) null else StopIn(task.key, task.lng, task.lat)
            }
            val result = if (stops.isEmpty()) null else sequence(SequenceRequest(startAt, depot, stops = stops))
            val start = depot ?: stops.firstOrNull()?.let { Point(it.lng, it.lat) }
            Planned(drivers.getValue(route.driverId), result, start)
        }
        val placed = planned.flatMap { it.result?.stops.orEmpty().map { stop -> stop.id } }.toSet()
        val loose = (input.routes.flatMap { it.orderIds } + input.unassigned).filter { it !in placed }
        val planId = UUID.randomUUID().toString()
        transactions.executeWithoutResult {
            jdbc.sql(
                "UPDATE dispatch_plan SET status = 'REPLACED', updated_at = CURRENT_TIMESTAMP " +
                    "WHERE center_code = :center AND service_day = :day AND status = 'DRAFT'",
            ).param("center", input.centerId).param("day", day).update()
            jdbc.sql(
                "INSERT INTO dispatch_plan (plan_id, center_code, service_day, method, depart_at, created_by) " +
                    "VALUES (:id, :center, :day, 'ZONE', :depart, :actor)",
            ).param("id", planId).param("center", input.centerId).param("day", day)
                .param("depart", startAt).param("actor", actorId).update()
            planned.filter { it.result != null }.forEach { insertRoute(planId, startAt, it) }
            loose.forEach { key -> insertStop(planId, key, null, null, null, null) }
        }
        return reader.list(input.centerId, day).first { it.id == planId }
    }

    private fun insertRoute(planId: String, startAt: OffsetDateTime, planned: Planned) {
        val result = planned.result ?: return
        val routeId = UUID.randomUUID().toString()
        jdbc.sql(
            "INSERT INTO dispatch_route (route_id, plan_id, driver_id, route_code, depart_at, start_lng, start_lat, " +
                "distance_m, duration_s, geometry) " +
                "VALUES (:id, :plan, :driver, :code, :depart, :lng, :lat, :m, :s, :g)",
        ).param("id", routeId).param("plan", planId).param("driver", planned.driver.id)
            .param("code", planned.driver.routeCode).param("depart", startAt)
            .param("lng", planned.start?.lng).param("lat", planned.start?.lat)
            .param("m", result.distanceMeters.toInt()).param("s", result.durationSeconds.toInt())
            .param("g", result.geometry).update()
        result.stops.forEachIndexed { index, stop ->
            insertStop(planId, stop.id, routeId, index + 1, stop.arrival, stop.distanceMeters.toInt())
        }
    }

    @Suppress("LongParameterList")
    private fun insertStop(
        planId: String,
        key: String,
        routeId: String?,
        seq: Int?,
        arrival: OffsetDateTime?,
        meters: Int?,
    ) {
        jdbc.sql(
            "INSERT INTO dispatch_stop (plan_id, task_key, route_id, seq, planned_arrival, distance_m) " +
                "VALUES (:plan, :key, :route, :seq, :arrival, :m)",
        ).param("plan", planId).param("key", key).param("route", routeId).param("seq", seq)
            .param("arrival", arrival).param("m", meters).update()
    }

    private fun sequence(request: SequenceRequest): SequenceResult =
        try {
            sequencer?.sequence(request) ?: throw EngineUnavailable("route engine is not set up")
        } catch (e: EngineUnavailable) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "route engine did not answer", e)
        }

    /** Every order of the request, once, with its stored position. An order never read is refused. */
    private fun tasks(input: DispatchIn): Map<String, Task> {
        val ids = input.routes.flatMap { it.orderIds } + input.unassigned
        if (ids.isEmpty() || ids.toSet().size != ids.size) throw bad("orders must be present and appear once")
        val found = jdbc.sql(
            "SELECT t.task_key, a.lng, a.lat FROM delivery_task t " +
                "JOIN address_location a ON a.address_key = t.address_key WHERE t.task_key IN (:keys)",
        ).param("keys", ids).query { rs, _ ->
            Task(rs.getString("task_key"), rs.getObject("lng") as Double?, rs.getObject("lat") as Double?)
        }.list().associateBy { it.key }
        if (found.size != ids.size) throw bad("some orders were never read; read the orders of the day again")
        return found
    }

    /** The drivers of the request, by code, in the center. The route code is the name of the main zone. */
    private fun drivers(input: DispatchIn): Map<String, Driver> {
        val codes = input.routes.map { it.driverId }
        if (codes.toSet().size != codes.size) throw bad("a driver appears twice")
        if (codes.isEmpty()) return emptyMap()
        val found = jdbc.sql(
            "SELECT d.driver_id, d.driver_code, z.name FROM driver d LEFT JOIN zone z ON z.zone_id = d.main_zone_id " +
                "WHERE d.center_code = :center AND d.driver_code IN (:codes)",
        ).param("center", input.centerId).param("codes", codes).query { rs, _ ->
            Driver(rs.getString("driver_id").trim(), rs.getString("driver_code"), rs.getString("name"))
        }.list().associateBy { it.code }
        if (found.size != codes.size) throw bad("some drivers are not drivers of this center")
        return found
    }

    private fun depot(centerId: String): Point? {
        val address = jdbc.sql("SELECT depot_address_ko FROM center WHERE center_code = :center")
            .param("center", centerId).query(String::class.java).optional().orElse(null)
        return depots.of(address)?.let { Point(it.lng, it.lat) }
    }

    private fun startAt(day: LocalDate, departAt: String): OffsetDateTime {
        val time = runCatching { LocalTime.parse(departAt) }.getOrNull() ?: throw bad("departAt must be HH:mm")
        return day.atTime(time).atZone(KOREA).toOffsetDateTime()
    }

    private fun bad(reason: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, reason)

    private companion object {
        val KOREA: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
