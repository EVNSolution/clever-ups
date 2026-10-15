package com.evnsolution.ups.accounts

import org.springframework.jdbc.core.simple.JdbcClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/** The two secrets leave the server once, in cookies. The server keeps only their hashes. */
data class IssuedSession(
    val accessToken: String,
    val refreshToken: String,
    val csrfToken: String,
    val accessExpiresAt: Instant,
    val refreshExpiresAt: Instant,
)

sealed interface Refreshed {
    data class Ok(val session: IssuedSession) : Refreshed

    /** Unknown, expired or revoked refresh token, or the account is disabled. */
    data object Rejected : Refreshed

    /** An already replaced refresh token came back: the session was ended, both tokens are dead. */
    data object Reused : Refreshed
}

/** How long each token lives. The refresh token never outlives [absolute]. */
data class SessionLifetimes(
    val access: Duration = Duration.ofMinutes(DEFAULT_ACCESS_MINUTES),
    val refresh: Duration = Duration.ofDays(DEFAULT_REFRESH_DAYS),
    val absolute: Duration = Duration.ofDays(DEFAULT_ABSOLUTE_DAYS),
) {
    private companion object {
        const val DEFAULT_ACCESS_MINUTES = 15L
        const val DEFAULT_REFRESH_DAYS = 30L
        const val DEFAULT_ABSOLUTE_DAYS = 90L
    }
}

/**
 * Server-side sessions with an access token (AT) and a refresh token (RT).
 *
 *  - The AT is short. A stolen AT is useful for minutes. It is checked against the database on every request,
 *    together with the membership, so revoking is immediate and no signed token has to be tracked.
 *  - The RT is long and rolling: each use replaces it, and the new one lives [SessionLifetimes.refresh] again,
 *    so a person who comes back within that time stays signed in. The absolute limit still ends the session.
 *  - A replaced RT that comes back after [GRACE] is a stolen copy: the whole session is ended. Inside [GRACE]
 *    it is only two tabs asking at once; the second is refused without ending anything and retries.
 */
class SessionService(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    private val lifetimes: SessionLifetimes = SessionLifetimes(),
    private val random: SecureRandom = SecureRandom(),
) {
    fun issue(accountId: String): IssuedSession {
        val now = clock.instant()
        val session = newTokens(now, now.plus(lifetimes.absolute), csrf = secret())
        jdbc.sql(
            "INSERT INTO session (session_id, account_id, csrf_token, access_hash, access_expires_at, " +
                "refresh_hash, refresh_expires_at, absolute_expires_at, created_at, last_seen_at) " +
                "VALUES (:id, :account, :csrf, :ah, :ae, :rh, :re, :abs, :now, :now)",
        )
            .param("id", UUID.randomUUID().toString()).param("account", accountId).param("csrf", session.csrfToken)
            .param("ah", hash(session.accessToken)).param("ae", at(session.accessExpiresAt))
            .param("rh", hash(session.refreshToken)).param("re", at(session.refreshExpiresAt))
            .param("abs", at(now.plus(lifetimes.absolute))).param("now", at(now)).update()
        return session
    }

    /** The person behind this access token, or null when it is unknown, expired or revoked. */
    fun resolve(accessToken: String): SessionInfo? {
        val now = at(clock.instant())
        return jdbc.sql(
            "SELECT a.account_id, a.login_id, m.role, m.status AS membership_status, s.csrf_token " +
                "FROM session s JOIN account a ON a.account_id = s.account_id " +
                "LEFT JOIN membership m ON m.account_id = a.account_id " +
                "WHERE s.access_hash = :hash AND s.revoked_at IS NULL AND s.access_expires_at > :now " +
                "AND s.absolute_expires_at > :now AND a.status = 'ACTIVE'",
        )
            .param("hash", hash(accessToken)).param("now", now)
            .query { rs, _ ->
                val membership = rs.getString("membership_status")?.let { MembershipStatus.valueOf(it) }
                    ?: MembershipStatus.NONE
                // A role is only meaningful while the membership is active. A revoked member shows no role.
                val role = rs.getString("role")?.takeIf { membership == MembershipStatus.ACTIVE }
                    ?.let { value -> Role.entries.first { it.value == value } }
                SessionInfo(
                    Account(rs.getString("account_id").trim(), rs.getString("login_id"), role, membership),
                    rs.getString("csrf_token"),
                )
            }
            .optional().orElse(null)
    }

    /** Replaces the refresh token and the access token. The old refresh token stops working at once. */
    fun refresh(refreshToken: String): Refreshed {
        val now = clock.instant()
        val old = hash(refreshToken)
        val fresh = newTokens(now, absolute = null, csrf = "")
        val changed = jdbc.sql(
            "UPDATE session SET previous_refresh_hash = refresh_hash, previous_rotated_at = :now, " +
                "refresh_hash = :rh, refresh_expires_at = LEAST(:re, absolute_expires_at), " +
                "access_hash = :ah, access_expires_at = :ae, last_seen_at = :now " +
                "WHERE refresh_hash = :old AND revoked_at IS NULL AND refresh_expires_at > :now " +
                "AND absolute_expires_at > :now " +
                "AND EXISTS (SELECT 1 FROM account a WHERE a.account_id = session.account_id AND a.status = 'ACTIVE')",
        )
            .param("now", at(now)).param("old", old)
            .param("rh", hash(fresh.refreshToken)).param("re", at(fresh.refreshExpiresAt))
            .param("ah", hash(fresh.accessToken)).param("ae", at(fresh.accessExpiresAt)).update()
        if (changed == 0) return if (reuseDetected(old, now)) Refreshed.Reused else Refreshed.Rejected
        val csrf = jdbc.sql("SELECT csrf_token FROM session WHERE access_hash = :ah")
            .param("ah", hash(fresh.accessToken)).query(String::class.java).single()
        return Refreshed.Ok(fresh.copy(csrfToken = csrf))
    }

    /** Ends the session of this access token. A second call, or an unknown token, changes nothing. */
    fun revoke(accessToken: String) {
        jdbc.sql("UPDATE session SET revoked_at = :now WHERE access_hash = :hash AND revoked_at IS NULL")
            .param("now", at(clock.instant())).param("hash", hash(accessToken)).update()
    }

    private fun reuseDetected(old: String, now: Instant): Boolean {
        val rotatedAt = jdbc.sql(
            "SELECT previous_rotated_at FROM session WHERE previous_refresh_hash = :old AND revoked_at IS NULL",
        ).param("old", old).query(OffsetDateTime::class.java).optional().orElse(null)?.toInstant()
        val stolen = rotatedAt != null && Duration.between(rotatedAt, now) > GRACE
        if (stolen) {
            jdbc.sql("UPDATE session SET revoked_at = :now WHERE previous_refresh_hash = :old AND revoked_at IS NULL")
                .param("now", at(now)).param("old", old).update()
        }
        return stolen
    }

    private fun newTokens(now: Instant, absolute: Instant?, csrf: String): IssuedSession {
        val wanted = now.plus(lifetimes.refresh)
        val refreshEnds = if (absolute != null && wanted.isAfter(absolute)) absolute else wanted
        return IssuedSession(secret(), secret(), csrf, now.plus(lifetimes.access), refreshEnds)
    }

    private fun secret(): String {
        val bytes = ByteArray(SECRET_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun at(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private companion object {
        const val SECRET_BYTES = 32
        val GRACE: Duration = Duration.ofSeconds(10)
    }
}
