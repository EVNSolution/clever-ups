package com.evnsolution.ups.addresses

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/** The rules the schema itself keeps: one center per Job, one row per task, and the address checks. */
class CenterSchemaTest {
    private lateinit var source: HikariDataSource
    private lateinit var jdbc: JdbcClient
    private val key = "a".repeat(64)

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
            "address_reference", "address_zone", "order_edit",
            "delivery_task", "job_center", "driver_zone", "driver_position",
            "driver_account", "driver", "zone",
            "center", "address_location",
        ).forEach { jdbc.sql("DELETE FROM $it").update() }
        jdbc.sql("INSERT INTO center (center_code) VALUES ('KRSNA')").update()
        jdbc.sql("INSERT INTO center (center_code) VALUES ('KRHWN')").update()
        jdbc.sql("INSERT INTO address_location (address_key, address1) VALUES (:k, 'x')").param("k", key).update()
    }

    @AfterEach
    fun tearDown() = source.close()

    @Test
    fun `a Job is given to one center once`() {
        jdbc.sql("INSERT INTO job_center (job_id, center_code, method) VALUES ('J1', 'KRSNA', 'DEFAULT')").update()
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.sql("INSERT INTO job_center (job_id, center_code, method) VALUES ('J1', 'KRHWN', 'MANUAL')").update()
        }
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.sql("INSERT INTO job_center (job_id, center_code, method) VALUES ('J2', 'NOPE', 'MANUAL')").update()
        }
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.sql("INSERT INTO job_center (job_id, center_code, method) VALUES ('J3', 'KRSNA', 'GUESS')").update()
        }
    }

    @Test
    fun `a task row exists once per Job and tracking number and points to a known address`() {
        val insert = "INSERT INTO delivery_task (task_key, job_id, row_no, tracking_number, address_key) " +
            "VALUES (:k, 'J1', 1, 'T1', :a)"
        jdbc.sql(insert).param("k", "J1:1").param("a", key).update()
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.sql(insert).param("k", "J1:2").param("a", key).update()
        }
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.sql(insert).param("k", "J1:3").param("a", "b".repeat(64)).update()
        }
    }

    @Test
    fun `the Korean address checks accept their values and refuse others`() {
        jdbc.sql("UPDATE address_location SET ko_source = 'TRANSLATOR', verify_status = 'MATCH' WHERE address_key = :k")
            .param("k", key).update()
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.sql("UPDATE address_location SET ko_source = 'GUESS' WHERE address_key = :k").param("k", key).update()
        }
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.sql("UPDATE address_location SET verify_status = 'MAYBE' WHERE address_key = :k")
                .param("k", key).update()
        }
        val untouched = jdbc.sql("SELECT COUNT(*) FROM address_location WHERE detail_ko IS NULL")
            .query(Int::class.java).single()
        assertEquals(1, untouched)
    }
}
