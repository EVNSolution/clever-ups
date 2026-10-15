package com.evnsolution.ups.dispatch

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate

/**
 * Moves a saved dispatch on. Sending makes its routes the ones drivers get: only one run per center and day is
 * SENT, so a run sent before it becomes REPLACED. Cancelling withdraws a draft or a sent run. Each change runs
 * with the center row locked, so two people sending at once still leave one SENT run.
 */
class DispatchStates(private val jdbc: JdbcClient, private val transactions: TransactionTemplate) {
    fun send(planId: String) = change(planId, from = setOf("DRAFT")) { center, day ->
        jdbc.sql(
            "UPDATE dispatch_plan SET status = 'REPLACED', updated_at = CURRENT_TIMESTAMP " +
                "WHERE center_code = :center AND service_day = :day AND status = 'SENT'",
        ).param("center", center).param("day", day).update()
        setStatus(planId, "SENT")
        jdbc.sql("UPDATE dispatch_route SET status = 'SENT' WHERE plan_id = :id").param("id", planId).update()
    }

    fun cancel(planId: String) =
        change(planId, from = setOf("DRAFT", "SENT")) { _, _ -> setStatus(planId, "CANCELLED") }

    private fun setStatus(planId: String, status: String) {
        jdbc.sql("UPDATE dispatch_plan SET status = :status, updated_at = CURRENT_TIMESTAMP WHERE plan_id = :id")
            .param("status", status).param("id", planId).update()
    }

    private fun change(planId: String, from: Set<String>, apply: (String, LocalDate) -> Unit) {
        val refused = transactions.execute {
            val plan = jdbc.sql("SELECT center_code, service_day FROM dispatch_plan WHERE plan_id = :id")
                .param("id", planId)
                .query { rs, _ -> rs.getString(1) to rs.getObject(2, LocalDate::class.java) }
                .optional().orElse(null)
            if (plan != null) {
                jdbc.sql("SELECT center_code FROM center WHERE center_code = :c FOR UPDATE").param("c", plan.first)
                    .query(String::class.java).list()
            }
            // Read the status again under the lock: another change may have come first.
            val status = jdbc.sql("SELECT status FROM dispatch_plan WHERE plan_id = :id").param("id", planId)
                .query(String::class.java).optional().orElse(null)
            when {
                plan == null || status == null -> HttpStatus.NOT_FOUND
                status !in from -> HttpStatus.CONFLICT
                else -> null.also { apply(plan.first, plan.second) }
            }
        }
        if (refused != null) throw ResponseStatusException(refused, "dispatch cannot change from its state")
    }
}
