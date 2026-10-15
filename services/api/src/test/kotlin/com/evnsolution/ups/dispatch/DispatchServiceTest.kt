package com.evnsolution.ups.dispatch

import com.evnsolution.ups.centers.DepotPlaces
import com.evnsolution.ups.driverapp.DeliveryResults
import com.evnsolution.ups.driverapp.DriverRoutes
import com.evnsolution.ups.driverapp.ReportIn
import com.evnsolution.ups.geocoding.Geocoder
import com.evnsolution.ups.orders.JdbcTaskStore
import com.evnsolution.ups.orders.Order
import com.evnsolution.ups.sequence.SequenceResult
import com.evnsolution.ups.sequence.SequencedStop
import com.evnsolution.ups.sequence.StopSequencer
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID

/** Orders read become task rows, and a saved dispatch keeps each route's order, times and the loose orders. */
class DispatchServiceTest {
    private lateinit var source: HikariDataSource
    private lateinit var jdbc: JdbcClient
    private val day = LocalDate.parse("2026-10-14")
    private val actor = "9".repeat(36)

    // The engine visits the stops in reverse, 5 minutes and 1 km apart.
    private val reverse = StopSequencer { request ->
        val stops = request.stops.reversed().mapIndexed { i, stop ->
            SequencedStop(stop.id, request.startAt!!.plusMinutes(5L * (i + 1)), 1000L * (i + 1))
        }
        SequenceResult(stops, emptyList(), 1000L * stops.size, 300L * stops.size, "line")
    }

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
        listOf(
            "delivery_photo", "delivery_result", "dispatch_stop", "dispatch_route", "dispatch_plan",
            "order_edit", "delivery_task",
            "address_zone", "driver_zone",
            "driver_position", "driver_account", "driver", "zone", "job_center", "address_location", "center",
        ).forEach { jdbc.sql("DELETE FROM $it").update() }
        jdbc.sql("INSERT INTO center (center_code) VALUES ('KRSNA')").update()
        jdbc.sql("INSERT INTO zone (zone_id, center_code, name) VALUES ('${"z".repeat(36)}', 'KRSNA', '정자1')").update()
        jdbc.sql(
            "INSERT INTO driver (driver_id, center_code, driver_code, main_zone_id) " +
                "VALUES ('${"1".repeat(36)}', 'KRSNA', '7001', '${"z".repeat(36)}')",
        ).update()
        jdbc.sql("INSERT INTO driver (driver_id, center_code, driver_code) VALUES (:id, 'KRSNA', '7002')")
            .param("id", "2".repeat(36)).update()
        address("a", 37.42, 127.12)
        address("b", 37.43, 127.13)
        address("c", null, null)
        address("d", 37.44, 127.14)
        JdbcTaskStore(jdbc).record(listOf(order(1, "a"), order(2, "b"), order(3, "c"), order(4, "d")))
    }

    @AfterEach
    fun tearDown() {
        // A shared PostgreSQL (UPS_TEST_DB_URL) is used by the other tests too: leave no row that points at theirs.
        val mine = listOf("delivery_photo", "delivery_result", "dispatch_stop", "dispatch_route", "dispatch_plan")
        (mine + "order_edit" + "delivery_task").forEach {
            jdbc.sql("DELETE FROM $it").update()
        }
        source.close()
    }

    private fun address(letter: String, lat: Double?, lng: Double?) {
        jdbc.sql(
            "INSERT INTO address_location (address_key, address1, status, lat, lng) VALUES (:k, 'x', :s, :lat, :lng)",
        )
            .param("k", letter.repeat(64)).param("s", if (lat == null) "PENDING" else "DONE")
            .param("lat", lat).param("lng", lng).update()
    }

    private fun order(row: Int, letter: String) =
        Order(id = "J1:$row", trackingNumber = "T$row", jobId = "J1", status = "READY", addressKey = letter.repeat(64))

    private fun service(engine: StopSequencer? = reverse) = DispatchService(
        jdbc,
        TransactionTemplate(DataSourceTransactionManager(source)),
        engine,
        DepotPlaces(DefaultListableBeanFactory().getBeanProvider(Geocoder::class.java)),
    )

    private val input = DispatchIn(
        centerId = "KRSNA",
        day = day,
        departAt = "09:00",
        routes = listOf(RouteIn("7001", listOf("J1:1", "J1:2", "J1:3"))),
    )

    @Test
    fun `a saved run keeps the engine's order and times, and an order without a place is loose`() {
        val saved = service().save(actor, input)
        assertEquals(listOf("DRAFT", 1, 3, 1), listOf(saved.status, saved.routes, saved.stops, saved.unassigned))
        val stops = jdbc.sql(
            "SELECT task_key, seq, distance_m FROM dispatch_stop WHERE route_id IS NOT NULL ORDER BY seq",
        )
            .query { rs, _ -> Triple(rs.getString(1), rs.getInt(2), rs.getInt(3)) }.list()
        assertEquals(listOf(Triple("J1:2", 1, 1000), Triple("J1:1", 2, 2000)), stops)
        val code = jdbc.sql("SELECT route_code FROM dispatch_route").query(String::class.java).single()
        assertEquals("정자1", code)
    }

    @Test
    fun `a saved run reads back with each route in visiting order and the loose orders`() {
        val saved = service().save(actor, input)
        val run = service().reader.get(saved.id)!!
        assertEquals(saved, run.summary)
        val route = run.routes.single()
        assertEquals(listOf("7001", "정자1", "PLANNED"), listOf(route.driverId, route.routeCode, route.status))
        assertEquals(listOf("J1:2" to 1, "J1:1" to 2), route.stops.map { it.orderId to it.seq })
        assertEquals(listOf("J1:3"), run.unassigned)
        assertEquals(null, service().reader.get("x".repeat(36)))
    }

    @Test
    fun `a new run of the same day replaces the earlier draft`() {
        val first = service().save(actor, input)
        service().save(actor, input)
        val statuses = service().reader.list("KRSNA", day).associate { it.id to it.status }
        assertEquals("REPLACED", statuses[first.id])
        assertEquals(1, statuses.values.count { it == "DRAFT" })
    }

    @Test
    fun `an unknown order or driver is refused, and so is a run without an engine`() {
        val unknownOrder = input.copy(routes = listOf(RouteIn("7001", listOf("J9:1"))))
        val unknownDriver = input.copy(routes = listOf(RouteIn("9999", listOf("J1:1"))))
        val status = { run: () -> Unit -> assertThrows(ResponseStatusException::class.java, run).statusCode.value() }
        assertEquals(400, status { service().save(actor, unknownOrder) })
        assertEquals(400, status { service().save(actor, unknownDriver) })
        assertEquals(503, status { service(null).save(actor, input) })
    }

    @Test
    fun `sending keeps one SENT run per day, and a cancelled run cannot be sent`() {
        val service = service()
        val first = service.save(actor, input)
        service.states.send(first.id)
        assertEquals("SENT", service.reader.get(first.id)!!.summary.status)
        assertEquals("SENT", service.reader.get(first.id)!!.routes.single().status)
        val second = service.save(actor, input)
        service.states.send(second.id)
        assertEquals("REPLACED", service.reader.get(first.id)!!.summary.status)
        service.states.cancel(second.id)
        assertEquals("CANCELLED", service.reader.get(second.id)!!.summary.status)
        val status = { run: () -> Unit -> assertThrows(ResponseStatusException::class.java, run).statusCode.value() }
        assertEquals(409, status { service.states.send(second.id) })
        assertEquals(404, status { service.states.cancel("x".repeat(36)) })
    }

    @Test
    fun `a draft's route can go to a free driver and take unassigned orders, and a sent run cannot change`() {
        val service = service()
        val run = service.save(actor, input.copy(unassigned = listOf("J1:4")))
        service.edits.changeDriver(run.id, "7001", "7002")
        val moved = service.reader.get(run.id)!!.routes.single()
        assertEquals("7002" to null, moved.driverId to moved.routeCode)
        service.edits.addOrders(run.id, "7002", listOf("J1:4"))
        val grown = service.reader.get(run.id)!!
        val stops = grown.routes.single().stops
        assertEquals(setOf("J1:1", "J1:2", "J1:4"), stops.map { it.orderId }.toSet())
        assertEquals(listOf(1, 2, 3), stops.map { it.seq })
        assertEquals(listOf("J1:3"), grown.unassigned)
        val status = { run: () -> Unit -> assertThrows(ResponseStatusException::class.java, run).statusCode.value() }
        assertEquals(400, status { service.edits.addOrders(run.id, "7002", listOf("J1:3")) })
        assertEquals(400, status { service.edits.changeDriver(run.id, "7002", "9999") })
        service.states.send(run.id)
        assertEquals(409, status { service.edits.changeDriver(run.id, "7002", "7001") })
    }

    @Test
    fun `a driver reads the route only once the run is sent, with addresses and no names`() {
        val routes = DriverRoutes(jdbc)
        val driver = "1".repeat(36)
        val run = service().save(actor, input)
        assertEquals(null, routes.of(driver, "KRSNA", day))
        service().states.send(run.id)
        val route = routes.of(driver, "KRSNA", day)!!
        assertEquals(listOf("J1:2", "J1:1"), route.stops.map { it.orderId })
        assertEquals(listOf("T2", "x"), listOf(route.stops.first().trackingNumber, route.stops.first().address1))
        assertEquals(null, routes.of(driver, "KRSNA", day.plusDays(1)))
    }

    @Test
    fun `a driver reports arrival, delivery, an exception and a photo on the own sent route only`() {
        val photos = java.nio.file.Files.createTempDirectory("photos")
        val results = DeliveryResults(
            jdbc,
            TransactionTemplate(DataSourceTransactionManager(source)),
            photos,
        )
        val me = "1".repeat(36)
        val other = "2".repeat(36)
        val run = service().save(actor, input)
        val status = { run: () -> Unit -> assertThrows(ResponseStatusException::class.java, run).statusCode.value() }
        assertEquals(404, status { results.arrive(me, run.id, "J1:1", null, null) }, "a draft is not on the phone")
        service().states.send(run.id)
        results.arrive(me, run.id, "J1:1", 37.42, 127.12)
        results.report(me, run.id, "J1:1", ReportIn("DELIVERED", null, "R", "FACE", "KIM"))
        results.report(me, run.id, "J1:2", ReportIn("EXCEPTION", "48", "C"))
        val photo = results.addPhoto(me, run.id, "J1:1", "image/jpeg", byteArrayOf(1, 2, 3))
        val done = results.of(run.id)
        val first = done.getValue("J1:1")
        assertEquals(listOf("DELIVERED", "KB"), listOf(first.outcome, first.code))
        assertEquals(listOf("FACE", "KIM"), listOf(first.podMethod, first.recipientName))
        assertEquals(listOf(photo), done.getValue("J1:1").photoIds)
        assertEquals(listOf("EXCEPTION", "48"), done.getValue("J1:2").let { listOf(it.outcome, it.code) })
        assertEquals(3, results.photo(run.id, photo)!!.bytes.size)
        val bad = ReportIn("EXCEPTION", "KB")
        assertEquals(400, status { results.report(me, run.id, "J1:1", bad) })
        val faceless = ReportIn("DELIVERED", null, "R", "FACE", " ")
        assertEquals(400, status { results.report(me, run.id, "J1:1", faceless) })
        assertEquals(404, status { results.arrive(other, run.id, "J1:1", null, null) }, "another driver's stop")
    }

    @Test
    fun `reading the same orders again keeps one row each and follows the status`() {
        JdbcTaskStore(jdbc).record(listOf(order(1, "a").copy(status = "DONE")))
        val rows = jdbc.sql("SELECT task_key, status FROM delivery_task ORDER BY task_key")
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }.list()
        assertEquals(listOf("J1:1" to "DONE", "J1:2" to "READY", "J1:3" to "READY", "J1:4" to "READY"), rows)
    }
}
