package com.evnsolution.ups.orders

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps what the source answered for a range of days, so a page that asks again does not wait for the source.
 * A range that ends before today is kept for [pastTtl], a range that includes today for [todayTtl].
 * Only a complete answer is kept (an incomplete one is asked again), and a failure is never kept.
 * One caller reads at a time per range; the others wait for that read and share its answer.
 */
class CachingOrderStore(
    private val source: OrderStore,
    private val clock: Clock,
    private val todayTtl: Duration = Duration.ofSeconds(DEFAULT_TODAY_SECONDS),
    private val pastTtl: Duration = Duration.ofMinutes(DEFAULT_PAST_MINUTES),
) : OrderStore, JobLister {
    private class Entry(val result: OrderResult, val expiresAt: Instant)

    private class JobEntry(val ids: List<String>, val expiresAt: Instant)

    private val jobLists = ConcurrentHashMap<OrderQuery, JobEntry>()

    private val entries = ConcurrentHashMap<OrderQuery, Entry>()
    private val locks = ConcurrentHashMap<OrderQuery, Any>()

    override fun find(query: OrderQuery): OrderResult =
        fresh(query) ?: synchronized(locks.computeIfAbsent(query) { Any() }) {
            fresh(query) ?: source.find(query).also { result ->
                if (!result.incomplete) entries[query] = Entry(result, clock.instant().plus(ttl(query)))
            }
        }

    /** The Job ids of a range, kept as long as a read of today. Empty when the source cannot list them. */
    override fun jobIds(from: LocalDate, to: LocalDate): List<String> {
        val lister = source as? JobLister
        val key = OrderQuery(from, to)
        val kept = jobLists[key]?.takeIf { it.expiresAt.isAfter(clock.instant()) }
        return when {
            lister == null -> emptyList()
            kept != null -> kept.ids
            else -> lister.jobIds(from, to).also { jobLists[key] = JobEntry(it, clock.instant().plus(todayTtl)) }
        }
    }

    private fun fresh(query: OrderQuery): OrderResult? =
        entries[query]?.takeIf { it.expiresAt.isAfter(clock.instant()) }?.result

    private fun ttl(query: OrderQuery): Duration {
        val today = LocalDate.now(clock)
        return if (query.to.isBefore(today)) pastTtl else todayTtl
    }

    private companion object {
        const val DEFAULT_TODAY_SECONDS = 60L
        const val DEFAULT_PAST_MINUTES = 60L
    }
}
