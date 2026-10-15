package com.evnsolution.ups.partner

import com.evnsolution.ups.orders.OrderQuery
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

class Ours24OrderStoreTest {
    @TempDir
    lateinit var dir: Path
    private val fake = FakeOurs24()
    private lateinit var store: Ours24OrderStore
    private val day = LocalDate.parse("2026-10-07")
    private val query = OrderQuery(day, day)

    @BeforeEach
    fun setUp() {
        store = Ours24OrderStore(Ours24Client(fake.url, writeKeyFile(dir)))
    }

    @AfterEach
    fun tearDown() = fake.close()

    private fun jobs(ids: List<String>, page: Int = 1, totalPages: Int = 1) =
        """{"jobs":[${ids.joinToString(",") { """{"id":"$it","status":"READY"}""" }}],""" +
            """"total":${ids.size},"page":$page,"limit":100,"totalPages":$totalPages}"""

    private fun row(no: Int, tracking: String? = "TRK$no", address2: String = "") =
        """{"rowNo":$no,"status":"PENDING",${tracking?.let { """"trackingNumber":"$it",""" } ?: ""}""" +
            """"customerName":"Kim $no","companyName":"","address1":"Street $no","address2":"$address2","postalCode":"0$no"}"""

    private fun tasks(rows: List<String>, page: Int = 1, total: Int = rows.size, totalPages: Int = 1) =
        """{"tasks":[${rows.joinToString(",")}],"total":$total,"page":$page,"limit":50,"totalPages":$totalPages}"""

    @Test
    fun `reads the rows of every Job as orders`() {
        fake.json("/jobs", jobs(listOf("J1", "J2")))
        fake.json("/jobs/J1/tasks", tasks(listOf(row(1), row(2, address2 = "Unit 2"))))
        fake.json("/jobs/J2/tasks", tasks(listOf(row(1))))
        val result = store.find(query)
        assertEquals(listOf("J1:1", "J1:2", "J2:1"), result.orders.map { it.id })
        assertFalse(result.incomplete)
        val first = result.orders.first()
        assertEquals("TRK1", first.trackingNumber)
        assertEquals("Kim 1", first.customerName)
        assertNull(first.companyName)
        assertNull(first.address2)
        assertEquals("01", first.postalCode)
        assertEquals("PENDING", first.status)
        assertEquals("J1", first.jobId)
        assertEquals("Unit 2", result.orders[1].address2)
    }

    @Test
    fun `reads Jobs in parallel, never more than three at once, and keeps the Job order`() {
        val ids = (1..8).map { "J$it" }
        fake.json("/jobs", jobs(ids))
        val running = AtomicInteger()
        val peak = AtomicInteger()
        ids.forEach { id ->
            fake.on("/jobs/$id/tasks") {
                peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                Thread.sleep(100)
                running.decrementAndGet()
                FakeOurs24.Reply(200, tasks(listOf(row(1))))
            }
        }
        val result = store.find(query)
        assertEquals(ids.map { "$it:1" }, result.orders.map { it.id })
        assertTrue(peak.get() in 2..3, "peak was ${peak.get()}")
    }

    @Test
    fun `passes the dates to Ours24 as given, however long the range`() {
        fake.json("/jobs", jobs(emptyList()))
        store.find(OrderQuery(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-10-08")))
        val asked = fake.seen.single().query
        assertEquals("2026-09-01", asked["start_date"])
        assertEquals("2026-10-08", asked["end_date"])
    }

    @Test
    fun `skips a row without a tracking number`() {
        fake.json("/jobs", jobs(listOf("J1")))
        fake.json("/jobs/J1/tasks", tasks(listOf(row(1), row(2, tracking = null))))
        val result = store.find(query)
        assertEquals(listOf("J1:1"), result.orders.map { it.id })
        assertFalse(result.incomplete)
    }

    @Test
    fun `reads every page when Ours24 honors paging`() {
        fake.on("/jobs") {
            val page = it.getValue("page").toInt()
            FakeOurs24.Reply(200, jobs(listOf("J$page"), page, totalPages = 2))
        }
        fake.on("/jobs/J1/tasks") {
            val page = it.getValue("page").toInt()
            val rows = if (page == 1) listOf(row(1), row(2)) else listOf(row(3))
            FakeOurs24.Reply(200, tasks(rows, page, total = 3, totalPages = 2))
        }
        fake.json("/jobs/J2/tasks", tasks(listOf(row(1))))
        val result = store.find(query)
        assertEquals(listOf("J1:1", "J1:2", "J1:3", "J2:1"), result.orders.map { it.id })
        assertFalse(result.incomplete)
    }

    @Test
    fun `flags the result when Ours24 ignores the page, and does not repeat rows`() {
        fake.json("/jobs", jobs(listOf("J1")))
        fake.json("/jobs/J1/tasks", tasks(listOf(row(1), row(2)), page = 1, total = 120, totalPages = 3))
        val result = store.find(query)
        assertEquals(listOf("J1:1", "J1:2"), result.orders.map { it.id })
        assertTrue(result.incomplete)
        assertEquals(2, fake.seen.count { it.path == "/jobs/J1/tasks" })
    }

    @Test
    fun `flags the result when the Job holds more rows than were read`() {
        fake.json("/jobs", jobs(listOf("J1")))
        fake.json("/jobs/J1/tasks", tasks(listOf(row(1)), total = 5))
        assertTrue(store.find(query).incomplete)
    }

    @Test
    fun `does not request a Job whose id could change the path`() {
        fake.json("/jobs", jobs(listOf("../admin", "a/b", "J1")))
        fake.json("/jobs/J1/tasks", tasks(listOf(row(1))))
        val result = store.find(query)
        assertEquals(listOf("J1:1"), result.orders.map { it.id })
        assertEquals(listOf("/jobs", "/jobs/J1/tasks"), fake.seen.map { it.path })
    }

    @Test
    fun `reads at most 200 Jobs and flags the rest`() {
        val ids = (1..201).map { "J$it" }
        fake.json("/jobs", jobs(ids))
        ids.forEach { fake.json("/jobs/$it/tasks", tasks(listOf(row(1)))) }
        val result = store.find(query)
        assertEquals(200, result.orders.size)
        assertTrue(result.incomplete)
    }

    @Test
    fun `an answer without a Job list fails`() {
        fake.json("/jobs", """{"data":[]}""")
        assertThrows<Ours24Exception> { store.find(query) }
    }
}
