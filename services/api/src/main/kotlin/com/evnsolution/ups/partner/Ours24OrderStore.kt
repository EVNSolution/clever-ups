package com.evnsolution.ups.partner

import com.evnsolution.ups.orders.Order
import com.evnsolution.ups.orders.JobLister
import com.evnsolution.ups.orders.OrderQuery
import com.evnsolution.ups.orders.OrderResult
import com.evnsolution.ups.orders.OrderStore
import org.slf4j.LoggerFactory
import java.time.LocalDate
import tools.jackson.databind.JsonNode
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Orders from Ours24: the Jobs of the dates asked for, and the rows of each Job.
 * The dates go to Ours24 exactly as given; Ours24 decides what range it accepts.
 *
 * The rows endpoint was seen to ignore its page parameter and return the first 50 rows every time.
 * So a page that answers with another page number ends the reading of that list and marks the result
 * incomplete, instead of repeating rows. When Ours24 fixes paging, the same code reads every page.
 */
class Ours24OrderStore(private val client: Ours24Client) : OrderStore, JobLister {
    /** [complete] is false when pages were left unread. */
    private class Pages(val answers: List<JsonNode>, val complete: Boolean)

    private class Read<T>(val items: List<T>, val complete: Boolean)

    override fun jobIds(from: LocalDate, to: LocalDate): List<String> = readJobs(OrderQuery(from, to)).items

    override fun find(query: OrderQuery): OrderResult {
        val all = readJobs(query)
        val jobs = if (query.jobIds == null) all else Read(all.items.filter { it in query.jobIds }, all.complete)
        var complete = jobs.complete
        var skipped = 0
        val orders = ArrayList<Order>()
        for ((jobId, rows) in readAllRows(jobs.items)) {
            complete = complete && rows.complete
            for (row in rows.items) {
                val order = toOrder(jobId, row)
                if (order == null) skipped++ else orders.add(order)
            }
        }
        log.info(
            "Ours24 read from={} to={} jobs={} orders={} skippedRows={} incomplete={}",
            query.from, query.to, jobs.items.size, orders.size, skipped, !complete,
        )
        return OrderResult(orders, incomplete = !complete)
    }

    /**
     * Reads the rows of every Job, at most [MAX_PARALLEL] Jobs at once so Ours24 sees a small, fixed load.
     * The result keeps the order of [jobIds]. The first failure ends the read and is thrown as it is.
     */
    private fun readAllRows(jobIds: List<String>): List<Pair<String, Read<JsonNode>>> {
        val pool = Executors.newFixedThreadPool(MAX_PARALLEL)
        try {
            val futures = jobIds.map { id -> pool.submit<Pair<String, Read<JsonNode>>> { id to readRows(id) } }
            return futures.map {
                try {
                    it.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun readJobs(query: OrderQuery): Read<String> {
        val pages = readPages { page ->
            client.get(
                "/jobs",
                mapOf(
                    "start_date" to query.from.toString(),
                    "end_date" to query.to.toString(),
                    "size" to JOB_PAGE_SIZE.toString(),
                    "page" to page.toString(),
                ),
            )
        }
        val ids = pages.answers.flatMap { items(it, "jobs") }
            .mapNotNull { text(it, "id")?.takeIf(JOB_ID::matches) }
            .distinct()
        return Read(ids.take(MAX_JOBS), pages.complete && ids.size <= MAX_JOBS)
    }

    private fun readRows(jobId: String): Read<JsonNode> {
        val pages = readPages { page ->
            client.get("/jobs/$jobId/tasks", mapOf("page" to page.toString(), "limit" to ROW_PAGE_SIZE.toString()))
        }
        val rows = pages.answers.flatMap { items(it, "tasks") }
            .filter { it.path("rowNo").isIntegralNumber }
            .distinctBy { it.path("rowNo").intValue() }
        val expected = pages.answers.first().path("total").intValue(rows.size)
        return Read(rows, pages.complete && rows.size >= expected)
    }

    /** Reads page 1, then the others while Ours24 answers the page that was asked for. */
    private fun readPages(fetch: (Int) -> JsonNode): Pages {
        val answers = arrayListOf(fetch(1))
        val totalPages = answers.first().path("totalPages").intValue(1)
        for (page in 2..minOf(totalPages, MAX_PAGES)) {
            val next = fetch(page)
            if (next.path("page").intValue(page) != page) break
            answers.add(next)
        }
        return Pages(answers, answers.size >= totalPages)
    }

    private fun items(answer: JsonNode, field: String): JsonNode =
        answer.path(field).also { if (!it.isArray) fail("Ours24 answered without a $field list") }

    private fun toOrder(jobId: String, row: JsonNode): Order? {
        val trackingNumber = text(row, "trackingNumber") ?: return null
        return Order(
            id = "$jobId:${row.path("rowNo").intValue()}",
            trackingNumber = trackingNumber,
            customerName = text(row, "customerName"),
            companyName = text(row, "companyName"),
            address1 = text(row, "address1"),
            address2 = text(row, "address2"),
            postalCode = text(row, "postalCode"),
            status = text(row, "status"),
            jobId = jobId,
        )
    }

    private fun text(node: JsonNode, name: String): String? =
        node.path(name).takeIf { it.isString }?.stringValue()?.takeIf { it.isNotBlank() }

    private companion object {
        const val JOB_PAGE_SIZE = 100
        const val ROW_PAGE_SIZE = 200
        const val MAX_PAGES = 50
        const val MAX_JOBS = 200
        const val MAX_PARALLEL = 3
        val JOB_ID = Regex("[A-Za-z0-9_-]{1,128}")
        val log = LoggerFactory.getLogger(Ours24OrderStore::class.java)
    }
}
