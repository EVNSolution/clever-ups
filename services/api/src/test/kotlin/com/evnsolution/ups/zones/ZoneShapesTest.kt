package com.evnsolution.ups.zones

import com.evnsolution.ups.boundaries.Boundary
import com.evnsolution.ups.boundaries.BoundaryReader
import com.evnsolution.ups.boundaries.BoundaryStore
import com.evnsolution.ups.boundaries.GeoJson
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** Zones made of boundaries or drawn: never over another zone of the same level, and 세부권역 inside their 권역. */
class ZoneShapesTest {
    private lateinit var source: HikariDataSource
    private lateinit var jdbc: JdbcClient
    private lateinit var zones: ZoneShapes
    private val mapper = JsonMapper.builder().build()
    private val actor = "9".repeat(36)

    private fun square(x: Double, y: Double, size: Double = 1.0): Geometry {
        val corners = listOf(x to y, x + size to y, x + size to y + size, x to y + size, x to y)
        return GeoJson.factory.createPolygon(corners.map { Coordinate(it.first, it.second) }.toTypedArray())
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
        cleanUp()
        jdbc.sql("INSERT INTO center (center_code) VALUES ('ZTEST')").update()
        // Two 행정동 side by side, x 0..1 and 1..2.
        val transactions = TransactionTemplate(DataSourceTransactionManager(source))
        BoundaryStore(jdbc, transactions, Clock.systemUTC()).replace(
            listOf(
                Boundary("Z1", "SGG", "테스트구", null, null, square(0.0, 0.0, 2.0)),
                Boundary("Z11", "DONG", "가동", "Z1", null, square(0.0, 0.0)),
                Boundary("Z12", "DONG", "나동", "Z1", null, square(1.0, 0.0)),
            ),
            "test",
            LocalDate.parse("2026-07-01"),
        )
        zones = ZoneShapes(jdbc, BoundaryReader(jdbc), Clock.systemUTC())
    }

    private fun cleanUp() {
        jdbc.sql("DELETE FROM zone WHERE center_code = 'ZTEST' AND parent_zone_id IS NOT NULL").update()
        jdbc.sql("DELETE FROM zone WHERE center_code = 'ZTEST'").update()
        jdbc.sql("DELETE FROM center WHERE center_code = 'ZTEST'").update()
        jdbc.sql("DELETE FROM admin_boundary WHERE level = 'DONG' AND code LIKE 'Z%'").update()
        jdbc.sql("DELETE FROM admin_boundary WHERE code LIKE 'Z%'").update()
    }

    @AfterEach
    fun tearDown() {
        cleanUp()
        source.close()
    }

    private fun area(zone: ZoneOut): Double = GeoJson.read(zone.shape!!).area

    private fun drawn(x: Double, y: Double, size: Double) =
        ShapeIn(polygon = mapper.readTree(GeoJson.write(square(x, y, size))))

    @Test
    fun `a zone made of 행정동 is their union, and a later zone is cut where it overlaps`() {
        val first = zones.create(ZoneIn("ZTEST", "분당"))
        assertEquals(2.0, area(zones.reshape(first.id, actor, ShapeIn(listOf("Z11", "Z12")))), 1e-9)
        val second = zones.create(ZoneIn("ZTEST", "수정"))
        // Drawn as x 1..3, y 0..2 (area 4): the part over the first zone (x 1..2, y 0..1) is cut away.
        assertEquals(3.0, area(zones.reshape(second.id, actor, drawn(1.0, 0.0, 2.0))), 1e-9)
    }

    @Test
    fun `a 세부권역 stays inside its 권역 and away from its siblings`() {
        val parent = zones.create(ZoneIn("ZTEST", "분당"))
        zones.reshape(parent.id, actor, ShapeIn(listOf("Z11")))
        val a = zones.create(ZoneIn("ZTEST", "정자1", parent.id))
        assertEquals(1.0, area(zones.reshape(a.id, actor, ShapeIn(listOf("Z11", "Z12")))), 1e-9)
        val b = zones.create(ZoneIn("ZTEST", "정자2", parent.id))
        assertThrows(ZoneConflict::class.java) { zones.reshape(b.id, actor, ShapeIn(listOf("Z11"))) }
    }

    @Test
    fun `names are unique in a center, a parent is a 권역 of the same center, and no input clears the shape`() {
        val first = zones.create(ZoneIn("ZTEST", "분당"))
        assertThrows(ZoneConflict::class.java) { zones.create(ZoneIn("ZTEST", " 분당 ")) }
        val child = zones.create(ZoneIn("ZTEST", "정자1", first.id))
        assertThrows(ZoneInvalid::class.java) { zones.create(ZoneIn("ZTEST", "정자1-1", child.id)) }
        assertThrows(ZoneInvalid::class.java) { zones.reshape(first.id, actor, ShapeIn(listOf("NOPE"))) }
        zones.reshape(first.id, actor, ShapeIn(listOf("Z11")))
        assertEquals(null, zones.reshape(first.id, actor, ShapeIn()).shape)
    }
}
