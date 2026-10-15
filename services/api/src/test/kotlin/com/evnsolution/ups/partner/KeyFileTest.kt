package com.evnsolution.ups.partner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class KeyFileTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `reads the key without the line end`() {
        assertEquals("abc-123", KeyFile.read(writeKeyFile(dir, "abc-123")))
    }

    @Test
    fun `refuses a file that other users can read, and does not show the key`() {
        val file = writeKeyFile(dir, "secret-value")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
        val e = assertThrows<Ours24Exception> { KeyFile.read(file) }
        assertFalse(e.message!!.contains("secret-value"))
    }

    @Test
    fun `refuses a link`() {
        val link = dir.resolve("link")
        Files.createDirectory(dir.resolve("real"))
        Files.createSymbolicLink(link, writeKeyFile(dir.resolve("real")))
        assertThrows<Ours24Exception> { KeyFile.read(link) }
    }

    @Test
    fun `refuses a missing file`() {
        assertThrows<Ours24Exception> { KeyFile.read(dir.resolve("none")) }
    }

    @Test
    fun `refuses a key with a space, an empty key and a non-ASCII key`() {
        for (content in listOf("two words", "", "키")) {
            val file = dir.resolve("bad")
            Files.deleteIfExists(file)
            Files.write(file, "$content\n".toByteArray())
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
            assertThrows<Ours24Exception> { KeyFile.read(file) }
        }
    }
}
