package com.evnsolution.ups.addresses

import com.evnsolution.ups.orders.Order
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/**
 * Runs the real migration on an in-memory H2 in PostgreSQL mode. Set UPS_TEST_DB_URL (and UPS_TEST_DB_USER)
 * to run the same tests on a real PostgreSQL; the table is emptied before each test.
 */
class JdbcAddressStoreTest {
    private lateinit var source: HikariDataSource
    private lateinit var jdbc: JdbcClient
    private lateinit var store: JdbcAddressStore

    @BeforeEach
    fun setUp() {
        source = HikariDataSource().apply {
            jdbcUrl = System.getenv("UPS_TEST_DB_URL")
                ?: "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            System.getenv("UPS_TEST_DB_USER")?.let { username = it }
        }
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
        jdbc = JdbcClient.create(source)
        jdbc.sql("DELETE FROM address_location").update()
        store = JdbcAddressStore(jdbc)
    }

    @AfterEach
    fun tearDown() = source.close()

    private fun order(id: String, address1: String?, address2: String? = null) =
        Order(id, "TRK-$id", address1 = address1, address2 = address2, postalCode = "06134")

    @Test
    fun `new addresses are queued once, however many orders share them`() {
        store.locate(listOf(order("1", "12 Teheran-ro"), order("2", "12  TEHERAN-RO"), order("3", "7 Sejong-daero")))
        store.locate(listOf(order("4", "12 Teheran-ro")))
        val pending = store.withStatus(LocationStatus.PENDING, 10)
        assertEquals(setOf("12 Teheran-ro", "7 Sejong-daero"), pending.map { it.address1 }.toSet())
        assertEquals(2, pending.size)
    }

    @Test
    fun `an order without an address line is passed through and queues nothing`() {
        val orders = listOf(order("1", null), order("2", "  "))
        assertEquals(orders, store.locate(orders))
        assertTrue(store.withStatus(LocationStatus.PENDING, 10).isEmpty())
    }

    @Test
    fun `a DONE address fills the place of every order that shares it`() {
        store.locate(listOf(order("1", "12 Teheran-ro")))
        val key = store.withStatus(LocationStatus.PENDING, 1).single().key
        assertTrue(store.update(key, LocationUpdate(LocationStatus.DONE, "서울 강남구 테헤란로 12", 37.5, 127.0)))
        val located = store.locate(listOf(order("1", "12 Teheran-ro"), order("2", "12 teheran-ro "), order("3", "x")))
        assertEquals(37.5, located[0].lat)
        assertEquals(127.0, located[1].lng)
        assertEquals("서울 강남구 테헤란로 12", located[1].addressKo)
        assertNull(located[2].lat)
        assertTrue(store.withStatus(LocationStatus.PENDING, 10).none { it.key == key })
    }

    @Test
    fun `FAILED leaves the queue and carries no place`() {
        store.locate(listOf(order("1", "Nowhere 1")))
        val key = store.withStatus(LocationStatus.PENDING, 1).single().key
        assertTrue(store.update(key, LocationUpdate(LocationStatus.FAILED)))
        assertEquals(key, store.withStatus(LocationStatus.FAILED, 10).single().key)
        assertNull(store.locate(listOf(order("1", "Nowhere 1"))).single().lat)
    }

    @Test
    fun `an unknown key is not found`() {
        assertFalse(store.update("0".repeat(64), LocationUpdate(LocationStatus.FAILED)))
    }

    @Test
    fun `the database itself refuses DONE without a place`() {
        store.locate(listOf(order("1", "12 Teheran-ro")))
        val key = store.withStatus(LocationStatus.PENDING, 1).single().key
        assertThrows<DataIntegrityViolationException> { store.update(key, LocationUpdate(LocationStatus.DONE)) }
    }

    @Test
    fun `detail, source and verification are stored, and an omitted verification is left as it was`() {
        store.locate(listOf(order("t1", "12 Teheran-ro")))
        val key = store.withStatus(LocationStatus.PENDING, 10).single().key
        assertTrue(
            store.update(
                key,
                LocationUpdate(
                    LocationStatus.FAILED, "서울", detailKo = "3층", koSource = KoSource.TRANSLATOR,
                    verifyStatus = VerifyStatus.MATCH,
                ),
            ),
        )
        assertTrue(store.update(key, LocationUpdate(LocationStatus.FAILED, "서울", koSource = KoSource.MANUAL)))
        val row = store.withStatus(LocationStatus.FAILED, 10).single()
        assertEquals(VerifyStatus.MATCH, row.verifyStatus)
        assertEquals(KoSource.MANUAL, row.koSource)
        assertEquals(null, row.detailKo)
    }

    @Test
    fun `an order carries the key of its address, and a typed Korean address takes the place away`() {
        val placed = order("m1", "12 Teheran-ro")
        store.locate(listOf(placed))
        val key = store.withStatus(LocationStatus.PENDING, 10).single().key
        assertEquals(key, store.locate(listOf(placed)).single().addressKey)
        assertNull(store.locate(listOf(order("none", null))).single().addressKey)
        store.update(key, LocationUpdate(LocationStatus.DONE, "서울 옛 주소", 37.5, 127.0, koSource = KoSource.TRANSLATOR))
        assertTrue(store.update(key, LocationUpdate(LocationStatus.PENDING, "서울 새 주소", koSource = KoSource.MANUAL)))
        val shown = store.locate(listOf(placed)).single()
        assertEquals("서울 새 주소", shown.addressKo)
        assertNull(shown.lat)
        assertEquals(1, store.withStatus(LocationStatus.PENDING, 10).size)
    }

    @Test
    fun `the Korean address is shown on an order before it has a position`() {
        store.locate(listOf(order("k1", "12 Teheran-ro")))
        val key = store.withStatus(LocationStatus.PENDING, 10).single().key
        val update = LocationUpdate(LocationStatus.PENDING, "서울 강남구 테헤란로 12", koSource = KoSource.REFERENCE)
        assertTrue(store.update(key, update))
        val shown = store.locate(listOf(order("k1", "12 Teheran-ro"))).single()
        assertEquals("서울 강남구 테헤란로 12", shown.addressKo)
        assertEquals(null, shown.lat)
        assertEquals(null, shown.detailKo)
    }
}

