package com.evnsolution.ups.phone

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the real migrations on an in-memory H2 in PostgreSQL mode. Set UPS_TEST_DB_URL (and UPS_TEST_DB_USER)
 * to run the same tests on a real PostgreSQL; the tables are emptied before each test.
 */
class PhoneSendTest {
    private lateinit var source: HikariDataSource
    private lateinit var jdbc: JdbcClient
    private val t0 = Instant.parse("2026-10-12T00:00:00Z")
    private val phone = "+821012345678"

    @BeforeEach
    fun setUp() {
        source = HikariDataSource().apply {
            jdbcUrl = System.getenv("UPS_TEST_DB_URL")
                ?: "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000"
            System.getenv("UPS_TEST_DB_USER")?.let { username = it }
            maximumPoolSize = POOL
        }
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
        jdbc = JdbcClient.create(source)
        jdbc.sql("DELETE FROM phone_send").update()
    }

    @AfterEach
    fun tearDown() = source.close()

    private fun ledger(limits: SendLimits = SendLimits()) =
        PhoneSendLedger(jdbc, TransactionTemplate(DataSourceTransactionManager(source)), limits)

    private fun number(i: Int) = "+8210%08d".format(i)

    @Test
    fun `the daily cap stops the eleventh message`() {
        val ledger = ledger(SendLimits(daily = 10, perAccount = 100))
        val results = (1..11).map { ledger.reserve("a$it", number(it), "k$it", t0) }
        assertEquals(10, results.count { it is Reservation.Granted })
        assertEquals(Reservation.Denied(DenyReason.DAILY_LIMIT, null), results.last())
    }

    @Test
    fun `the cap is a rolling window and frees up after it`() {
        val ledger = ledger(SendLimits(daily = 2, perAccount = 100))
        ledger.reserve("a1", number(1), "k1", t0)
        ledger.reserve("a2", number(2), "k2", t0.plusSeconds(60))
        assertInstanceOf(Reservation.Denied::class.java, ledger.reserve("a3", number(3), "k3", t0.plusSeconds(3600)))
        val later = ledger.reserve("a3", number(3), "k4", t0.plus(Duration.ofHours(24)).plusSeconds(1))
        assertInstanceOf(Reservation.Granted::class.java, later)
    }

    @Test
    fun `the same request key is never granted twice`() {
        val ledger = ledger()
        val first = ledger.reserve("a1", phone, "same", t0) as Reservation.Granted
        val again = ledger.reserve("a1", phone, "same", t0.plusSeconds(1))
        assertEquals(Reservation.Duplicate(first.sendId, SendStatus.RESERVED), again)
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM phone_send").query(Int::class.java).single())
    }

    @Test
    fun `a repeat of a finished request reports its final status and sends nothing`() {
        val ledger = ledger()
        val first = ledger.reserve("a1", phone, "same", t0) as Reservation.Granted
        assertTrue(ledger.finish(first.sendId, SendStatus.SENT, "ref", t0))
        assertEquals(Reservation.Duplicate(first.sendId, SendStatus.SENT), ledger.reserve("a1", phone, "same", t0))
        assertTrue(!ledger.finish(first.sendId, SendStatus.UNKNOWN, null, t0), "a finished row is not finished twice")
    }

    @Test
    fun `one number waits for the cooldown and one account has its own cap`() {
        val ledger = ledger(SendLimits(daily = 100, perAccount = 2, cooldown = Duration.ofSeconds(60)))
        ledger.reserve("a1", phone, "k1", t0)
        val cool = ledger.reserve("a1", phone, "k2", t0.plusSeconds(30))
        assertEquals(Reservation.Denied(DenyReason.COOLDOWN, t0.plusSeconds(60)), cool)
        assertInstanceOf(Reservation.Granted::class.java, ledger.reserve("a1", phone, "k3", t0.plusSeconds(61)))
        assertEquals(
            Reservation.Denied(DenyReason.ACCOUNT_LIMIT, null),
            ledger.reserve("a1", number(2), "k4", t0.plusSeconds(200)),
        )
    }

    @Test
    fun `a refused send does not count and an unknown one does`() {
        val ledger = ledger(SendLimits(daily = 2, perAccount = 100, cooldown = Duration.ofSeconds(1)))
        val a = ledger.reserve("a1", number(1), "k1", t0) as Reservation.Granted
        val b = ledger.reserve("a2", number(2), "k2", t0) as Reservation.Granted
        ledger.finish(a.sendId, SendStatus.FAILED, null, t0)
        ledger.finish(b.sendId, SendStatus.UNKNOWN, null, t0)
        assertInstanceOf(Reservation.Granted::class.java, ledger.reserve("a3", number(3), "k3", t0.plusSeconds(5)))
        assertInstanceOf(Reservation.Denied::class.java, ledger.reserve("a4", number(4), "k4", t0.plusSeconds(5)))
    }

    @Test
    fun `the default cap is ten, and another instance on the same database sees the same count`() {
        assertEquals(10, SendLimits().daily, "the default daily cap must stay at the free ten")
        val first = ledger(SendLimits(perAccount = 100))
        val granted = (1..10).count { first.reserve("a$it", number(it), "k$it", t0) is Reservation.Granted }
        assertEquals(10, granted)
        // A restart, or the second slot of a deploy: a new object with no memory, the same database.
        val second = ledger(SendLimits(perAccount = 100))
        assertEquals(
            Reservation.Denied(DenyReason.DAILY_LIMIT, null),
            second.reserve("a11", number(11), "k11", t0.plusSeconds(3600)),
        )
        // A request key already used is still answered as a repeat, not as a new message.
        assertInstanceOf(Reservation.Duplicate::class.java, second.reserve("a1", number(1), "k1", t0.plusSeconds(3600)))
    }

    @Test
    fun `forty callers at once get exactly ten reservations`() {
        val ledger = ledger(SendLimits(daily = 10, perAccount = 100))
        val outcomes = runTogether(40) { i -> ledger.reserve("a$i", number(i), "k$i", t0) }
        assertEquals(10, outcomes.count { it is Reservation.Granted })
        assertEquals(10, jdbc.sql("SELECT COUNT(*) FROM phone_send").query(Int::class.java).single())
    }

    @Test
    fun `thirty identical requests at once get exactly one reservation`() {
        val ledger = ledger()
        val outcomes = runTogether(30) { ledger.reserve("a1", phone, "one-click", t0) }
        assertEquals(1, outcomes.count { it is Reservation.Granted })
        assertEquals(29, outcomes.count { it is Reservation.Duplicate })
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM phone_send").query(Int::class.java).single())
    }

    @Test
    fun `the sender is called once per granted reservation, whatever the contention`() {
        val calls = AtomicInteger()
        val sender = PhoneSender { _, _ -> calls.incrementAndGet(); "ref" }
        val service = PhoneSendService(
            ledger(SendLimits(daily = 10, perAccount = 100)),
            sender,
            Clock.fixed(t0, ZoneOffset.UTC),
        )
        // 60 callers, 30 distinct request keys: each key is asked twice at the same moment.
        val results = runTogether(60) { i -> service.send("a$i", number(i), "k${i % 30}") }
        assertEquals(10, calls.get(), "the provider was called more or less often than the cap")
        assertEquals(10, results.count { it is SendResult.Sent })
    }

    @Test
    fun `a timeout is counted and never retried, a refusal is released`() {
        val calls = AtomicInteger()
        var mode = "timeout"
        val service = PhoneSendService(
            ledger(SendLimits(daily = 10, perAccount = 100, cooldown = Duration.ofSeconds(1))),
            { _, _ ->
                calls.incrementAndGet()
                if (mode == "timeout") error("read timed out") else throw PhoneSendRejected("blocked")
            },
            Clock.fixed(t0, ZoneOffset.UTC),
        )
        assertInstanceOf(SendResult.Uncertain::class.java, service.send("a1", number(1), "k1"))
        assertEquals(
            SendStatus.UNKNOWN,
            (service.send("a1", number(1), "k1") as SendResult.AlreadyHandled).status,
        )
        assertEquals(1, calls.get(), "a repeat of a timed-out request must not call the provider again")
        mode = "refuse"
        assertInstanceOf(SendResult.Failed::class.java, service.send("a2", number(2), "k2"))
        assertEquals(2, calls.get())
        assertEquals(
            1,
            jdbc.sql("SELECT COUNT(*) FROM phone_send WHERE status <> 'FAILED'").query(Int::class.java).single(),
        )
    }

    private fun <T> runTogether(count: Int, task: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(count)
        val start = CountDownLatch(1)
        try {
            val futures = (1..count).map { i -> pool.submit<T> { start.await(); task(i) } }
            start.countDown()
            return futures.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val POOL = 64
    }
}
