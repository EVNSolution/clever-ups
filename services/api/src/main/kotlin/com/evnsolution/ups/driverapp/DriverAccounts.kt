package com.evnsolution.ups.driverapp

import com.evnsolution.ups.accounts.Account
import com.evnsolution.ups.accounts.ApprovalPolicy
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Ties an active account to one driver of one center and makes its role `driver`. From then on the account reaches
 * only its own routes (the gate keeps it off the center's lists) and uses only that center. The owner decides, as
 * with approvals; the owner's own account and an account without an active membership cannot be tied.
 */
class DriverAccounts(private val jdbc: JdbcClient, private val transactions: TransactionTemplate) {
    fun link(actor: Account, accountId: String, centerCode: String, driverCode: String) {
        if (!ApprovalPolicy.canDecide(actor)) throw fail(HttpStatus.FORBIDDEN, "only the owner ties driver accounts")
        transactions.executeWithoutResult {
            val role = jdbc.sql("SELECT role FROM membership WHERE account_id = :id AND status = 'ACTIVE' FOR UPDATE")
                .param("id", accountId).query(String::class.java).optional().orElse(null)
            val driver = jdbc.sql("SELECT driver_id FROM driver WHERE center_code = :center AND driver_code = :code")
                .param("center", centerCode).param("code", driverCode)
                .query(String::class.java).optional().orElse(null)
            val problem = when {
                role == null -> HttpStatus.NOT_FOUND to "no active membership for this account"
                role == "owner" || accountId == actor.id -> HttpStatus.CONFLICT to "the owner cannot be a driver"
                driver == null -> HttpStatus.BAD_REQUEST to "not a driver of this center"
                else -> null
            }
            if (problem != null) throw fail(problem.first, problem.second)
            write(actor.id, accountId, centerCode, checkNotNull(driver).trim())
        }
    }

    /** The driver code tied to each account that has one. */
    fun codes(): Map<String, String> =
        jdbc.sql(
            "SELECT da.account_id, d.driver_code FROM driver_account da JOIN driver d ON d.driver_id = da.driver_id",
        ).query { rs, _ -> rs.getString(1).trim() to rs.getString(2) }.list().toMap()

    /** The driver tied to the account, with its center, or null. */
    fun driverOf(accountId: String): Pair<String, String>? =
        jdbc.sql(
            "SELECT d.driver_id, d.center_code FROM driver_account da JOIN driver d ON d.driver_id = da.driver_id " +
                "WHERE da.account_id = :id",
        ).param("id", accountId).query { rs, _ -> rs.getString(1).trim() to rs.getString(2) }
            .optional().orElse(null)

    private fun write(actorId: String, accountId: String, centerCode: String, driverId: String) {
        // The driver leaves any account it had; the account leaves any driver it had.
        jdbc.sql("DELETE FROM driver_account WHERE account_id = :account OR driver_id = :driver")
            .param("account", accountId).param("driver", driverId).update()
        jdbc.sql("INSERT INTO driver_account (account_id, driver_id, linked_by) VALUES (:account, :driver, :actor)")
            .param("account", accountId).param("driver", driverId).param("actor", actorId).update()
        jdbc.sql(
            "UPDATE membership SET role = 'driver', decided_by = :actor, updated_at = CURRENT_TIMESTAMP " +
                "WHERE account_id = :account",
        )
            .param("actor", actorId).param("account", accountId).update()
        jdbc.sql("DELETE FROM account_center WHERE account_id = :account").param("account", accountId).update()
        jdbc.sql(
            "INSERT INTO account_center (account_id, center_code, granted_by, granted_at) " +
                "VALUES (:account, :center, :actor, CURRENT_TIMESTAMP)",
        ).param("account", accountId).param("center", centerCode).param("actor", actorId).update()
        jdbc.sql(
            "INSERT INTO access_decision (decision_id, account_id, actor_id, action, role, center_codes, decided_at) " +
                "VALUES (:id, :account, :actor, 'LINK_DRIVER', 'driver', :center, CURRENT_TIMESTAMP)",
        ).param("id", UUID.randomUUID().toString()).param("account", accountId).param("actor", actorId)
            .param("center", centerCode).update()
    }

    private fun fail(status: HttpStatus, reason: String) = ResponseStatusException(status, reason)
}
