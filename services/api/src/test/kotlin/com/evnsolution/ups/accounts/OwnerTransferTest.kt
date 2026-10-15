package com.evnsolution.ups.accounts

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

/** The terminal-only hand-over of the owner role, on a real schema (H2, or PostgreSQL with UPS_TEST_DB_URL). */
class OwnerTransferTest {
    private lateinit var source: HikariDataSource
    private lateinit var jdbc: JdbcClient
    private lateinit var service: OwnerTransferService
    private lateinit var accounts: AccountService
    private val password = "Long-Enough-Pw-1"

    @BeforeEach
    fun setUp() {
        source = HikariDataSource().apply {
            jdbcUrl = System.getenv("UPS_TEST_DB_URL")
                ?: "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            System.getenv("UPS_TEST_DB_USER")?.let { username = it }
            maximumPoolSize = 4
        }
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
        jdbc = JdbcClient.create(source)
        val transactions = TransactionTemplate(DataSourceTransactionManager(source))
        listOf(
            "access_decision", "account_center", "access_request", "session", "membership", "password_credential",
            "login_attempt", "driver_account", "account",
        ).forEach { jdbc.sql("DELETE FROM $it").update() }
        accounts = AccountService(jdbc, transactions, PasswordHasher(), Clock.systemUTC())
        service = OwnerTransferService(jdbc, transactions, Clock.systemUTC())
    }

    @AfterEach
    fun tearDown() = source.close()

    private fun role(loginId: String): String? =
        jdbc.sql("SELECT m.role FROM membership m JOIN account a ON a.account_id = m.account_id WHERE a.login_key = :k")
            .param("k", loginId).query(String::class.java).optional().orElse(null)

    @Test
    fun `the owner role moves, the old owner becomes an admin and the centers are set`() {
        accounts.createOwner("first-owner", password)
        val next = accounts.insertAccount("next-owner", accounts.hash(password))
        service.transfer("Next-Owner", listOf("krhwn", "KRSNA", "krhwn"))
        assertEquals("owner", role("next-owner"))
        assertEquals("admin", role("first-owner"))
        val centers = jdbc.sql("SELECT center_code FROM account_center WHERE account_id = :a ORDER BY center_code")
            .param("a", next.id).query(String::class.java).list()
        assertEquals(listOf("KRHWN", "KRSNA"), centers)
        val owners = jdbc.sql("SELECT COUNT(*) FROM membership WHERE role = 'owner'").query(Int::class.java).single()
        assertEquals(1, owners)
        val audit = jdbc.sql("SELECT COUNT(*) FROM access_decision WHERE evidence = 'owner-transfer'")
            .query(Int::class.java).single()
        assertEquals(2, audit)
    }

    @Test
    fun `an unknown account, the current owner and a bad center change nothing`() {
        accounts.createOwner("only-owner", password)
        assertThrows(NotFound::class.java) { service.transfer("nobody", listOf("KRHWN")) }
        assertThrows(StateConflict::class.java) { service.transfer("only-owner", listOf("KRHWN")) }
        accounts.insertAccount("other", accounts.hash(password))
        assertThrows(BadDecision::class.java) { service.transfer("other", listOf("x")) }
        assertEquals("owner", role("only-owner"))
        assertEquals(null, role("other"))
    }
}
