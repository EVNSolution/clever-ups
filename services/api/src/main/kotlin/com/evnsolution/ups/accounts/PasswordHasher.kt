package com.evnsolution.ups.accounts

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 from the JDK, kept as `pbkdf2-sha256$iterations$salt$hash`. The count is stored with the
 * hash, so it can be raised later and old hashes still verify. 600,000 is the current OWASP figure for this
 * algorithm. A memory-hard function (Argon2id) is stronger; it needs a library, so it is the upgrade path.
 */
class PasswordHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) {
    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val key = derive(password, salt, iterations)
        return listOf(SCHEME, iterations, encode(salt), encode(key)).joinToString(SEPARATOR)
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split(SEPARATOR)
        val count = parts.getOrNull(ITERATIONS_AT)?.toIntOrNull()
        return parts.size == PARTS && parts[0] == SCHEME && count != null && count in 1..MAX_ITERATIONS &&
            MessageDigest.isEqual(derive(password, decode(parts[SALT_AT]), count), decode(parts[HASH_AT]))
    }

    private fun derive(password: String, salt: ByteArray, count: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, count, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    companion object {
        const val DEFAULT_ITERATIONS = 600_000
        private const val MAX_ITERATIONS = 10_000_000
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
        private const val SCHEME = "pbkdf2-sha256"
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val SEPARATOR = "$"
        private const val PARTS = 4
        private const val ITERATIONS_AT = 1
        private const val SALT_AT = 2
        private const val HASH_AT = 3
    }
}
