package com.evnsolution.ups.accounts

import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

sealed interface LoginResult {
    data class Success(val session: IssuedSession, val info: SessionInfo) : LoginResult

    /** Wrong ID or wrong password. The two are never told apart. */
    data object Failure : LoginResult

    data class Locked(val retryAfter: Duration) : LoginResult
}

/**
 * Password sign-in with a lock against guessing: [MAX_FAILURES] failures of one login key inside [WINDOW] lock
 * that key, an unknown key too, so the lock does not reveal which IDs exist. While locked even the right
 * password is refused. A missing account still costs one password check, so timing does not reveal it either.
 *
 * NOTE: the lock is per login key, not per address. A trusted-proxy address needs the deployment first
 * (docs: deployment); add a per-address count then.
 */
class LoginService(
    private val jdbc: JdbcClient,
    private val hasher: PasswordHasher,
    private val sessions: SessionService,
    private val clock: Clock,
) {
    private val decoy = hasher.hash(UUID.randomUUID().toString())

    fun login(loginId: String, password: String): LoginResult {
        val key = loginId.trim().lowercase(Locale.ROOT)
        val now = clock.instant()
        if (recentFailures(key, now) >= MAX_FAILURES) return LoginResult.Locked(retryAfter(key, now))
        val accountId = authenticate(key, password)
        record(key, accountId != null, now)
        return accountId?.let(::start) ?: LoginResult.Failure
    }

    /** Returns the account ID when the password is right. A missing account still costs one password check. */
    private fun authenticate(key: String, password: String): String? {
        val record = jdbc.sql(
            "SELECT a.account_id, p.password_hash FROM account a " +
                "JOIN password_credential p ON p.account_id = a.account_id " +
                "WHERE a.login_key = :key AND a.status = 'ACTIVE'",
        ).param("key", key).query { rs, _ -> rs.getString("account_id").trim() to rs.getString("password_hash") }
            .optional().orElse(null)
        val matches = hasher.verify(password, record?.second ?: decoy)
        return if (matches) record?.first else null
    }

    private fun start(accountId: String): LoginResult {
        val issued = sessions.issue(accountId)
        val info = sessions.resolve(issued.accessToken)
        return if (info == null) LoginResult.Failure else LoginResult.Success(issued, info)
    }

    private fun recentFailures(key: String, now: Instant): Int =
        jdbc.sql(
            "SELECT COUNT(*) FROM login_attempt WHERE login_key = :key AND succeeded = FALSE AND attempted_at > :since",
        ).param("key", key).param("since", at(now.minus(WINDOW))).query(Int::class.java).single()

    private fun retryAfter(key: String, now: Instant): Duration {
        val oldest = jdbc.sql(
            "SELECT MIN(attempted_at) FROM login_attempt WHERE login_key = :key AND succeeded = FALSE " +
                "AND attempted_at > :since",
        ).param("key", key).param("since", at(now.minus(WINDOW)))
            .query(OffsetDateTime::class.java).optional().orElse(null)
        return oldest?.toInstant()?.plus(WINDOW)?.let { Duration.between(now, it) }?.coerceAtLeast(Duration.ZERO)
            ?: WINDOW
    }

    private fun record(key: String, succeeded: Boolean, now: Instant) {
        jdbc.sql(
            "INSERT INTO login_attempt (attempt_id, login_key, succeeded, attempted_at) " +
                "VALUES (:id, :key, :ok, :now)",
        )
            .param("id", UUID.randomUUID().toString()).param("key", key).param("ok", succeeded)
            .param("now", at(now)).update()
        jdbc.sql("DELETE FROM login_attempt WHERE attempted_at < :cutoff").param("cutoff", at(now.minus(KEEP))).update()
    }

    private fun at(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    companion object {
        const val MAX_FAILURES = 5
        val WINDOW: Duration = Duration.ofMinutes(15)
        private val KEEP: Duration = Duration.ofDays(1)
    }
}
