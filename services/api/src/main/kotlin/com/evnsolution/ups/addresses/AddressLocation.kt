package com.evnsolution.ups.addresses

import com.evnsolution.ups.orders.Order
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

enum class LocationStatus { PENDING, DONE, FAILED }

/** Who made the Korean form of an address. */
enum class KoSource { TRANSLATOR, REFERENCE, MANUAL }

/** Result of checking the Korean form against the reference set. */
enum class VerifyStatus { UNVERIFIED, MATCH, MISMATCH, NO_REFERENCE }

/** The text of an address as Ours24 hands it over. English only; Ours24 has no coordinates and no Korean form. */
data class AddressText(
    val address1: String,
    val address2: String = "",
    val postalCode: String = "",
    val companyName: String = "",
) {
    val key: String get() = AddressKey.of(this)
}

/** An address and what is known about its place. [addressKo], [lat] and [lng] are set together with DONE. */
data class AddressLocation(
    val key: String,
    val address1: String,
    val address2: String,
    val postalCode: String,
    val companyName: String,
    val addressKo: String?,
    val lat: Double?,
    val lng: Double?,
    val status: LocationStatus,
    val updatedAt: Instant,
    val detailKo: String? = null,
    val koSource: KoSource? = null,
    val verifyStatus: VerifyStatus = VerifyStatus.UNVERIFIED,
)

/** A Korean address taken from the Fleetune upload file. A reference to check against, not a confirmed answer. */
data class AddressReference(val key: String, val addressKo: String, val detailKo: String?)

/** What a service writes back for one address. */
data class LocationUpdate(
    val status: LocationStatus,
    val addressKo: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    /** The unit or building part of the Korean address. Needs [addressKo]. */
    val detailKo: String? = null,
    val koSource: KoSource? = null,
    /** Omitted means "leave the stored value as it is". */
    val verifyStatus: VerifyStatus? = null,
)

/**
 * The same address must always give the same key, whichever Job it arrives in: trim, collapse white space
 * and ignore case in each part, then hash the four parts. Changing this rule orphans every stored row, so
 * change it only together with a migration.
 */
object AddressKey {
    fun of(text: AddressText): String {
        val parts = listOf(text.address1, text.address2, text.postalCode, text.companyName).map(::normalize)
        val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\n").toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun normalize(part: String): String = part.trim().replace(WHITE_SPACE, " ").uppercase(Locale.ROOT)

    private val WHITE_SPACE = Regex("\\s+")
}

/** The address an order points at, or null when the order has no address line. */
fun Order.addressText(): AddressText? =
    address1?.takeIf { it.isNotBlank() }
        ?.let { AddressText(it, address2.orEmpty(), postalCode.orEmpty(), companyName.orEmpty()) }

/**
 * Where addresses and their places are kept. No database is connected by default, so the default keeps nothing
 * and orders pass through unchanged.
 */
interface AddressStore {
    /** Stores the addresses of [orders] that are new (as PENDING) and returns the orders with the place filled in. */
    fun locate(orders: List<Order>): List<Order>

    /** Addresses waiting for a place, oldest first. */
    fun withStatus(status: LocationStatus, limit: Int): List<AddressLocation>

    /** Returns false when no address has this [key]. */
    fun update(key: String, update: LocationUpdate): Boolean

    /** Reference Korean addresses in key order, after the key [after] (exclusive). */
    fun references(limit: Int, after: String?): List<AddressReference>
}
