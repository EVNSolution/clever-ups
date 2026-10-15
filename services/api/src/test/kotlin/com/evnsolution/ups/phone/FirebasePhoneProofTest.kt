package com.evnsolution.ups.phone

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.Date

/** Tokens are signed here with a throwaway key; the verifier is given the matching public key set. */
class FirebasePhoneProofTest {
    private val project = "ups-test-project"
    private val key: RSAKey = RSAKeyGenerator(KEY_BITS).keyID("k1").generate()
    private val now: Instant = Instant.now()
    private val proof = FirebasePhoneProof(
        project,
        ImmutableJWKSet<SecurityContext>(JWKSet(key.toPublicJWK())),
        Clock.fixed(now, java.time.ZoneOffset.UTC),
    )

    private fun token(
        aud: String = project,
        iss: String = "https://securetoken.google.com/$project",
        provider: String? = "phone",
        phone: String? = "+821012345678",
        authAgo: Long = 10,
        expiresIn: Long = 3600,
        signWith: RSAKey = key,
    ): String {
        val claims = JWTClaimsSet.Builder()
            .audience(aud).issuer(iss).subject("uid-1")
            .issueTime(Date.from(now.minusSeconds(authAgo)))
            .expirationTime(Date.from(now.plusSeconds(expiresIn)))
            .claim("auth_time", now.minusSeconds(authAgo).epochSecond)
        if (phone != null) claims.claim("phone_number", phone)
        if (provider != null) claims.claim("firebase", mapOf("sign_in_provider" to provider))
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signWith.keyID).build(), claims.build())
        jwt.sign(RSASSASigner(signWith))
        return jwt.serialize()
    }

    private fun refused(idToken: String) = assertThrows(PhoneProofException::class.java) { proof.verify(idToken) }

    @Test
    fun `a fresh phone sign-in of this project is accepted`() {
        val verified = proof.verify(token())
        assertEquals("+821012345678", verified.number)
        assertEquals("uid-1", verified.subject)
    }

    @Test
    fun `another project, issuer or key is refused`() {
        refused(token(aud = "other-project"))
        refused(token(iss = "https://securetoken.google.com/other-project"))
        refused(token(signWith = RSAKeyGenerator(KEY_BITS).keyID("k1").generate()))
    }

    @Test
    fun `an expired or stale proof is refused`() {
        refused(token(expiresIn = -3600))
        refused(token(authAgo = 600))
    }

    @Test
    fun `only a phone sign-in with a Korean mobile number counts`() {
        refused(token(provider = "password"))
        refused(token(provider = null))
        refused(token(phone = null))
        refused(token(phone = "+14155550123"))
        refused(token(phone = "+82212345678"))
        refused(token(phone = "01012345678"))
    }

    @Test
    fun `a token signed with a shared secret instead of the key is refused`() {
        val claims = JWTClaimsSet.Builder().audience(project).issuer("https://securetoken.google.com/$project")
            .subject("x").issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60)))
            .claim("auth_time", now.epochSecond).claim("phone_number", "+821012345678")
            .claim("firebase", mapOf("sign_in_provider" to "phone")).build()
        val jwt = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
        jwt.sign(MACSigner(ByteArray(MAC_BYTES) { 1 }))
        refused(jwt.serialize())
    }

    @Test
    fun `garbage is refused`() {
        refused("not-a-token")
        refused("")
    }

    private companion object {
        const val KEY_BITS = 2048
        const val MAC_BYTES = 32
    }
}
