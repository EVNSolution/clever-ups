package com.evnsolution.ups.boundaries

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** 행정동 squares become 행정동, 시·군·구 and 시 rows, with parents and joined shapes, and are written in order. */
class BoundariesTest {
    // Two 일반구 of one 시, side by side, one 행정동 each; and a 군 of two 행정동.
    private val file = """
        {"type":"FeatureCollection","features":[
          ${dong("4113151000", "41131", "성남시수정구", "경기도 성남시수정구 가동", 0, 0)},
          ${dong("4113352000", "41133", "성남시중원구", "경기도 성남시중원구 나동", 1, 0)},
          ${dong("4182025000", "41820", "가평군", "경기도 가평군 다동", 5, 5)},
          ${dong("4182026000", "41820", "가평군", "경기도 가평군 라동", 5, 6)}
        ]}
    """.trimIndent()

    private fun dong(code: String, sgg: String, sggName: String, name: String, x: Int, y: Int) =
        """{"type":"Feature","properties":{"adm_cd2":"$code","adm_cd":"${code.take(8)}","adm_nm":"$name",""" +
            """"sgg":"$sgg","sggnm":"$sggName","sido":"41","sidonm":"경기도"},"geometry":{"type":"MultiPolygon",""" +
            """"coordinates":[[[[$x,$y],[${x + 1},$y],[${x + 1},${y + 1}],[$x,${y + 1}],[$x,$y]]]]}}"""

    @Test
    fun `일반구 make a 시, and every 행정동 hangs under its 시·군·구`() {
        val rows = AdmDongKor.read(file).associateBy { it.code }
        val levels = AdmDongKor.read(file).map { it.level }
        assertEquals(listOf("CITY", "SGG", "SGG", "SGG", "DONG", "DONG", "DONG", "DONG"), levels)
        assertEquals("경기도 성남시", rows.getValue("41130").name)
        assertEquals(2.0, rows.getValue("41130").geometry.area, 1e-9)
        assertEquals("경기도 성남시 수정구", rows.getValue("41131").name)
        assertEquals("41130", rows.getValue("41131").parentCode)
        assertEquals(null, rows.getValue("41820").parentCode)
        assertEquals(2.0, rows.getValue("41820").geometry.area, 1e-9)
        assertEquals("41820", rows.getValue("4182026000").parentCode)
        assertEquals("41820260", rows.getValue("4182026000").statCode)
    }

    @Test
    fun `a 구 name without a 시 part stays whole`() {
        assertEquals("종로구" to null, AdmDongKor.split("종로구"))
        assertEquals("가평군" to null, AdmDongKor.split("가평군"))
        assertEquals("고양시" to "일산동구", AdmDongKor.split("고양시일산동구"))
    }

    @Test
    fun `an import replaces every row`() {
        HikariDataSource().apply {
            jdbcUrl = System.getenv("UPS_TEST_DB_URL")
                ?: "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            System.getenv("UPS_TEST_DB_USER")?.let { username = it }
            maximumPoolSize = 2
        }.use { source ->
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
            val jdbc = JdbcClient.create(source)
            val transactions = TransactionTemplate(DataSourceTransactionManager(source))
            val store = BoundaryStore(jdbc, transactions, Clock.systemUTC())
            val rows = AdmDongKor.read(file)
            store.replace(rows, "test", LocalDate.parse("2026-07-01"))
            store.replace(rows, "test", LocalDate.parse("2026-07-01"))
            val count = jdbc.sql("SELECT COUNT(*) FROM admin_boundary").query(Int::class.java).single()
            assertEquals(8, count)
            val box = jdbc.sql("SELECT min_lng, max_lng FROM admin_boundary WHERE code = '41130'")
                .query { rs, _ -> rs.getDouble(1) to rs.getDouble(2) }.single()
            assertEquals(0.0 to 2.0, box)
            jdbc.sql("DELETE FROM admin_boundary WHERE level = 'DONG'").update()
            jdbc.sql("DELETE FROM admin_boundary WHERE level = 'SGG'").update()
            jdbc.sql("DELETE FROM admin_boundary").update()
        }
    }
}
