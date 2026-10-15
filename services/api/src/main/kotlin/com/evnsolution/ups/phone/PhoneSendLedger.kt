package com.evnsolution.ups.phone

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class SendStatus { RESERVED, SENT, FAILED, UNKNOWN }

enum class DenyReason { DAILY_LIMIT, ACCOUNT_LIMIT, COOLDOWN }

/**
 * [daily] caps all messages of the service inside a rolling [window]. A rolling window, not a calendar day,
 * because the provider's day boundary is not known here: no 24 hours can hold more than [daily] messages,
 * so no provider day can either.
 */
data class SendLimits(
    val daily: Int = DEFAULT_DAILY,
    val perAccount: Int = DEFAULT_PER_ACCOUNT,
    val cooldown: Duration = Duration.ofSeconds(DEFAULT_COOLDOWN_SECONDS),
    val window: Duration = Duration.ofHours(DEFAULT_WINDOW_HOURS),
) {
    init {
        require(daily >= 1 && perAccount >= 1) { "limits must be at least 1" }
    }

    private companion object {
        const val DEFAULT_DAILY = 10
        const val DEFAULT_PER_ACCOUNT = 3
        const val DEFAULT_COOLDOWN_SECONDS = 60L
        const val DEFAULT_WINDOW_HOURS = 24L
    }
}

sealed interface Reservation {
    /** The caller may send exactly one message for [sendId], and must record the outcome. */
    data class Granted(val sendId: String) : Reservation

    /** This request was seen before. Nothing may be sent again, whatever the earlier status was. */
    data class Duplicate(val sendId: String, val status: SendStatus) : Reservation

    data class Denied(val reason: DenyReason, val retryAfter: Instant?) : Reservation
}

/**
 * The server-side gate in front of every verification message. Reserve first, send second, record third.
 *
 * Why this cannot send twice or go over the cap:
 *  - A reservation is one transaction that first locks the single gate row, so reservations run one at a time
 *    on every instance and every slot. Inside the lock it counts the rows and inserts its own.
 *  - `request_key` is unique, so a repeated or double-clicked request meets the row of the first one.
 *  - A message that may have left (timeout, crash after reserve) stays counted and is never retried here.
 *    A lost message costs the person one more explicit request; a duplicate would cost real money and trust.
 */
class PhoneSendLedger(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val limits: SendLimits = SendLimits(),
) {
    fun reserve(accountId: String, phone: String, requestKey: String, now: Instant): Reservation =
        try {
            transactions.execute { decide(accountId, phone, requestKey, now) }
        } catch (_: DuplicateKeyException) {
            // The unique key caught a request that raced past the lock. It is the same answer as a repeat.
            findByKey(requestKey)?.let { Reservation.Duplicate(it.first, it.second) }
                ?: error("A duplicate request key was reported but no row holds it")
        } ?: error("The reservation transaction returned nothing")

    /** Moves a RESERVED row to a final status. Returns false when the row was not RESERVED any more. */
    fun finish(sendId: String, status: SendStatus, providerRef: String?, now: Instant): Boolean {
        require(status != SendStatus.RESERVED) { "a send is finished with SENT, FAILED or UNKNOWN" }
        return jdbc.sql(
            "UPDATE phone_send SET status = :status, provider_ref = :ref, updated_at = :now " +
                "WHERE send_id = :id AND status = 'RESERVED'",
        )
            .param("status", status.name)
            .param("ref", providerRef)
            .param("now", at(now))
            .param("id", sendId)
            .update() == 1
    }

    private fun decide(accountId: String, phone: String, requestKey: String, now: Instant): Reservation {
        jdbc.sql("SELECT id FROM phone_send_gate WHERE id = 1 FOR UPDATE").query().listOfRows()
        val earlier = findByKey(requestKey)
        if (earlier != null) return Reservation.Duplicate(earlier.first, earlier.second)
        return denial(accountId, phone, now) ?: insert(accountId, phone, requestKey, now)
    }

    private fun denial(accountId: String, phone: String, now: Instant): Reservation.Denied? {
        val since = at(now.minus(limits.window))
        val coolEnd = lastSendTo(phone, since)?.plus(limits.cooldown)
        return when {
            coolEnd != null && coolEnd.isAfter(now) -> Reservation.Denied(DenyReason.COOLDOWN, coolEnd)
            counted(since, accountId) >= limits.perAccount -> Reservation.Denied(DenyReason.ACCOUNT_LIMIT, null)
            counted(since, null) >= limits.daily -> Reservation.Denied(DenyReason.DAILY_LIMIT, null)
            else -> null
        }
    }

    private fun lastSendTo(phone: String, since: OffsetDateTime): Instant? =
        jdbc.sql(
            "SELECT MAX(created_at) FROM phone_send " +
                "WHERE phone = :phone AND status <> 'FAILED' AND created_at > :since",
        )
            .param("phone", phone).param("since", since)
            .query(OffsetDateTime::class.java).optional().orElse(null)?.toInstant()

    private fun insert(accountId: String, phone: String, requestKey: String, now: Instant): Reservation.Granted {
        val sendId = UUID.randomUUID().toString()
        jdbc.sql(
            "INSERT INTO phone_send (send_id, request_key, account_id, phone, status, created_at, updated_at) " +
                "VALUES (:id, :key, :account, :phone, 'RESERVED', :now, :now)",
        )
            .param("id", sendId).param("key", requestKey).param("account", accountId)
            .param("phone", phone).param("now", at(now))
            .update()
        return Reservation.Granted(sendId)
    }

    /** FAILED rows (the provider refused before anything was sent) do not count. Everything else does. */
    private fun counted(since: OffsetDateTime, accountId: String?): Int =
        if (accountId == null) {
            jdbc.sql("SELECT COUNT(*) FROM phone_send WHERE created_at > :since AND status <> 'FAILED'")
                .param("since", since).query(Int::class.java).single()
        } else {
            jdbc.sql(
                "SELECT COUNT(*) FROM phone_send WHERE created_at > :since AND status <> 'FAILED' " +
                    "AND account_id = :account",
            ).param("since", since).param("account", accountId).query(Int::class.java).single()
        }

    private fun findByKey(requestKey: String): Pair<String, SendStatus>? =
        jdbc.sql("SELECT send_id, status FROM phone_send WHERE request_key = :key")
            .param("key", requestKey)
            .query { rs, _ -> rs.getString("send_id").trim() to SendStatus.valueOf(rs.getString("status")) }
            .optional().orElse(null)

    private fun at(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
}
