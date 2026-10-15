package com.evnsolution.ups.accounts

import com.evnsolution.ups.phone.PhoneProof
import com.evnsolution.ups.phone.KoreanMobile
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/** What a person types to sign up. Nothing here picks a role, an organization or a center's access. */
data class SignUp(
    val loginId: String,
    val password: String,
    val fullName: String,
    /** The center the person says they work at. A request, not a grant: the owner decides. */
    val centerCode: String,
    val driverId: String,
    val phone: String,
    /** No terms text exists yet. The field is accepted and ignored until the privacy and location terms do. */
    val termsAccepted: Boolean = true,
    /**
     * The Firebase ID token the browser got after the code was typed. Required when phone verification is
     * configured on this server; ignored otherwise. It is checked and discarded, never stored.
     */
    val phoneProof: String = "",
    /** The sign-up code given by the owner. Required while the server asks for one. */
    val inviteCode: String = "",
)

/** A field is not acceptable. The message names the field and is safe to show. */
class InvalidSignUp(message: String) : IllegalArgumentException(message)

/** New sign-ups are paused for today. */
class SignUpClosed : IllegalStateException("Sign-up is closed for now. Try again later.")

/** What a signed-in account types to ask for access again. Nothing here picks a role or a center's access. */
data class AccessProfile(
    val fullName: String,
    val centerCode: String,
    val driverId: String,
    val phone: String,
    val termsAccepted: Boolean,
)

/**
 * The values of an access request in the form they are stored in. The center and the driver ID are not asked
 * for any more: a blank is stored as null, and the owner names the centers when approving.
 */
data class CleanProfile(val fullName: String, val centerCode: String?, val driverId: String?, val phone: String)

/** The values of a sign-up in the form they are stored in. */
data class CleanSignUp(
    val loginId: String,
    val password: String,
    val fullName: String,
    val centerCode: String?,
    val driverId: String?,
    val phone: String,
)

object SignUpRules {
    private val CENTER = Regex("[A-Z0-9_-]{2,20}")
    private val DRIVER = Regex("[A-Za-z0-9_-]{1,32}")
    private const val MAX_NAME = 50

    /** The person's exact words, stripped of surrounding space and of runs of space inside. */
    private fun squash(text: String) = text.trim().replace(Regex("\\s+"), " ")

    fun clean(input: SignUp): CleanSignUp {
        val profile = cleanProfile(
            AccessProfile(input.fullName, input.centerCode, input.driverId, input.phone, input.termsAccepted),
        )
        AccountService.validate(input.loginId, input.password)
        return CleanSignUp(
            input.loginId, input.password, profile.fullName, profile.centerCode, profile.driverId, profile.phone,
        )
    }

    /** The fields of a request that both sign-up and a later request share. */
    fun cleanProfile(input: AccessProfile): CleanProfile {
        val name = squash(input.fullName)
        val center = input.centerCode.trim().uppercase(Locale.ROOT).ifEmpty { null }
        val driver = input.driverId.trim().ifEmpty { null }
        val phone = normalizePhone(input.phone)
        val problem = when {
            name.isEmpty() || name.length > MAX_NAME || name.any { it.isISOControl() } ->
                "The name is 1 to $MAX_NAME letters"
            center != null && !CENTER.matches(center) ->
                "The center code is 2 to 20 letters, digits, dashes or underscores"
            driver != null && !DRIVER.matches(driver) ->
                "The driver ID is 1 to 32 letters, digits, dashes or underscores"
            phone == null -> "The phone number is not a Korean mobile number"
            else -> null
        }
        if (problem != null) throw InvalidSignUp(problem)
        return CleanProfile(name, center, driver, checkNotNull(phone))
    }

    /** `010-1234-5678`, `01012345678` and `+821012345678` all become `+821012345678`. Anything else is null. */
    fun normalizePhone(text: String): String? {
        val compact = text.filterNot { it == '-' || it == ' ' }
        val e164 = when {
            compact.startsWith("+82") -> compact
            compact.startsWith("0") -> "+82" + compact.drop(1)
            else -> compact
        }
        return e164.takeIf(KoreanMobile::isValid)
    }
}

/**
 * Sign-up: one action makes the account, its pending membership and the access request, together or not at all.
 * The person is not approved by this. The terms text does not exist yet, so the accepted version is a marker.
 */
class SignUpService(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val accounts: AccountService,
    private val clock: Clock,
    private val dailyLimit: Int,
    /** Null when phone verification is not configured: then sign-up does not ask for a proof. */
    private val phoneProof: PhoneProof? = null,
    private val invites: CenterInviteService? = null,
    /** When true, only a person with a valid sign-up code can make an account. */
    val codeRequired: Boolean = false,
) {
    fun signUp(input: SignUp): Account {
        val clean = SignUpRules.clean(input)
        if (codeRequired) requireInvites().requireValid(input.inviteCode, "signup:${clean.phone}")
        val verified = phoneVerified(input.phoneProof, clean.phone)
        val now = clock.instant()
        if (opened(now) >= dailyLimit) throw SignUpClosed()
        val hash = accounts.hash(clean.password) // slow, so before the transaction
        return transactions.execute { create(clean, hash, now, verified, input.inviteCode) }
            ?: error("The sign-up transaction returned nothing")
    }

    private fun requireInvites(): CenterInviteService = checkNotNull(invites) { "Sign-up codes need the database" }

    /** With a configured provider the number must be proved at sign-up, and it must be the number given. */
    private fun phoneVerified(token: String, number: String): Boolean {
        val proof = phoneProof ?: return false
        val proved = if (token.isBlank()) null else runCatching { proof.verify(token) }.getOrNull()
        val problem = when {
            token.isBlank() -> "The phone number must be verified"
            proved == null -> "The phone proof was refused"
            proved.number != number -> "The verified phone number is not the number given"
            else -> null
        }
        if (problem != null) throw InvalidSignUp(problem)
        return true
    }

    /**
     * A signed-in account without access asks again (never asked, expired, rejected or revoked). One waiting
     * request at a time. The same daily cap as sign-up applies, so the queue cannot be flooded this way either.
     */
    fun requestAgain(actor: Account, input: AccessProfile): Account {
        val profile = SignUpRules.cleanProfile(input)
        val now = clock.instant()
        val blocked = when (actor.membership) {
            MembershipStatus.PENDING -> "A request is already waiting"
            MembershipStatus.ACTIVE -> "This account already has access"
            else -> null
        }
        if (blocked != null) throw StateConflict(blocked)
        if (opened(now) >= dailyLimit) throw SignUpClosed()
        transactions.executeWithoutResult { open(actor.id, profile, now) }
        return actor.copy(role = null, membership = MembershipStatus.PENDING)
    }

    private fun open(accountId: String, profile: CleanProfile, now: Instant) {
        val at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        // Locks the account row, so a double click or two tabs open one request.
        jdbc.sql("SELECT account_id FROM account WHERE account_id = :id FOR UPDATE").param("id", accountId)
            .query().listOfRows()
        val status = jdbc.sql("SELECT status FROM membership WHERE account_id = :id")
            .param("id", accountId).query(String::class.java).optional().orElse(null)
        val waiting = jdbc.sql("SELECT COUNT(*) FROM access_request WHERE account_id = :id AND status = 'PENDING'")
            .param("id", accountId).query(Int::class.java).single()
        if (status == "ACTIVE" || status == "PENDING" || waiting > 0) {
            throw StateConflict("A request is already waiting")
        }
        if (status == null) {
            jdbc.sql(
                "INSERT INTO membership (account_id, role, status, created_at, updated_at) " +
                    "VALUES (:id, NULL, 'PENDING', :now, :now)",
            ).param("id", accountId).param("now", at).update()
        } else {
            jdbc.sql(
                "UPDATE membership SET role = NULL, status = 'PENDING', decided_by = NULL, decided_at = NULL, " +
                    "updated_at = :now WHERE account_id = :id",
            ).param("id", accountId).param("now", at).update()
        }
        jdbc.sql(
            "INSERT INTO access_request (request_id, account_id, full_name, center_code, driver_id, phone, " +
                "terms_version, status, created_at, expires_at) " +
                "VALUES (:rid, :id, :name, :center, :driver, :phone, :terms, 'PENDING', :now, :expires)",
        )
            .param("rid", UUID.randomUUID().toString()).param("id", accountId).param("name", profile.fullName)
            .param("center", profile.centerCode).param("driver", profile.driverId).param("phone", profile.phone)
            .param("terms", TERMS_VERSION).param("now", at)
            .param("expires", OffsetDateTime.ofInstant(now.plus(REQUEST_LIFETIME), ZoneOffset.UTC)).update()
    }

    private fun create(clean: CleanSignUp, hash: String, now: Instant, verified: Boolean, inviteCode: String): Account {
        val account = accounts.insertAccount(clean.loginId, hash)
        val at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        // With a code the account is active at once, with the role and centers of the code. The code is used in
        // this transaction, so a code that was taken a moment ago stops the whole sign-up.
        val grant = if (codeRequired) {
            requireInvites().grantNewAccount(inviteCode, account.id, now)
                ?: throw InvalidSignUp("The sign-up code is not valid")
        } else {
            null
        }
        if (grant == null) {
            jdbc.sql(
                "INSERT INTO membership (account_id, role, status, created_at, updated_at) " +
                    "VALUES (:id, NULL, 'PENDING', :now, :now)",
            ).param("id", account.id).param("now", at).update()
        }
        val status = if (grant == null) "PENDING" else "APPROVED"
        jdbc.sql(
            "INSERT INTO access_request (request_id, account_id, full_name, center_code, driver_id, phone, " +
                "phone_verified, terms_version, status, decided_by, decided_at, decision_role, created_at, " +
                "expires_at) VALUES (:rid, :id, :name, :center, :driver, :phone, :verified, :terms, :status, " +
                ":by, :decidedAt, :role, :now, :expires)",
        )
            .param("rid", UUID.randomUUID().toString()).param("id", account.id).param("name", clean.fullName)
            .param("center", clean.centerCode).param("driver", clean.driverId).param("phone", clean.phone)
            .param("verified", verified).param("terms", TERMS_VERSION).param("status", status)
            .param("by", grant?.createdBy).param("decidedAt", if (grant == null) null else at)
            .param("role", grant?.role).param("now", at)
            .param("expires", OffsetDateTime.ofInstant(now.plus(REQUEST_LIFETIME), ZoneOffset.UTC)).update()
        if (grant == null) return account.copy(membership = MembershipStatus.PENDING)
        return account.copy(role = Role.entries.first { it.value == grant.role }, membership = MembershipStatus.ACTIVE)
    }

    /** New requests in the last 24 hours, counted for everyone: a cap on a flood of sign-ups. */
    private fun opened(now: Instant): Int =
        jdbc.sql("SELECT COUNT(*) FROM access_request WHERE created_at > :since")
            .param("since", OffsetDateTime.ofInstant(now.minus(COUNT_WINDOW), ZoneOffset.UTC))
            .query(Int::class.java).single()

    companion object {
        /** No terms text exists yet; this marks that an acceptance was recorded against a draft. */
        const val TERMS_VERSION = "draft-2026-10-12"
        val REQUEST_LIFETIME: Duration = Duration.ofDays(14)
        private val COUNT_WINDOW: Duration = Duration.ofDays(1)
    }
}
