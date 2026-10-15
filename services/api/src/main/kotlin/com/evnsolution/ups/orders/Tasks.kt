package com.evnsolution.ups.orders

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/** Keeps the Ours24 rows that were read, so a saved dispatch can point at them. Off without a database. */
fun interface TaskStore {
    fun record(orders: List<Order>)
}

@Component
class EmptyTaskStore : TaskStore {
    override fun record(orders: List<Order>) = Unit
}

/**
 * One `delivery_task` row per order read: the key is the order ID (Job and row number). A new row is added; a known
 * row gets its status and the time it was last seen. No customer name or phone number is stored.
 */
class JdbcTaskStore(private val jdbc: JdbcClient) : TaskStore {
    private data class Row(
        val key: String,
        val jobId: String,
        val rowNo: Int,
        val tracking: String,
        val status: String?,
        val address: String,
    )

    override fun record(orders: List<Order>) {
        val rows = orders.mapNotNull { order ->
            val jobId = order.jobId ?: return@mapNotNull null
            val address = order.addressKey ?: return@mapNotNull null
            val rowNo = order.id.substringAfterLast(':').toIntOrNull() ?: return@mapNotNull null
            Row(order.id, jobId, rowNo, order.trackingNumber, order.status, address)
        }.distinctBy { it.key }
        if (rows.isEmpty()) return
        val known = jdbc.sql("SELECT task_key, status FROM delivery_task WHERE task_key IN (:keys)")
            .param("keys", rows.map { it.key })
            .query { rs, _ -> rs.getString("task_key") to rs.getString("status") }
            .list().toMap()
        // ponytail: one statement per new or changed row; batch them if a day brings tens of thousands of rows.
        rows.filter { it.key !in known }.forEach(::insert)
        rows.filter { it.key in known && known[it.key] != it.status }.forEach { row ->
            jdbc.sql("UPDATE delivery_task SET status = :status WHERE task_key = :key")
                .param("status", row.status).param("key", row.key).update()
        }
        jdbc.sql("UPDATE delivery_task SET last_seen_at = CURRENT_TIMESTAMP WHERE task_key IN (:keys)")
            .param("keys", rows.map { it.key }).update()
    }

    private fun insert(row: Row) {
        try {
            jdbc.sql(
                "INSERT INTO delivery_task (task_key, job_id, row_no, tracking_number, status, address_key) " +
                    "VALUES (:key, :job, :row, :tracking, :status, :address)",
            )
                .param("key", row.key).param("job", row.jobId).param("row", row.rowNo)
                .param("tracking", row.tracking).param("status", row.status).param("address", row.address)
                .update()
        } catch (_: DuplicateKeyException) {
            // Another request recorded the same row between the check and the insert. That is fine.
        }
    }
}
