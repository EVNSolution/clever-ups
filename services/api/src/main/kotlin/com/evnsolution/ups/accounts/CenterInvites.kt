package com.evnsolution.ups.accounts

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/** Too many wrong codes in a short time. Maps to 429. */
class InviteLocked(val retryAfter: Duration) : RuntimeException("Too many wrong codes. Try again later.")

data class CreatedInvite(val code: String, val centerCodes: List<String>, val role: String, val expiresAt: Instant)

data class Redeemed(val centerCodes: List<String>, val role: String?)

/** What a code gave to a new account at sign-up: the role, the centers and who made the code. */
data class InviteGrant(val role: String, val centerCodes: List<String>, val createdBy: String)

/** Wrong-code counting for [CenterInviteService]: five misses in 15 minutes lock a key. */
class InviteAttempts(private val jdbc: JdbcClient) {
    /** How long [key] must wait, or null when it may try. */
    fun lockedFor(key: String, now: Instant): Duration? {
        val since = at(now.minus(WINDOW))
        val count = jdbc.sql(
            "SELECT COUNT(*) FROM login_attempt WHERE login_key = :k AND succeeded = FALSE AND attempted_at > :s",
        ).param("k", key).param("s", since).query(Int::class.java).single()
        if (count < MAX_FAILURES) return null
        val oldest = jdbc.sql(
            "SELECT MIN(attempted_at) FROM login_attempt WHERE login_key = :k AND succeeded = FALSE " +
                "AND attempted_at > :s",
        ).param("k", key).param("s", since).query(OffsetDateTime::class.java).optional().orElse(null)
        return oldest?.toInstant()?.plus(WINDOW)?.let { Duration.between(now, it) }?.coerceAtLeast(Duration.ZERO)
            ?: WINDOW
    }

    fun record(key: String, now: Instant) {
        jdbc.sql(
            "INSERT INTO login_attempt (attempt_id, login_key, succeeded, attempted_at) VALUES (:id, :k, FALSE, :now)",
        ).param("id", UUID.randomUUID().toString()).param("k", key).param("now", at(now)).update()
    }

    private companion object {
        const val MAX_FAILURES = 5
        val WINDOW: Duration = Duration.ofMinutes(15)
    }
}

private fun at(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

private fun hash(code: String): String =
    MessageDigest.getInstance("SHA-256").digest(code.toByteArray()).joinToString("") { "%02x".format(it) }

private fun normalize(input: String): String = input.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }

/**
 * A center entry code. The owner makes one for one or more centers and a role (member or viewer). A person who
 * types it gets those centers added; a person without access becomes active with the role of the code, which
 * is the same as an approval by the person who made the code. A person who already has access keeps the role.
 * The same code is the key to sign-up: it is checked before a message is sent and used when the account is made.
 * The owner role can never come from a code.
 *
 * HANDOFF(승인자 이관): when center admins approve, let them make codes for their own centers: in [create]
 * replace the owner check by `ApprovalPolicy.canDecide(actor)` for a center the actor holds in `account_center`.
 */
class CenterInviteService(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val attempts: InviteAttempts = InviteAttempts(jdbc),
    private val random: SecureRandom = SecureRandom(),
) {
    fun create(actor: Account, centerCodes: List<String>, role: String): CreatedInvite {
        if (!ApprovalPolicy.canDecide(actor)) throw NotAllowed("This account cannot make center codes")
        val centers = centerCodes.map { it.trim().uppercase(Locale.ROOT) }.distinct()
        requireGrantable(centers, role)
        val now = clock.instant()
        val code = (1..CODE_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        val expires = now.plus(LIFETIME)
        transactions.executeWithoutResult {
            jdbc.sql(
                "INSERT INTO center_invite (code_hash, role, created_by, created_at, expires_at) " +
                    "VALUES (:h, :r, :by, :now, :exp)",
            )
                .param("h", hash(code)).param("r", role).param("by", actor.id)
                .param("now", at(now)).param("exp", at(expires)).update()
            centers.forEach {
                jdbc.sql("INSERT INTO center_invite_center (code_hash, center_code) VALUES (:h, :c)")
                    .param("h", hash(code)).param("c", it).update()
            }
        }
        return CreatedInvite(code.chunked(GROUP).joinToString("-"), centers, role, expires)
    }

    private fun requireGrantable(centers: List<String>, role: String) {
        val problem = when {
            centers.isEmpty() || centers.size > MAX_CENTERS -> "A code carries 1 to $MAX_CENTERS centers"
            centers.any { !CENTER.matches(it) } ->
                "A center code is 2 to 20 letters, digits, dashes or underscores"
            role !in GRANTABLE -> "A code can give member or viewer"
            else -> null
        }
        if (problem != null) throw BadDecision(problem)
    }

    /** True when [input] is a code that has not been used and has not expired. It changes nothing. */
    fun isValid(input: String): Boolean {
        val rows = jdbc.sql("SELECT expires_at, used_by FROM center_invite WHERE code_hash = :h")
            .param("h", hash(normalize(input)))
            .query { rs, _ ->
                rs.getObject("expires_at", OffsetDateTime::class.java).toInstant() to rs.getString("used_by")
            }.list()
        val now = clock.instant()
        return rows.any { (expires, used) -> used == null && expires.isAfter(now) }
    }

    /**
     * Before a message is sent or an account is made: the code must be valid, and wrong codes count against
     * [key] (a phone number, which is the only thing a person without an account has).
     */
    fun requireValid(input: String, key: String) {
        val now = clock.instant()
        attempts.lockedFor(key, now)?.let { throw InviteLocked(it) }
        if (!isValid(input)) {
            attempts.record(key, now)
            throw BadDecision("The sign-up code is not valid")
        }
    }

    /**
     * Uses a code for a brand-new account, inside the caller's transaction: the membership becomes active with
     * the role of the code, the centers are added, the code is marked used, and one audit line is written.
     * Returns null when the code is not usable any more, so the caller stops and rolls back.
     */
    fun grantNewAccount(input: String, accountId: String, now: Instant): InviteGrant? {
        val code = normalize(input)
        val invite = lock(code)?.takeIf { it.usedBy == null && it.expiresAt.isAfter(now) } ?: return null
        val at = at(now)
        jdbc.sql(
            "INSERT INTO membership (account_id, role, status, decided_by, decided_at, created_at, updated_at) " +
                "VALUES (:a, :r, 'ACTIVE', :by, :at, :at, :at)",
        ).param("a", accountId).param("r", invite.role).param("by", invite.createdBy).param("at", at).update()
        finish(code, accountId, invite, at, role = invite.role)
        return InviteGrant(invite.role, invite.centerCodes, invite.createdBy)
    }

    /** The owner has every center, and a person who guessed too often waits. */
    private fun requireMayRedeem(actor: Account, key: String, now: Instant) {
        if (actor.role == Role.OWNER) throw StateConflict("The owner has every center already")
        attempts.lockedFor(key, now)?.let { throw InviteLocked(it) }
    }

    fun redeem(actor: Account, input: String): Redeemed {
        val now = clock.instant()
        val key = "invite:${actor.id}"
        requireMayRedeem(actor, key, now)
        val code = normalize(input)
        val result = transactions.execute { use(actor, code, now) }
        if (result == null) {
            attempts.record(key, now)
            throw BadDecision("The code is not valid")
        }
        return result
    }

    private fun lock(code: String): Invite? {
        val head = jdbc.sql(
            "SELECT role, created_by, expires_at, used_by FROM center_invite WHERE code_hash = :h FOR UPDATE",
        ).param("h", hash(code))
            .query { rs, _ ->
                Head(
                    rs.getString("role"), rs.getString("created_by").trim(),
                    rs.getObject("expires_at", OffsetDateTime::class.java).toInstant(), rs.getString("used_by"),
                )
            }.optional().orElse(null) ?: return null
        val centers = jdbc.sql("SELECT center_code FROM center_invite_center WHERE code_hash = :h ORDER BY center_code")
            .param("h", hash(code)).query(String::class.java).list().map { it.orEmpty() }
        return Invite(centers, head.role, head.createdBy, head.expiresAt, head.usedBy)
    }

    private fun use(actor: Account, code: String, now: Instant): Redeemed? {
        val invite = lock(code)
        if (invite == null || invite.usedBy != null || !invite.expiresAt.isAfter(now)) return null
        val at = at(now)
        jdbc.sql("SELECT account_id FROM account WHERE account_id = :a FOR UPDATE").param("a", actor.id)
            .query().listOfRows()
        val status = jdbc.sql("SELECT status FROM membership WHERE account_id = :a").param("a", actor.id)
            .query(String::class.java).optional().orElse(null)
        val active = status == "ACTIVE"
        when (status) {
            null -> jdbc.sql(
                "INSERT INTO membership (account_id, role, status, decided_by, decided_at, created_at, updated_at) " +
                    "VALUES (:a, :r, 'ACTIVE', :by, :at, :at, :at)",
            ).param("a", actor.id).param("r", invite.role).param("by", invite.createdBy).param("at", at).update()
            else -> if (!active) {
                jdbc.sql(
                    "UPDATE membership SET role = :r, status = 'ACTIVE', decided_by = :by, decided_at = :at, " +
                        "updated_at = :at WHERE account_id = :a",
                ).param("r", invite.role).param("by", invite.createdBy).param("at", at).param("a", actor.id).update()
            }
        }
        jdbc.sql(
            "UPDATE access_request SET status = 'APPROVED', decided_by = :by, decided_at = :at, decision_role = :r " +
                "WHERE account_id = :a AND status = 'PENDING'",
        ).param("by", invite.createdBy).param("at", at).param("r", invite.role).param("a", actor.id).update()
        finish(code, actor.id, invite, at, role = if (active) null else invite.role)
        return Redeemed(invite.centerCodes, if (active) null else invite.role)
    }

    /** The part shared by sign-up and entering a code: centers, used mark, audit line. */
    private fun finish(code: String, accountId: String, invite: Invite, at: OffsetDateTime, role: String?) {
        invite.centerCodes.forEach { center ->
            val present = jdbc.sql("SELECT COUNT(*) FROM account_center WHERE account_id = :a AND center_code = :c")
                .param("a", accountId).param("c", center).query(Int::class.java).single()
            if (present == 0) {
                jdbc.sql(
                    "INSERT INTO account_center (account_id, center_code, granted_by, granted_at) " +
                        "VALUES (:a, :c, :by, :at)",
                ).param("a", accountId).param("c", center).param("by", invite.createdBy).param("at", at).update()
            }
        }
        jdbc.sql("UPDATE center_invite SET used_by = :a, used_at = :at WHERE code_hash = :h")
            .param("a", accountId).param("at", at).param("h", hash(code)).update()
        jdbc.sql(
            "INSERT INTO access_decision (decision_id, request_id, account_id, actor_id, action, role, " +
                "center_codes, evidence, decided_at) " +
                "VALUES (:id, NULL, :a, :by, 'APPROVE', :r, :c, 'center-invite', :at)",
        )
            .param("id", UUID.randomUUID().toString()).param("a", accountId).param("by", invite.createdBy)
            .param("r", role).param("c", invite.centerCodes.joinToString(",")).param("at", at).update()
    }

    private data class Head(val role: String, val createdBy: String, val expiresAt: Instant, val usedBy: String?)

    private data class Invite(
        val centerCodes: List<String>,
        val role: String,
        val createdBy: String,
        val expiresAt: Instant,
        val usedBy: String?,
    )

    private companion object {
        /** No 0, 1, I, L or O, so a code read aloud or from a screenshot is not misread. */
        const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        const val CODE_LENGTH = 12
        const val MAX_CENTERS = 10
        const val GROUP = 4
        val GRANTABLE = setOf("member", "viewer")
        val LIFETIME: Duration = Duration.ofDays(7)
        val CENTER = Regex("[A-Z0-9_-]{2,20}")
    }
}

data class CreateInviteBody(val centerCodes: List<String>?, val role: String?)

data class RedeemBody(val code: String?)

@RestController
@RequestMapping("/api/center-invites")
@ConditionalOnProperty("ups.db.url")
class CenterInviteController(private val invites: CenterInviteService, private val accounts: AccountStore) {
    /** Owner only (the gate has checked the session and the CSRF token; the service checks the owner). */
    @PostMapping
    fun create(request: HttpServletRequest, @RequestBody body: CreateInviteBody): ResponseEntity<CreatedInvite> {
        val actor = accounts.current(request) ?: throw NotAllowed("Not signed in")
        val made = invites.create(actor, body.centerCodes.orEmpty(), body.role.orEmpty())
        return ResponseEntity.status(HttpStatus.CREATED).body(made)
    }

    /** Any signed-in account, with or without access. A wrong code counts toward a short lock. */
    @PostMapping("/redeem")
    fun redeem(request: HttpServletRequest, @RequestBody body: RedeemBody): Redeemed {
        val actor = accounts.current(request) ?: throw NotAllowed("Not signed in")
        return invites.redeem(actor, body.code.orEmpty())
    }
}
