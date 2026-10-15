package com.evnsolution.ups.dispatch

import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime

/** Reads saved dispatches: the runs of a day, and one run with its routes and stops as they were computed. */
class DispatchReader(private val jdbc: JdbcClient) {
    fun list(centerId: String, day: LocalDate): List<DispatchSummary> =
        jdbc.sql(
            "SELECT p.*, (SELECT COUNT(*) FROM dispatch_route r WHERE r.plan_id = p.plan_id) AS routes, " +
                "(SELECT COUNT(*) FROM dispatch_stop s WHERE s.plan_id = p.plan_id) AS stops, " +
                "(SELECT COUNT(*) FROM dispatch_stop s WHERE s.plan_id = p.plan_id AND s.route_id IS NULL) AS loose " +
                "FROM dispatch_plan p WHERE p.center_code = :center AND p.service_day = :day " +
                "ORDER BY p.created_at DESC",
        ).param("center", centerId).param("day", day).query { rs, _ -> summary(rs) }.list()

    /** The center of a saved dispatch, or null when there is none with this ID. */
    fun centerOf(planId: String): String? =
        jdbc.sql("SELECT center_code FROM dispatch_plan WHERE plan_id = :id")
            .param("id", planId).query(String::class.java).optional().orElse(null)

    /** A saved dispatch with its routes in driver order and each route's stops in visiting order. */
    fun get(planId: String): DispatchOut? {
        val plan = jdbc.sql("SELECT plan_id, center_code, service_day FROM dispatch_plan WHERE plan_id = :id")
            .param("id", planId)
            .query { rs, _ -> rs.getString("center_code") to rs.getObject("service_day", LocalDate::class.java) }
            .optional().orElse(null) ?: return null
        val summary = list(plan.first, plan.second).first { it.id == planId }
        val stops = jdbc.sql(
            "SELECT route_id, task_key, seq, planned_arrival, distance_m FROM dispatch_stop " +
                "WHERE plan_id = :id ORDER BY route_id, seq, task_key",
        ).param("id", planId).query { rs, _ ->
            rs.getString("route_id")?.trim() to StopOut(
                rs.getString("task_key"),
                rs.getInt("seq"),
                rs.getObject("planned_arrival", OffsetDateTime::class.java) ?: OffsetDateTime.MIN,
                rs.getInt("distance_m"),
            )
        }.list()
        val byRoute = stops.filter { it.first != null }.groupBy({ it.first }, { it.second })
        val routes = jdbc.sql(
            "SELECT r.*, d.driver_code FROM dispatch_route r JOIN driver d ON d.driver_id = r.driver_id " +
                "WHERE r.plan_id = :id ORDER BY d.driver_code",
        ).param("id", planId).query { rs, _ -> route(rs, byRoute[rs.getString("route_id").trim()].orEmpty()) }.list()
        return DispatchOut(summary, routes, stops.filter { it.first == null }.map { it.second.orderId })
    }

    private fun route(rs: ResultSet, stops: List<StopOut>): RouteOut {
        val lng = rs.getObject("start_lng") as Double?
        val lat = rs.getObject("start_lat") as Double?
        return RouteOut(
            driverId = rs.getString("driver_code"),
            routeCode = rs.getString("route_code"),
            status = rs.getString("status"),
            departAt = rs.getObject("depart_at", OffsetDateTime::class.java),
            start = if (lng == null || lat == null) null else PlaceOut(lng, lat),
            distanceMeters = rs.getInt("distance_m"),
            durationSeconds = rs.getInt("duration_s"),
            geometry = rs.getString("geometry"),
            stops = stops,
        )
    }

    private fun summary(rs: ResultSet) = DispatchSummary(
        id = rs.getString("plan_id"),
        centerId = rs.getString("center_code"),
        day = rs.getObject("service_day", LocalDate::class.java),
        method = rs.getString("method"),
        status = rs.getString("status"),
        departAt = rs.getObject("depart_at", OffsetDateTime::class.java),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        routes = rs.getInt("routes"),
        stops = rs.getInt("stops"),
        unassigned = rs.getInt("loose"),
    )

}
