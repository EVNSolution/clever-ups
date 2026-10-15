package com.evnsolution.ups.addresses

import com.evnsolution.ups.centers.JdbcDriverStore
import com.evnsolution.ups.centers.JdbcJobCenterStore
import com.evnsolution.ups.centers.JdbcRouteStore
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/** The drivers, zones and Job centers come out of the master tables as the API models. */
class MasterStoresTest {
    private lateinit var source: HikariDataSource
    private lateinit var jdbc: JdbcClient
    private val zoneA = "a".repeat(36)
    private val zoneB = "b".repeat(36)
    private val driver1 = "1".repeat(36)
    private val driver2 = "2".repeat(36)

    @BeforeEach
    fun setUp() {
        source = HikariDataSource().apply {
            jdbcUrl = System.getenv("UPS_TEST_DB_URL")
                ?: "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            System.getenv("UPS_TEST_DB_USER")?.let { username = it }
            maximumPoolSize = 2
        }
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
        jdbc = JdbcClient.create(source)
        val masters = listOf("driver", "zone", "job_center", "center")
        (listOf("address_zone", "driver_zone", "driver_position", "driver_account") + masters).forEach {
            jdbc.sql("DELETE FROM $it").update()
        }
        jdbc.sql("INSERT INTO center (center_code, name_ko) VALUES ('KRSNA', '성남')").update()
        jdbc.sql("INSERT INTO center (center_code) VALUES ('KRHWN')").update()
        zone(zoneA, "정자1")
        zone(zoneB, "수내1")
        driver(driver1, "7001", zoneA)
        driver(driver2, "7002", null)
        serve(driver1, zoneA)
        serve(driver1, zoneB)
        serve(driver2, zoneB)
        jdbc.sql("INSERT INTO job_center (job_id, center_code, method) VALUES ('J1', 'KRSNA', 'MANUAL')").update()
    }

    private fun zone(id: String, name: String) {
        jdbc.sql("INSERT INTO zone (zone_id, center_code, name) VALUES (:id, 'KRSNA', :n)")
            .param("id", id).param("n", name).update()
    }

    private fun driver(id: String, code: String, main: String?) {
        jdbc.sql("INSERT INTO driver (driver_id, center_code, driver_code, main_zone_id) VALUES (:id, 'KRSNA', :c, :z)")
            .param("id", id).param("c", code).param("z", main).update()
    }

    private fun serve(driver: String, zone: String) {
        jdbc.sql("INSERT INTO driver_zone (driver_id, zone_id) VALUES (:d, :z)")
            .param("d", driver).param("z", zone).update()
    }

    @AfterEach
    fun tearDown() {
        jdbc.sql("DELETE FROM driver_position").update()
        source.close()
    }

    @Test
    fun `a driver carries the latest place the phone reported, and only the latest`() {
        val positions = com.evnsolution.ups.driverapp.DriverPositions(jdbc)
        positions.report(driver1, com.evnsolution.ups.driverapp.PositionIn(37.40, 127.10, 8.0))
        positions.report(driver1, com.evnsolution.ups.driverapp.PositionIn(37.41, 127.11))
        val drivers = JdbcDriverStore(jdbc).findAll("KRSNA").associateBy { it.id }
        assertEquals(37.41 to 127.11, drivers.getValue("7001").let { it.lat to it.lng })
        assertEquals(null, drivers.getValue("7002").positionAt)
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            positions.report(driver1, com.evnsolution.ups.driverapp.PositionIn(91.0, 127.0))
        }
    }

    @Test
    fun `a driver shows its main zone first and the other zones after it`() {
        val drivers = JdbcDriverStore(jdbc).findAll("KRSNA")
        assertEquals(listOf("7001", "7002"), drivers.map { it.id })
        assertEquals("정자1", drivers[0].routeCode)
        assertEquals(listOf("정자1", "수내1"), drivers[0].zones)
        assertEquals(null, drivers[1].routeCode)
        assertEquals(listOf("수내1"), drivers[1].zones)
        assertEquals(emptyList<String>(), JdbcDriverStore(jdbc).findAll("KRHWN").map { it.id })
    }

    @Test
    fun `a zone is a route with the drivers that serve it`() {
        val routes = JdbcRouteStore(jdbc).findAll("KRSNA")
        assertEquals(listOf("수내1", "정자1"), routes.map { it.code })
        assertEquals(listOf("7001", "7002"), routes.first { it.code == "수내1" }.driverIds)
        assertEquals(listOf("7001"), routes.first { it.code == "정자1" }.driverIds)
    }

    @Test
    fun `the center of a Job is read for the Jobs asked about only`() {
        assertEquals(mapOf("J1" to "KRSNA"), JdbcJobCenterStore(jdbc).centers(listOf("J1", "J2")))
        assertEquals(emptyMap<String, String>(), JdbcJobCenterStore(jdbc).centers(emptyList()))
    }
}
