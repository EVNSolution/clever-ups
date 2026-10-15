package com.evnsolution.ups.dispatch

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
import java.time.OffsetDateTime

/**
 * Changes a draft before it is sent: give a route to another driver of the center, or add unassigned orders to a
 * route. Adding asks the route engine again for the whole route, from the route's own start and time.
 */
class DispatchEdits(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val sequencer: StopSequencer?,
) {
    private data class RouteRow(val id: String, val departAt: OffsetDateTime, val start: Point?)

    /** The route of [fromCode] goes to [toCode], who must be of the center and have no route in the run yet. */
    fun changeDriver(planId: String, fromCode: String, toCode: String) = inDraft(planId) { center ->
        val route = route(planId, fromCode)
        val driver = jdbc.sql(
            "SELECT d.driver_id, z.name FROM driver d LEFT JOIN zone z ON z.zone_id = d.main_zone_id " +
                "WHERE d.center_code = :center AND d.driver_code = :code",
        ).param("center", center).param("code", toCode)
            .query { rs, _ -> rs.getString(1).trim() to rs.getString(2) }.optional().orElse(null)
            ?: throw fail(HttpStatus.BAD_REQUEST, "not a driver of this center")
        val busy = jdbc.sql("SELECT COUNT(*) FROM dispatch_route WHERE plan_id = :plan AND driver_id = :driver")
            .param("plan", planId).param("driver", driver.first).query(Int::class.java).single()
        if (busy > 0) throw fail(HttpStatus.CONFLICT, "the driver already has a route in this run")
        jdbc.sql("UPDATE dispatch_route SET driver_id = :driver, route_code = :code WHERE route_id = :id")
            .param("driver", driver.first).param("code", driver.second).param("id", route.id).update()
    }

    /** Unassigned orders of the run join the route of [driverCode]; the whole route is ordered again. */
    fun addOrders(planId: String, driverCode: String, orderIds: List<String>) {
        val route = route(planId, driverCode)
        val loose = jdbc.sql(
            "SELECT s.task_key, a.lng, a.lat FROM dispatch_stop s JOIN delivery_task t ON t.task_key = s.task_key " +
                "JOIN address_location a ON a.address_key = t.address_key " +
                "WHERE s.plan_id = :plan AND (s.route_id = :route OR (s.route_id IS NULL AND s.task_key IN (:add)))",
        ).param("plan", planId).param("route", route.id).param("add", orderIds.ifEmpty { listOf("") })
            .query { rs, _ -> Triple(rs.getString(1), rs.getObject(2) as Double?, rs.getObject(3) as Double?) }.list()
        val problem = when {
            orderIds.isEmpty() || orderIds.toSet().size != orderIds.size -> "orders must be present and appear once"
            !loose.map { it.first }.toSet().containsAll(orderIds) -> "only unassigned orders of this run"
            loose.any { it.second == null || it.third == null } -> "an order has no position"
            else -> null
        }
        if (problem != null) throw fail(HttpStatus.BAD_REQUEST, problem)
        val stops = loose.map { StopIn(it.first, it.second!!, it.third!!) }
        val result = sequence(SequenceRequest(route.departAt, route.start, stops = stops))
        inDraft(planId) { _ -> store(planId, route.id, result) }
    }

    private fun store(planId: String, routeId: String, result: SequenceResult) {
        jdbc.sql("UPDATE dispatch_route SET distance_m = :m, duration_s = :s, geometry = :g WHERE route_id = :id")
            .param("m", result.distanceMeters.toInt()).param("s", result.durationSeconds.toInt())
            .param("g", result.geometry).param("id", routeId).update()
        result.stops.forEachIndexed { index, stop ->
            jdbc.sql(
                "UPDATE dispatch_stop SET route_id = :route, seq = :seq, planned_arrival = :at, distance_m = :m " +
                    "WHERE plan_id = :plan AND task_key = :key",
            ).param("route", routeId).param("seq", index + 1).param("at", stop.arrival)
                .param("m", stop.distanceMeters.toInt()).param("plan", planId).param("key", stop.id).update()
        }
        result.unassigned.forEach { key ->
            jdbc.sql(
                "UPDATE dispatch_stop SET route_id = NULL, seq = NULL, planned_arrival = NULL, distance_m = NULL " +
                    "WHERE plan_id = :plan AND task_key = :key",
            ).param("plan", planId).param("key", key).update()
        }
    }

    private fun route(planId: String, driverCode: String): RouteRow =
        jdbc.sql(
            "SELECT r.route_id, r.depart_at, r.start_lng, r.start_lat FROM dispatch_route r " +
                "JOIN driver d ON d.driver_id = r.driver_id WHERE r.plan_id = :plan AND d.driver_code = :code",
        ).param("plan", planId).param("code", driverCode).query { rs, _ ->
            val lng = rs.getObject("start_lng") as Double?
            val lat = rs.getObject("start_lat") as Double?
            RouteRow(
                rs.getString("route_id").trim(),
                rs.getObject("depart_at", OffsetDateTime::class.java),
                if (lng == null || lat == null) null else Point(lng, lat),
            )
        }.optional().orElse(null) ?: throw fail(HttpStatus.NOT_FOUND, "no route of this driver in the run")

    /** Runs [change] with the center row locked, only while the run is still a DRAFT. */
    private fun inDraft(planId: String, change: (String) -> Unit) {
        transactions.executeWithoutResult {
            val center = jdbc.sql("SELECT center_code FROM dispatch_plan WHERE plan_id = :id").param("id", planId)
                .query(String::class.java).optional().orElse(null) ?: throw fail(HttpStatus.NOT_FOUND, "no dispatch")
            jdbc.sql("SELECT center_code FROM center WHERE center_code = :c FOR UPDATE").param("c", center)
                .query(String::class.java).list()
            val status = jdbc.sql("SELECT status FROM dispatch_plan WHERE plan_id = :id").param("id", planId)
                .query(String::class.java).single()
            if (status != "DRAFT") throw fail(HttpStatus.CONFLICT, "only a draft can change")
            change(center)
        }
    }

    private fun sequence(request: SequenceRequest): SequenceResult =
        try {
            sequencer?.sequence(request) ?: throw EngineUnavailable("route engine is not set up")
        } catch (e: EngineUnavailable) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "route engine did not answer", e)
        }

    private fun fail(status: HttpStatus, reason: String) = ResponseStatusException(status, reason)
}
