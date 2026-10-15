package com.evnsolution.ups.addresses

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class AddressKeyTest {
    @Test
    fun `the same address gives the same key whatever the case and spacing`() {
        val a = AddressText("12  Teheran-ro ", "Suite 3", "06134", "ACME")
        val b = AddressText("12 teheran-ro", " suite   3 ", "06134 ", "acme")
        assertEquals(a.key, b.key)
        assertEquals(64, a.key.length)
    }

    @Test
    fun `a different part gives a different key`() {
        val base = AddressText("12 Teheran-ro", "Suite 3", "06134")
        assertNotEquals(base.key, base.copy(address2 = "Suite 4").key)
        assertNotEquals(base.key, base.copy(postalCode = "06135").key)
        // The parts are joined with a separator, so moving text between parts changes the key.
        assertNotEquals(AddressText("A", "B C").key, AddressText("A B", "C").key)
    }
}
