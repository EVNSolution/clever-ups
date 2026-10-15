package com.evnsolution.ups.partner

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions

/**
 * Reads the Ours24 key from a file, with the same checks as scripts/ours24_partner.py:
 * a regular file, not a link, owned by this user, mode 600, printable ASCII. The key never appears in an error.
 */
object KeyFile {
    private const val MAX_BYTES = 4096
    private val PRINTABLE_ASCII = Regex("[\\x21-\\x7e]{1,$MAX_BYTES}")
    private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")

    /** [what] names the secret in error messages, for example "Ours24 key" or "service token". */
    fun read(path: Path, what: String = "Ours24 key"): String {
        val unreadable = "The $what file is missing or unreadable"
        val attributes = try {
            Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            fail(unreadable, e)
        }
        if (!attributes.isRegularFile) fail("The $what path must be a regular file, not a link")
        if (attributes.owner().name != System.getProperty("user.name")) {
            fail("The $what file must belong to the user that runs the API")
        }
        if (attributes.permissions() != OWNER_ONLY) fail("The $what file must have mode 600")
        val bytes = try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_BYTES + 2) }
        } catch (e: IOException) {
            fail(unreadable, e)
        }
        val key = String(bytes, Charsets.US_ASCII).trimEnd('\r', '\n')
        if (!PRINTABLE_ASCII.matches(key)) fail("The $what file does not hold a usable value")
        return key
    }
}
