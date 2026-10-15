package com.evnsolution.ups.phone

import java.time.Clock
import java.time.Instant

/** The provider refused the request and nothing was sent. The reservation is released. */
class PhoneSendRejected(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Sends one verification message. Any other exception means "it may or may not have left". */
fun interface PhoneSender {
    /**
     * [challenge] is the anti-abuse token the person's browser got for this one send (reCAPTCHA).
     * Returns the provider's reference for this send, which the browser needs to enter the code.
     */
    fun send(phone: String, challenge: String): String?
}

sealed interface SendResult {
    /** [reference] is not stored. It goes back to the person who asked, and nowhere else. */
    data class Sent(val sendId: String, val reference: String?) : SendResult

    /** The message may have been sent. It is counted and it is not retried. The person must ask again. */
    data class Uncertain(val sendId: String) : SendResult

    /** The provider refused and nothing was sent. The reservation no longer counts. */
    data class Failed(val sendId: String) : SendResult

    data class AlreadyHandled(val sendId: String, val status: SendStatus) : SendResult

    data class Denied(val reason: DenyReason, val retryAfter: Instant?) : SendResult
}

/**
 * Reserve, send, record. [requestKey] must come from the person's one action (for example one id made when
 * the form opens), so that a double click or a retried HTTP call is the same request and sends once.
 *
 * NOTE for whoever writes the real [PhoneSender] (Identity Platform `accounts:sendVerificationCode` or a
 * Korean SMS API): throw [PhoneSendRejected] only when the provider clearly answered "not sent" (a 4xx).
 * Let a timeout or a 5xx escape as it is. Never retry inside the sender.
 */
class PhoneSendService(
    private val ledger: PhoneSendLedger,
    private val sender: PhoneSender,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun send(accountId: String, phone: String, requestKey: String, challenge: String = ""): SendResult {
        require(KoreanMobile.isValid(phone)) { "phone is not a Korean mobile number in E.164 form" }
        return when (val reservation = ledger.reserve(accountId, phone, requestKey, clock.instant())) {
            is Reservation.Denied -> SendResult.Denied(reservation.reason, reservation.retryAfter)
            is Reservation.Duplicate -> SendResult.AlreadyHandled(reservation.sendId, reservation.status)
            is Reservation.Granted -> dispatch(reservation.sendId, phone, challenge)
        }
    }

    private fun dispatch(sendId: String, phone: String, challenge: String): SendResult =
        try {
            val reference = sender.send(phone, challenge)
            ledger.finish(sendId, SendStatus.SENT, null, clock.instant())
            SendResult.Sent(sendId, reference)
        } catch (_: PhoneSendRejected) {
            ledger.finish(sendId, SendStatus.FAILED, null, clock.instant())
            SendResult.Failed(sendId)
        } catch (_: RuntimeException) {
            // Timeout, connection loss, 5xx, or a bug: the message may have left. Keep it counted, never resend.
            ledger.finish(sendId, SendStatus.UNKNOWN, null, clock.instant())
            SendResult.Uncertain(sendId)
        }
}
