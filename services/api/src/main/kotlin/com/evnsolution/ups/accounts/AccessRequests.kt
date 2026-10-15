package com.evnsolution.ups.accounts

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The caller may not do this. Maps to 403. */
class NotAllowed(message: String) : RuntimeException(message)

/** The thing asked for does not exist. Maps to 404. */
class NotFound(message: String) : RuntimeException(message)

/** The thing exists but is not in a state that allows this. Maps to 409. */
class StateConflict(message: String) : RuntimeException(message)

/** The input is not acceptable. Maps to 400. */
class BadDecision(message: String) : IllegalArgumentException(message)

/**
 * Who may decide a request, and which roles they may give. This is the one place that answers it.
 *
 * HANDOFF(승인자 이관): in the first phase only the owner (개발 관리자) decides. To move this to center admins:
 *  1. canDecide: also allow `actor.role == ADMIN && request.centerCode in the actor's account_center rows`;
 *  2. grantableRoles: return `setOf(MEMBER, VIEWER)` for ADMIN;
 *  3. list: filter by the same rule. The request already stores its center, so no schema change is needed.
 * Nothing else checks a role for approval, so nothing else changes.
 */
object ApprovalPolicy {
    fun canDecide(actor: Account): Boolean = actor.role == Role.OWNER && actor.membership == MembershipStatus.ACTIVE

    fun grantableRoles(actor: Account): Set<Role> =
        if (canDecide(actor)) setOf(Role.ADMIN, Role.MEMBER, Role.VIEWER) else emptySet()
}

data class MemberView(
    val accountId: String,
    val loginId: String,
    val role: String?,
    val status: String,
    val centerCodes: List<String>,
    val decidedAt: Instant?,
    /** The driver code, for a `driver` account. */
    val driverId: String? = null,
)

data class AccessRequestView(
    val requestId: String,
    val accountId: String,
    val loginId: String,
    val fullName: String?,
    val centerCode: String?,
    val driverId: String?,
    val phone: String?,
    val phoneVerified: Boolean,
    val status: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/**
 * Lists and decides access requests. Every decision is one transaction that changes the request, the membership,
 * the account's centers and appends one `access_decision` row. A request that is not PENDING is a conflict, so
 * a double click or two tabs decide once.
 */
class AccessRequestService(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
) {
    fun pending(actor: Account, limit: Int): List<AccessRequestView> {
        requireDecider(actor)
        housekeeping(clock.instant())
        return jdbc.sql(
            "SELECT r.request_id, r.account_id, a.login_id, r.full_name, r.center_code, r.driver_id, r.phone, " +
                "r.phone_verified, r.status, r.created_at, r.expires_at " +
                "FROM access_request r JOIN account a ON a.account_id = r.account_id " +
                "WHERE r.status = 'PENDING' ORDER BY r.created_at, r.request_id LIMIT :limit",
        )
            .param("limit", limit)
            .query { rs, _ ->
                AccessRequestView(
                    rs.getString("request_id").trim(), rs.getString("account_id").trim(), rs.getString("login_id"),
                    rs.getString("full_name"), rs.getString("center_code"), rs.getString("driver_id"),
                    rs.getString("phone"), rs.getBoolean("phone_verified"), rs.getString("status"),
                    rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    rs.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
                )
            }.list()
    }

    /** Accounts with an active membership, with the centers each may use. */
    fun members(actor: Account, limit: Int): List<MemberView> {
        requireDecider(actor)
        val rows = jdbc.sql(
            "SELECT a.account_id, a.login_id, m.role, m.status, m.decided_at, d.driver_code FROM membership m " +
                "JOIN account a ON a.account_id = m.account_id " +
                "LEFT JOIN driver_account da ON da.account_id = a.account_id " +
                "LEFT JOIN driver d ON d.driver_id = da.driver_id WHERE m.status = 'ACTIVE' " +
                "ORDER BY a.login_id LIMIT :limit",
        ).param("limit", limit).query { rs, _ ->
            MemberView(
                rs.getString("account_id").trim(), rs.getString("login_id"), rs.getString("role"),
                rs.getString("status"), emptyList(),
                rs.getObject("decided_at", OffsetDateTime::class.java)?.toInstant(),
                rs.getString("driver_code"),
            )
        }.list()
        if (rows.isEmpty()) return rows
        val centers = jdbc.sql("SELECT account_id, center_code FROM account_center ORDER BY center_code")
            .query { rs, _ -> rs.getString("account_id").trim() to rs.getString("center_code") }.list()
            .groupBy({ it.first }, { it.second })
        return rows.map { it.copy(centerCodes = centers[it.accountId].orEmpty()) }
    }

    /** [centerCodes] defaults to the center the person asked for. */
    fun approve(actor: Account, requestId: String, role: Role, centerCodes: List<String>?) {
        requireDecider(actor)
        if (role !in ApprovalPolicy.grantableRoles(actor)) throw NotAllowed("This role cannot be given by approval")
        val now = clock.instant()
        transactions.executeWithoutResult {
            val request = lockPending(requestId, now)
            val codes = (centerCodes?.takeIf { it.isNotEmpty() } ?: listOfNotNull(request.center))
                .map { it.trim().uppercase() }.distinct()
            if (codes.isEmpty() || codes.any { !CENTER.matches(it) }) throw BadDecision("A center code is needed")
            val at = at(now)
            jdbc.sql(
                "UPDATE access_request SET status = 'APPROVED', decided_by = :actor, decided_at = :now, " +
                    "decision_role = :role WHERE request_id = :id",
            ).param("actor", actor.id).param("now", at).param("role", role.value).param("id", requestId).update()
            jdbc.sql(
                "UPDATE membership SET role = :role, status = 'ACTIVE', decided_by = :actor, decided_at = :now, " +
                    "updated_at = :now WHERE account_id = :account",
            ).param("role", role.value).param("actor", actor.id).param("now", at).param("account", request.accountId)
                .update()
            // The approval defines the whole set of centers: nothing from an earlier membership stays.
            jdbc.sql("DELETE FROM account_center WHERE account_id = :a").param("a", request.accountId).update()
            codes.forEach { grantCenter(request.accountId, it, actor.id, at) }
            val evidence = "phone_verified=${request.phoneVerified}"
            audit(requestId, request.accountId, actor.id, "APPROVE", role.value, codes.joinToString(","), evidence, at)
        }
    }

    fun reject(actor: Account, requestId: String) {
        requireDecider(actor)
        val now = clock.instant()
        transactions.executeWithoutResult {
            val request = lockPending(requestId, now)
            val at = at(now)
            jdbc.sql(
                "UPDATE access_request SET status = 'REJECTED', decided_by = :actor, decided_at = :now " +
                    "WHERE request_id = :id",
            ).param("actor", actor.id).param("now", at).param("id", requestId).update()
            jdbc.sql(
                "UPDATE membership SET status = 'REJECTED', decided_by = :actor, decided_at = :now, " +
                    "updated_at = :now WHERE account_id = :account",
            ).param("actor", actor.id).param("now", at).param("account", request.accountId).update()
            audit(requestId, request.accountId, actor.id, "REJECT", null, null, null, at)
        }
    }

    /** Ends an active membership. The owner cannot be revoked here, and nobody revokes themselves. */
    fun revoke(actor: Account, accountId: String) {
        requireDecider(actor)
        val now = clock.instant()
        transactions.executeWithoutResult {
            val role = jdbc.sql("SELECT role FROM membership WHERE account_id = :id AND status = 'ACTIVE' FOR UPDATE")
                .param("id", accountId).query(String::class.java).optional().orElse(null)
            when {
                accountId == actor.id || role == Role.OWNER.value ->
                    throw NotAllowed("The owner cannot be revoked here, and nobody revokes themselves")
                role == null -> throw NotFound("No active membership for this account")
            }
            val at = at(now)
            jdbc.sql(
                "UPDATE membership SET status = 'REVOKED', decided_by = :actor, decided_at = :now, updated_at = :now " +
                    "WHERE account_id = :id",
            ).param("actor", actor.id).param("now", at).param("id", accountId).update()
            // A revoked account keeps no center, so a later approval cannot bring an old one back.
            jdbc.sql("DELETE FROM account_center WHERE account_id = :id").param("id", accountId).update()
            audit(null, accountId, actor.id, "REVOKE", checkNotNull(role), null, null, at)
        }
    }

    /**
     * Requests past their 14 days become EXPIRED and the waiting membership is removed, so the person can ask
     * again. The personal fields of an expired or rejected request are cleared after [KEEP].
     */
    fun housekeeping(now: Instant) {
        val at = at(now)
        transactions.executeWithoutResult {
            jdbc.sql(
                "DELETE FROM membership WHERE status = 'PENDING' AND account_id IN " +
                    "(SELECT account_id FROM access_request WHERE status = 'PENDING' AND expires_at <= :now)",
            ).param("now", at).update()
            jdbc.sql("UPDATE access_request SET status = 'EXPIRED' WHERE status = 'PENDING' AND expires_at <= :now")
                .param("now", at).update()
            jdbc.sql(
                "UPDATE access_request SET full_name = NULL, driver_id = NULL, phone = NULL " +
                    "WHERE status IN ('EXPIRED', 'REJECTED') AND full_name IS NOT NULL " +
                    "AND COALESCE(decided_at, expires_at) <= :cutoff",
            ).param("cutoff", at(now.minus(KEEP))).update()
        }
    }

    private class Locked(val accountId: String, val center: String?, val phoneVerified: Boolean)

    /** Locks the request row. Throws when it is gone, decided already, or past its 14 days. */
    private fun lockPending(requestId: String, now: Instant): Locked {
        val row = jdbc.sql(
            "SELECT account_id, center_code, phone_verified, status, expires_at FROM access_request " +
                "WHERE request_id = :id FOR UPDATE",
        ).param("id", requestId).query { rs, _ ->
            Triple(
                Locked(rs.getString("account_id").trim(), rs.getString("center_code"), rs.getBoolean("phone_verified")),
                rs.getString("status"),
                rs.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
            )
        }.optional().orElse(null) ?: throw NotFound("No such request")
        val problem = when {
            row.second != "PENDING" -> "This request was already decided"
            !row.third.isAfter(now) -> "This request has expired"
            else -> null
        }
        if (problem != null) throw StateConflict(problem)
        return row.first
    }

    private fun grantCenter(accountId: String, code: String, actorId: String, at: OffsetDateTime) {
        val present = jdbc.sql("SELECT COUNT(*) FROM account_center WHERE account_id = :a AND center_code = :c")
            .param("a", accountId).param("c", code).query(Int::class.java).single()
        if (present > 0) return
        jdbc.sql(
            "INSERT INTO account_center (account_id, center_code, granted_by, granted_at) " +
                "VALUES (:a, :c, :by, :at)",
        ).param("a", accountId).param("c", code).param("by", actorId).param("at", at).update()
    }

    private fun audit(
        requestId: String?,
        accountId: String,
        actorId: String,
        action: String,
        role: String?,
        centers: String?,
        evidence: String?,
        at: OffsetDateTime,
    ) {
        jdbc.sql(
            "INSERT INTO access_decision (decision_id, request_id, account_id, actor_id, action, role, " +
                "center_codes, evidence, decided_at) VALUES (:id, :req, :acc, :actor, :action, :role, :centers, " +
                ":evidence, :at)",
        )
            .param("id", UUID.randomUUID().toString()).param("req", requestId).param("acc", accountId)
            .param("actor", actorId).param("action", action).param("role", role).param("centers", centers)
            .param("evidence", evidence).param("at", at).update()
    }

    private fun requireDecider(actor: Account) {
        if (!ApprovalPolicy.canDecide(actor)) throw NotAllowed("This account cannot decide access requests")
    }

    private fun at(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private companion object {
        val CENTER = Regex("[A-Z0-9_-]{2,20}")
        val KEEP: Duration = Duration.ofDays(30)
    }
}
