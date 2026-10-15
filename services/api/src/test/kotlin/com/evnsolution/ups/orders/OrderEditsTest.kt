package com.evnsolution.ups.orders

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Corrected cells show over the Ours24 row, and edited names go 90 days after their operating day. */
class OrderEditsTest {
    private lateinit var source: HikariDataSource
    private lateinit var jdbc: JdbcClient
    private lateinit var edits: JdbcOrderEdits
    private val actor = "9".repeat(36)
    private val seoul = ZoneId.of("Asia/Seoul")
    private val tasks = listOf("edit-job:1", "edit-job:2")

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
        jdbc.sql("INSERT INTO address_location (address_key, address1) VALUES (:k, 'x')").param("k", "e".repeat(64))
            .update()
        tasks.forEachIndexed { i, key ->
            jdbc.sql(
                "INSERT INTO delivery_task (task_key, job_id, row_no, tracking_number, address_key) " +
                    "VALUES (:t, 'edit-job', :n, :t, :k)",
            ).param("t", key).param("n", i + 1).param("k", "e".repeat(64)).update()
        }
        edits = editsOn("2026-10-15")
    }

    private fun editsOn(day: String) = JdbcOrderEdits(
        jdbc,
        TransactionTemplate(DataSourceTransactionManager(source)),
        Clock.fixed(Instant.parse("${day}T03:00:00Z"), seoul),
    )

    private fun cleanUp() {
        jdbc.sql("DELETE FROM order_edit WHERE task_key IN (:t)").param("t", tasks).update()
        jdbc.sql("DELETE FROM delivery_task WHERE task_key IN (:t)").param("t", tasks).update()
        jdbc.sql("DELETE FROM address_location WHERE address_key = :k").param("k", "e".repeat(64)).update()
    }

    @AfterEach
    fun tearDown() {
        cleanUp()
        source.close()
    }

    @Test
    fun `corrected cells replace the Ours24 values and are listed`() {
        edits.save(actor, LocalDate.parse("2026-10-15"), listOf(
            OrderEditIn(tasks[0], "customerName", "Kim Fixed"),
            OrderEditIn(tasks[0], "postalCode", "13100"),
        ))
        val shown = edits.apply(listOf(
            Order(tasks[0], "T1", customerName = "Kim", companyName = "ACME", postalCode = "1"),
            Order(tasks[1], "T2", customerName = "Lee"),
        ))
        assertEquals(Order(tasks[0], "T1", customerName = "Kim Fixed", companyName = "ACME", postalCode = "13100",
            edited = listOf("customerName", "postalCode")), shown[0])
        assertEquals(Order(tasks[1], "T2", customerName = "Lee"), shown[1])
    }

    @Test
    fun `edited names go 90 days after the operating day, other cells stay`() {
        edits.save(actor, LocalDate.parse("2026-07-17"), listOf(
            OrderEditIn(tasks[0], "customerName", "Kim Fixed"),
            OrderEditIn(tasks[0], "companyName", "ACME Fixed"),
            OrderEditIn(tasks[0], "address1", "1 Fixed-ro"),
        ))
        edits.save(actor, LocalDate.parse("2026-07-18"), listOf(OrderEditIn(tasks[1], "customerName", "Lee Fixed")))
        editsOn("2026-10-15").purge()
        val left = jdbc.sql("SELECT task_key, field_name FROM order_edit WHERE task_key IN (:t) ORDER BY 1, 2")
            .param("t", tasks).query { rs, _ -> rs.getString(1) + "/" + rs.getString(2) }.list()
        assertEquals(listOf("${tasks[0]}/address1", "${tasks[1]}/customerName"), left)
    }
}
