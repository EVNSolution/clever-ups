package com.evnsolution.ups.accounts

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/** The input is not acceptable. The message says what to change and is safe to show. */
class InvalidCredentials(message: String) : IllegalArgumentException(message)

/** Another account already has this login ID (in any letter case), or an owner already exists. */
class AccountConflict(message: String) : IllegalStateException(message)

/**
 * Makes accounts. Both entry points write the account, its password and, for an owner, its membership in one
 * transaction, so a half-made account never exists.
 */
class AccountService(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val hasher: PasswordHasher,
    private val clock: Clock,
) {
    /** A person with a password and no membership: signs in, sees no data. Sign-up and approval build on this. */
    fun createAccount(loginId: String, password: String): Account =
        create(loginId, password, ownerMembership = false)

    /**
     * The first 개발 관리자. There is exactly one owner for now (a decision of 2026-10-12); a second is refused.
     * Run from the terminal with the password typed without echo. Never reachable from the web.
     */
    fun createOwner(loginId: String, password: String): Account =
        create(loginId, password, ownerMembership = true)

    /** Hashes a password. Slow on purpose, so callers do it outside any transaction. */
    fun hash(password: String): String = hasher.hash(password)

    /**
     * Inserts an account and its password hash, without a membership. It joins the caller's transaction when
     * there is one (sign-up), so the account never exists without its request.
     */
    fun insertAccount(loginId: String, passwordHash: String): Account =
        transactions.execute { insert(loginId, passwordHash, ownerMembership = false) }
            ?: error("The account transaction returned nothing")

    private fun create(loginId: String, password: String, ownerMembership: Boolean): Account {
        validate(loginId, password)
        val hash = hasher.hash(password) // outside the transaction: it is slow and needs no connection
        return transactions.execute { insert(loginId, hash, ownerMembership) }
            ?: error("The account transaction returned nothing")
    }

    private fun insert(loginId: String, hash: String, ownerMembership: Boolean): Account {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val key = loginId.lowercase(Locale.ROOT)
        if (ownerMembership && owners() > 0) throw AccountConflict("An owner already exists")
        if (jdbc.sql("SELECT COUNT(*) FROM account WHERE login_key = :key").param("key", key)
                .query(Int::class.java).single() > 0
        ) {
            throw AccountConflict("This login ID is taken")
        }
        val id = UUID.randomUUID().toString()
        jdbc.sql(
            "INSERT INTO account (account_id, login_id, login_key, created_at, updated_at) " +
                "VALUES (:id, :loginId, :key, :now, :now)",
        ).param("id", id).param("loginId", loginId).param("key", key).param("now", now).update()
        jdbc.sql("INSERT INTO password_credential (account_id, password_hash, changed_at) VALUES (:id, :hash, :now)")
            .param("id", id).param("hash", hash).param("now", now).update()
        if (!ownerMembership) return Account(id, loginId, null, MembershipStatus.NONE)
        jdbc.sql(
            "INSERT INTO membership (account_id, role, status, decided_at, created_at, updated_at) " +
                "VALUES (:id, 'owner', 'ACTIVE', :now, :now, :now)",
        ).param("id", id).param("now", now).update()
        return Account(id, loginId, Role.OWNER, MembershipStatus.ACTIVE)
    }

    private fun owners(): Int =
        jdbc.sql("SELECT COUNT(*) FROM membership WHERE role = 'owner'").query(Int::class.java).single()

    companion object {
        private val LOGIN_ID = Regex("[A-Za-z0-9._-]{4,32}")
        const val MIN_PASSWORD = 8
        const val MAX_PASSWORD = 128

        /**
         * The owner's rule (2026-10-12): 8 or more characters with an upper-case English letter, a digit and a
         * special character. The sign-in lock and the slow hash carry the rest. A list of known-bad passwords
         * is a later addition.
         */
        fun validate(loginId: String, password: String) {
            validateLoginId(loginId)
            validatePassword(loginId, password)
        }

        /** Checked alone first, so the terminal command can refuse a bad ID before it asks for a password. */
        fun validateLoginId(loginId: String) {
            if (!LOGIN_ID.matches(loginId)) {
                throw InvalidCredentials(
                    "The login ID is 4 to 32 letters, digits, dots, dashes or underscores (no @ or spaces)",
                )
            }
        }

        fun validatePassword(loginId: String, password: String) {
            val problem = when {
                password.length !in MIN_PASSWORD..MAX_PASSWORD ->
                    "The password is $MIN_PASSWORD to $MAX_PASSWORD characters"
                !password.any { it in 'A'..'Z' } -> "The password needs an upper-case English letter"
                !password.any { it in '0'..'9' } -> "The password needs a digit"
                password.all { it.isLetterOrDigit() } -> "The password needs a special character"
                password.contains(loginId, ignoreCase = true) -> "The password must not contain the login ID"
                else -> null
            }
            if (problem != null) throw InvalidCredentials(problem)
        }
    }
}
