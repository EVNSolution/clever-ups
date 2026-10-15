package com.evnsolution.ups.orders

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** The source is asked once per range while the answer is fresh, and never for the same range at once. */
class CachingOrderStoreTest {
    private class MovingClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val calls = AtomicInteger()
    private val clock = MovingClock(Instant.parse("2026-10-13T03:00:00Z"))
    private val day = LocalDate.parse("2026-10-05")

    private fun store(incomplete: Boolean = false, failing: Boolean = false) = CachingOrderStore(
        OrderStore {
            calls.incrementAndGet()
            if (failing) error("the source is down")
            OrderResult(listOf(Order("o", "T1")), incomplete)
        },
        clock,
        todayTtl = Duration.ofSeconds(60),
        pastTtl = Duration.ofHours(1),
    )

    @Test
    fun `a past day is asked once within its hour and again after it`() {
        val cached = store()
        cached.find(OrderQuery(day, day))
        cached.find(OrderQuery(day, day))
        assertEquals(1, calls.get())
        clock.now = clock.now.plus(Duration.ofMinutes(61))
        cached.find(OrderQuery(day, day))
        assertEquals(2, calls.get())
    }

    @Test
    fun `today is kept for a minute only`() {
        val cached = store()
        val today = LocalDate.parse("2026-10-13")
        cached.find(OrderQuery(today, today))
        clock.now = clock.now.plus(Duration.ofSeconds(30))
        cached.find(OrderQuery(today, today))
        assertEquals(1, calls.get())
        clock.now = clock.now.plus(Duration.ofSeconds(40))
        cached.find(OrderQuery(today, today))
        assertEquals(2, calls.get())
    }

    @Test
    fun `an incomplete answer and a failure are not kept`() {
        val partial = store(incomplete = true)
        partial.find(OrderQuery(day, day))
        partial.find(OrderQuery(day, day))
        assertEquals(2, calls.get())
        val broken = store(failing = true)
        repeat(2) { runCatching { broken.find(OrderQuery(day, day)) } }
        assertEquals(4, calls.get())
    }

    @Test
    fun `callers that arrive together share one read`() {
        val cached = CachingOrderStore(
            OrderStore {
                calls.incrementAndGet()
                Thread.sleep(150)
                OrderResult(listOf(Order("o", "T1")))
            },
            clock,
        )
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val futures = (1..8).map { pool.submit { start.await(); cached.find(OrderQuery(day, day)) } }
        start.countDown()
        futures.forEach { it.get() }
        pool.shutdown()
        assertEquals(1, calls.get())
    }

    @Test
    fun `the Job ids of a range are listed once while they are fresh`() {
        val lists = AtomicInteger()
        val source = object : OrderStore, JobLister {
            override fun find(query: OrderQuery) = OrderResult(emptyList())

            override fun jobIds(from: LocalDate, to: LocalDate): List<String> {
                lists.incrementAndGet()
                return listOf("j1")
            }
        }
        val cached = CachingOrderStore(source, clock, todayTtl = Duration.ofSeconds(60))
        assertEquals(listOf("j1"), cached.jobIds(day, day))
        assertEquals(listOf("j1"), cached.jobIds(day, day))
        assertEquals(1, lists.get())
    }
}

