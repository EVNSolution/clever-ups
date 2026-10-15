package com.evnsolution.ups.accounts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PasswordHasherTest {
    private val hasher = PasswordHasher(iterations = 1_000)

    @Test
    fun `a password verifies and a different one does not`() {
        val stored = hasher.hash("correct horse battery")
        assertTrue(hasher.verify("correct horse battery", stored))
        assertFalse(hasher.verify("correct horse batterz", stored))
    }

    @Test
    fun `the same password gives a different hash each time, and the hash holds no password`() {
        val a = hasher.hash("correct horse battery")
        val b = hasher.hash("correct horse battery")
        assertNotEquals(a, b)
        assertFalse(a.contains("correct"))
    }

    @Test
    fun `an old hash with another cost still verifies`() {
        val old = PasswordHasher(iterations = 500).hash("correct horse battery")
        assertTrue(hasher.verify("correct horse battery", old))
        assertEquals("pbkdf2-sha256", old.substringBefore("\$"))
    }

    @Test
    fun `a damaged or foreign stored value never verifies`() {
        val damaged = listOf(
            "", "plain", "bcrypt\$10\$abc\$def", "pbkdf2-sha256\$x\$a\$b", "pbkdf2-sha256\$0\$AAAA\$AAAA",
        )
        for (stored in damaged) {
            assertFalse(hasher.verify("anything", stored), stored)
        }
    }
}
