package com.evnsolution.ups.centers

import com.evnsolution.ups.drivers.Driver
import com.evnsolution.ups.drivers.DriverStore
import com.evnsolution.ups.orders.JobCenterStore
import com.evnsolution.ups.routes.Route
import com.evnsolution.ups.routes.RouteStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import javax.sql.DataSource

/** Drivers, zones and the center of each Job, read from the master tables. On only with a database. */
@Configuration
@ConditionalOnProperty("ups.db.url")
class MasterStores {
    @Bean
    @Primary
    fun jdbcDrivers(dataSource: DataSource): DriverStore = JdbcDriverStore(JdbcClient.create(dataSource))

    @Bean
    @Primary
    fun jdbcRoutes(dataSource: DataSource): RouteStore = JdbcRouteStore(JdbcClient.create(dataSource))

    @Bean
    @Primary
    fun jdbcJobCenters(dataSource: DataSource): JobCenterStore = JdbcJobCenterStore(JdbcClient.create(dataSource))
}

/** The driver code is the id and the name. The route code of a driver is the name of its main zone. */
class JdbcDriverStore(private val jdbc: JdbcClient) : DriverStore {
    override fun findAll(centerId: String?): List<Driver> {
        val zones = jdbc.sql(
            "SELECT dz.driver_id, z.name FROM driver_zone dz JOIN zone z ON z.zone_id = dz.zone_id ORDER BY z.name",
        ).query { rs, _ -> rs.getString("driver_id").trim() to rs.getString("name") }.list()
            .groupBy({ it.first }, { it.second })
        val sql = "SELECT d.driver_id, d.center_code, d.driver_code, z.name AS main_zone, " +
            "p.lat, p.lng, p.recorded_at FROM driver d " +
            "LEFT JOIN zone z ON z.zone_id = d.main_zone_id LEFT JOIN driver_position p ON p.driver_id = d.driver_id " +
            (if (centerId == null) "" else "WHERE d.center_code = :center ") + "ORDER BY d.center_code, d.driver_code"
        val spec = jdbc.sql(sql).let { if (centerId == null) it else it.param("center", centerId) }
        return spec.query { rs, _ ->
            val id = rs.getString("driver_id").trim()
            val main = rs.getString("main_zone")
            val all = zones[id].orEmpty()
            Driver(
                id = rs.getString("driver_code"),
                centerId = rs.getString("center_code"),
                routeCode = main,
                lng = rs.getObject("lng") as Double?,
                lat = rs.getObject("lat") as Double?,
                positionAt = rs.getObject("recorded_at", java.time.OffsetDateTime::class.java)?.toInstant(),
                zones = if (main == null) all else listOf(main) + all.filter { it != main },
            )
        }.list()
    }
}

/** A route is a zone. Its code is the zone name and its drivers are the drivers that serve it. */
class JdbcRouteStore(private val jdbc: JdbcClient) : RouteStore {
    private data class Row(val zoneId: String, val centerCode: String, val name: String, val driverCode: String?)

    override fun findAll(centerId: String?): List<Route> {
        val sql = "SELECT z.zone_id, z.center_code, z.name, d.driver_code FROM zone z " +
            "LEFT JOIN driver_zone dz ON dz.zone_id = z.zone_id LEFT JOIN driver d ON d.driver_id = dz.driver_id " +
            (if (centerId == null) "" else "WHERE z.center_code = :center ") +
            "ORDER BY z.center_code, z.name, d.driver_code"
        val spec = jdbc.sql(sql).let { if (centerId == null) it else it.param("center", centerId) }
        val rows = spec.query { rs, _ ->
            Row(
                rs.getString("zone_id").trim(),
                rs.getString("center_code"),
                rs.getString("name"),
                rs.getString("driver_code"),
            )
        }.list()
        return rows.groupBy { it.zoneId }.map { (id, group) ->
            Route(id, group.first().centerCode, group.first().name, group.mapNotNull { it.driverCode })
        }
    }
}

class JdbcJobCenterStore(private val jdbc: JdbcClient) : JobCenterStore {
    override fun centers(jobIds: Collection<String>): Map<String, String> =
        if (jobIds.isEmpty()) {
            emptyMap()
        } else {
            jobIds.chunked(CHUNK).flatMap { chunk ->
                jdbc.sql("SELECT job_id, center_code FROM job_center WHERE job_id IN (:ids)").param("ids", chunk)
                    .query { rs, _ -> rs.getString("job_id") to rs.getString("center_code") }.list()
            }.toMap()
        }

    private companion object {
        const val CHUNK = 500
    }
}
