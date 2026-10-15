package com.evnsolution.ups.phone

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import java.text.ParseException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Checks a Firebase Authentication ID token that a browser obtained by verifying a phone number.
 * Accepted only when all of these hold: RS256 signature by one of Google's keys, issuer and audience of this
 * project, not expired, the phone provider, a Korean mobile number, and a sign-in newer than [maxAge].
 * A token without the phone provider (for example an email sign-in of the same project) is refused.
 *
 * NOTE for whoever adds the sign-up flow: keep the proof to "this person holds this number just now".
 * Do not use it as a login. The caller must compare the number with the application's own record and
 * must store the number, not the token.
 */
class FirebasePhoneProof(
    private val projectId: String,
    keys: JWKSource<SecurityContext>,
    private val clock: Clock = Clock.systemUTC(),
    private val maxAge: Duration = DEFAULT_MAX_AGE,
) : PhoneProof {
    private val processor = DefaultJWTProcessor<SecurityContext>().apply {
        jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, keys)
        jwtClaimsSetVerifier = DefaultJWTClaimsVerifier(
            JWTClaimsSet.Builder().audience(projectId).issuer("https://securetoken.google.com/$projectId").build(),
            setOf("sub", "iat", "exp", "auth_time"),
        )
    }

    override fun verify(idToken: String): VerifiedPhone {
        val claims = parse(idToken)
        val number = claims.getStringClaim("phone_number")
        val provedAt = Instant.ofEpochSecond(claims.getLongClaim("auth_time"))
        val reason = refusal(claims, number, provedAt)
        if (reason != null) throw PhoneProofException(reason)
        return VerifiedPhone(checkNotNull(number), claims.subject, provedAt)
    }

    private fun parse(idToken: String): JWTClaimsSet =
        try {
            processor.process(idToken, null)
        } catch (e: ParseException) {
            throw PhoneProofException("The phone proof is not a token", e)
        } catch (e: BadJOSEException) {
            throw PhoneProofException("The phone proof was refused", e)
        } catch (e: JOSEException) {
            throw PhoneProofException("The phone proof could not be checked", e)
        }

    private fun refusal(claims: JWTClaimsSet, number: String?, provedAt: Instant): String? {
        val provider = (claims.getClaim("firebase") as? Map<*, *>)?.get("sign_in_provider")
        val now = clock.instant()
        return when {
            provider != PHONE_PROVIDER -> "The proof is not a phone sign-in"
            number == null || !KoreanMobile.isValid(number) -> "The proof does not hold a Korean mobile number"
            provedAt.isAfter(now.plus(SKEW)) || Duration.between(provedAt, now) > maxAge -> "The phone proof is too old"
            else -> null
        }
    }

    private companion object {
        const val PHONE_PROVIDER = "phone"
        const val DEFAULT_MAX_AGE_MINUTES = 5L
        val DEFAULT_MAX_AGE: Duration = Duration.ofMinutes(DEFAULT_MAX_AGE_MINUTES)
        val SKEW: Duration = Duration.ofSeconds(60)
    }
}
