package com.evnsolution.ups.phone

import java.time.Instant

/** A phone number that its holder has just proved to own, in E.164 form such as +821012345678. */
data class VerifiedPhone(val number: String, val subject: String, val provedAt: Instant)

/** The proof is missing, wrong, too old or not a Korean mobile number. The message never holds the token. */
class PhoneProofException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Turns what a person's browser got from the phone-verification provider into a phone number the server
 * can trust. The provider sends the one-time code and the browser enters it; this server only checks the
 * signed result. Nothing here stores anything.
 */
fun interface PhoneProof {
    fun verify(idToken: String): VerifiedPhone
}

/** A Korean mobile number in E.164 form. Landlines, other countries and local forms are not accepted. */
object KoreanMobile {
    private val FORM = Regex("\\+821[0-9]{8,9}")

    fun isValid(number: String): Boolean = FORM.matches(number)
}
