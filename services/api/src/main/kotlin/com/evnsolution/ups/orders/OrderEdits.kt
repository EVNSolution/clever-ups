package com.evnsolution.ups.orders

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

/** One cell a person changed. A null or blank [value] gives the cell back to Ours24. */
data class OrderEditIn(val orderId: String = "", val field: String = "", val value: String? = null)

/** The cells changed in the order table of one operating day. */
data class OrderEditsIn(val day: LocalDate? = null, val edits: List<OrderEditIn> = emptyList())

/** The order columns a person may correct. Tracking number and status are keys and state, so they stay. */
val EDITABLE_FIELDS = setOf("customerName", "companyName", "address1", "address2", "postalCode")

/** Edited names are personal data: kept this many days after the operating day, then deleted. */
const val NAME_KEEP_DAYS = 90L

/** Corrections people make to orders, shown over what Ours24 gives. Off without a database. */
interface OrderEditStore {
    /** The orders with their corrected cells, and which fields were corrected. */
    fun apply(orders: List<Order>): List<Order>

    /** The Job of each known order. An order never read has no task row and cannot be edited. */
    fun jobsOf(orderIds: Collection<String>): Map<String, String>

    fun save(accountId: String, day: LocalDate, edits: List<OrderEditIn>)
}

@Component
class EmptyOrderEditStore : OrderEditStore {
    override fun apply(orders: List<Order>) = orders

    override fun jobsOf(orderIds: Collection<String>): Map<String, String> = emptyMap()

    override fun save(accountId: String, day: LocalDate, edits: List<OrderEditIn>) =
        throw UnsupportedOperationException("order edits need the database")
}

class JdbcOrderEdits(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
) : OrderEditStore {
    override fun apply(orders: List<Order>): List<Order> {
        if (orders.isEmpty()) return orders
        val edits = jdbc.sql("SELECT task_key, field_name, cell_value FROM order_edit WHERE task_key IN (:keys)")
            .param("keys", orders.map { it.id }.distinct())
            .query { rs, _ -> Triple(rs.getString("task_key"), rs.getString("field_name"), rs.getString("cell_value")) }
            .list().groupBy({ it.first }, { it.second to it.third })
        return orders.map { order -> edits[order.id]?.toMap()?.let { applied(order, it) } ?: order }
    }

    private fun applied(order: Order, cells: Map<String, String>): Order =
        order.copy(
            customerName = cells["customerName"] ?: order.customerName,
            companyName = cells["companyName"] ?: order.companyName,
            address1 = cells["address1"] ?: order.address1,
            address2 = cells["address2"] ?: order.address2,
            postalCode = cells["postalCode"] ?: order.postalCode,
            edited = cells.keys.sorted(),
        )

    override fun jobsOf(orderIds: Collection<String>): Map<String, String> =
        if (orderIds.isEmpty()) {
            emptyMap()
        } else {
            jdbc.sql("SELECT task_key, job_id FROM delivery_task WHERE task_key IN (:keys)")
                .param("keys", orderIds.distinct())
                .query { rs, _ -> rs.getString("task_key") to rs.getString("job_id") }
                .list().toMap()
        }

    override fun save(accountId: String, day: LocalDate, edits: List<OrderEditIn>) {
        val now = OffsetDateTime.now(clock)
        transactions.executeWithoutResult {
            edits.forEach { edit ->
                jdbc.sql("DELETE FROM order_edit WHERE task_key = :key AND field_name = :field")
                    .param("key", edit.orderId).param("field", edit.field).update()
                val value = edit.value?.trim().orEmpty()
                if (value.isEmpty()) return@forEach
                jdbc.sql(
                    "INSERT INTO order_edit (task_key, field_name, cell_value, work_day, edited_by, edited_at) " +
                        "VALUES (:key, :field, :value, :day, :by, :at)",
                )
                    .param("key", edit.orderId).param("field", edit.field).param("value", value)
                    .param("day", day).param("by", accountId).param("at", now)
                    .update()
            }
        }
    }

    /** Every night: edited names whose operating day is [NAME_KEEP_DAYS] days past are deleted. */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    fun purge() {
        jdbc.sql("DELETE FROM order_edit WHERE field_name IN ('customerName', 'companyName') AND work_day <= :cutoff")
            .param("cutoff", LocalDate.now(clock).minusDays(NAME_KEEP_DAYS))
            .update()
    }
}
