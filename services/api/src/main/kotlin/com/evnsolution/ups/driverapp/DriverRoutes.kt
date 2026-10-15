package com.evnsolution.ups.driverapp

import com.evnsolution.ups.dispatch.PlaceOut
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime

/** One stop as the driver sees it: the place to go and the parcel, with no customer name or phone number. */
data class DriverStop(
    val orderId: String,
    val seq: Int,
    val arrival: OffsetDateTime,
    val distanceMeters: Int,
    val trackingNumber: String,
    val addressKo: String?,
    val detailKo: String?,
    val address1: String,
    val address2: String,
    val postalCode: String,
    val lng: Double?,
    val lat: Double?,
    /** What the driver has reported here, if anything. */
    val result: ResultOut? = null,
)

/** The driver's route of a day, from the run that was sent for the driver's center. */
data class DriverRoute(
    val planId: String,
    val day: LocalDate,
    val driverId: String,
    val routeCode: String?,
    val departAt: OffsetDateTime,
    val start: PlaceOut?,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val geometry: String?,
    val stops: List<DriverStop>,
)

/** Reads the route a driver got: only from a SENT run, so a draft or a cancelled run never reaches a phone. */
class DriverRoutes(private val jdbc: JdbcClient) {
    fun of(driverId: String, centerCode: String, day: LocalDate): DriverRoute? {
        // A SENT run per center and day is one at most, so the driver has one route at most.
        val routeId = jdbc.sql(
            "SELECT r.route_id FROM dispatch_route r JOIN dispatch_plan p ON p.plan_id = r.plan_id " +
                "WHERE r.driver_id = :driver AND p.center_code = :center AND p.service_day = :day " +
                "AND p.status = 'SENT'",
        ).param("driver", driverId).param("center", centerCode).param("day", day)
            .query(String::class.java).list().firstOrNull()
        return routeId?.let { read(it.trim()) }
    }

    private fun read(routeId: String): DriverRoute? {
        val stops = jdbc.sql(
            "SELECT s.task_key, s.seq, s.planned_arrival, s.distance_m, t.tracking_number, a.address_ko, " +
                "a.detail_ko, a.address1, a.address2, a.postal_code, a.lng, a.lat FROM dispatch_stop s " +
                "JOIN delivery_task t ON t.task_key = s.task_key " +
                "JOIN address_location a ON a.address_key = t.address_key " +
                "WHERE s.route_id = :route ORDER BY s.seq",
        ).param("route", routeId).query { rs, _ -> stop(rs) }.list()
        return jdbc.sql(
            "SELECT r.*, p.service_day, d.driver_code FROM dispatch_route r " +
                "JOIN dispatch_plan p ON p.plan_id = r.plan_id JOIN driver d ON d.driver_id = r.driver_id " +
                "WHERE r.route_id = :route",
        ).param("route", routeId).query { rs, _ ->
            val lng = rs.getObject("start_lng") as Double?
            val lat = rs.getObject("start_lat") as Double?
            DriverRoute(
                planId = rs.getString("plan_id").trim(),
                day = rs.getObject("service_day", LocalDate::class.java),
                driverId = rs.getString("driver_code"),
                routeCode = rs.getString("route_code"),
                departAt = rs.getObject("depart_at", OffsetDateTime::class.java),
                start = if (lng == null || lat == null) null else PlaceOut(lng, lat),
                distanceMeters = rs.getInt("distance_m"),
                durationSeconds = rs.getInt("duration_s"),
                geometry = rs.getString("geometry"),
                stops = stops,
            )
        }.optional().orElse(null)
    }

    private fun stop(rs: ResultSet) = DriverStop(
        orderId = rs.getString("task_key"),
        seq = rs.getInt("seq"),
        arrival = rs.getObject("planned_arrival", OffsetDateTime::class.java),
        distanceMeters = rs.getInt("distance_m"),
        trackingNumber = rs.getString("tracking_number"),
        addressKo = rs.getString("address_ko"),
        detailKo = rs.getString("detail_ko"),
        address1 = rs.getString("address1"),
        address2 = rs.getString("address2"),
        postalCode = rs.getString("postal_code"),
        lng = rs.getObject("lng") as Double?,
        lat = rs.getObject("lat") as Double?,
    )
}

/** Where the phone is, as it reports it. */
data class PositionIn(val lat: Double? = null, val lng: Double? = null, val accuracy: Double? = null)

/** Keeps the latest place of each driver. The row is replaced at every report; nothing older is kept. */
class DriverPositions(private val jdbc: JdbcClient) {
    fun report(driverId: String, position: PositionIn) {
        val lat = position.lat
        val lng = position.lng
        require(lat != null && lng != null && lat in -MAX_LAT..MAX_LAT && lng in -MAX_LNG..MAX_LNG) {
            "a position needs lat and lng in range"
        }
        val changed = jdbc.sql(
            "UPDATE driver_position SET lat = :lat, lng = :lng, accuracy_m = :acc, recorded_at = CURRENT_TIMESTAMP " +
                "WHERE driver_id = :driver",
        ).param("lat", lat).param("lng", lng).param("acc", position.accuracy).param("driver", driverId).update()
        if (changed == 0) {
            jdbc.sql(
                "INSERT INTO driver_position (driver_id, lat, lng, accuracy_m, recorded_at) " +
                    "VALUES (:driver, :lat, :lng, :acc, CURRENT_TIMESTAMP)",
            ).param("driver", driverId).param("lat", lat).param("lng", lng).param("acc", position.accuracy).update()
        }
    }

    private companion object {
        const val MAX_LAT = 90.0
        const val MAX_LNG = 180.0
    }
}
