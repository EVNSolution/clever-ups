package com.evnsolution.ups.addresses

import com.evnsolution.ups.orders.Order
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * Keeps [AddressText] rows in `address_location`.
 *
 * NOTE for the person who writes the translator and the geocoder (not part of this class on purpose):
 *  - The queue is the rows with status PENDING. Read them with [withStatus], work on them outside this
 *    process (a script or a separate service is fine), and write the answer back with [update].
 *  - The text is English and may be noisy (unit numbers, company names inside the address). Make the
 *    Korean road-name address first ([LocationUpdate.addressKo]), then geocode that, not the English text.
 *  - Answer DONE only with a place you trust; answer FAILED when you cannot find one, so the row leaves the
 *    queue and a person can look at it. A DONE row without lat and lng is refused by the database.
 *  - Positions in Korea fall roughly in lat 33..39 and lng 124..132. A result outside that is almost
 *    certainly wrong; treat it as FAILED.
 *  - Do not store customer names here and do not write to Ours24 from this code.
 */
class JdbcAddressStore(private val jdbc: JdbcClient) : AddressStore {
    override fun locate(orders: List<Order>): List<Order> {
        val texts = orders.mapNotNull { it.addressText() }.distinctBy { it.key }
        val known = find(texts.map { it.key })
        // ponytail: one INSERT per new address; batch them if a single day brings tens of thousands of new ones.
        texts.filter { it.key !in known }.forEach(::register)
        return orders.map { order ->
            val place = order.addressText()?.let { known[it.key] }
            val located = when {
                place == null -> order
                // The Korean address is shown as soon as it exists; the position comes only with DONE.
                place.status != LocationStatus.DONE ->
                    order.copy(addressKo = place.addressKo, detailKo = place.detailKo)
                else -> order.copy(
                    lat = order.lat ?: place.lat,
                    lng = order.lng ?: place.lng,
                    addressKo = place.addressKo,
                    detailKo = place.detailKo,
                )
            }
            located.copy(addressKey = order.addressText()?.key)
        }
    }

    override fun withStatus(status: LocationStatus, limit: Int): List<AddressLocation> =
        jdbc.sql("SELECT * FROM address_location WHERE status = :status ORDER BY created_at, address_key LIMIT :limit")
            .param("status", status.name)
            .param("limit", limit)
            .query { rs, _ -> row(rs) }
            .list()

    override fun update(key: String, update: LocationUpdate): Boolean =
        jdbc.sql(
            "UPDATE address_location SET status = :status, address_ko = :ko, lat = :lat, lng = :lng, " +
                "detail_ko = :detail, ko_source = :source, " +
                "verify_status = COALESCE(CAST(:verify AS TEXT), verify_status), " +
                "verified_at = CASE WHEN CAST(:verify AS TEXT) IS NULL THEN verified_at ELSE CURRENT_TIMESTAMP END, " +
                "updated_at = CURRENT_TIMESTAMP WHERE address_key = :key",
        )
            .param("status", update.status.name)
            .param("ko", update.addressKo)
            .param("lat", update.lat)
            .param("lng", update.lng)
            .param("detail", update.detailKo)
            .param("source", update.koSource?.name)
            .param("verify", update.verifyStatus?.name)
            .param("key", key)
            .update() > 0

    override fun references(limit: Int, after: String?): List<AddressReference> =
        jdbc.sql(
            "SELECT address_key, address_ko, detail_ko FROM address_reference " +
                "WHERE address_key > :after ORDER BY address_key LIMIT :limit",
        )
            .param("after", after ?: "")
            .param("limit", limit)
            .query { rs, _ ->
                AddressReference(
                    rs.getString("address_key").trim(),
                    rs.getString("address_ko"),
                    rs.getString("detail_ko"),
                )
            }
            .list()

    private fun register(text: AddressText) {
        try {
            jdbc.sql(
                "INSERT INTO address_location (address_key, address1, address2, postal_code, company_name) " +
                    "SELECT :key, :a1, :a2, :postal, :company " +
                    "WHERE NOT EXISTS (SELECT 1 FROM address_location WHERE address_key = :key)",
            )
                .param("key", text.key)
                .param("a1", text.address1.trim())
                .param("a2", text.address2.trim())
                .param("postal", text.postalCode.trim())
                .param("company", text.companyName.trim())
                .update()
        } catch (_: DuplicateKeyException) {
            // Another request registered the same address between the check and the insert. That is fine.
        }
    }

    private fun find(keys: List<String>): Map<String, AddressLocation> =
        if (keys.isEmpty()) {
            emptyMap()
        } else {
            keys.chunked(IN_LIMIT).flatMap { chunk ->
                jdbc.sql("SELECT * FROM address_location WHERE address_key IN (:keys)")
                    .param("keys", chunk)
                    .query { rs, _ -> row(rs) }
                    .list()
            }.associateBy { it.key }
        }

    private fun row(rs: ResultSet) = AddressLocation(
        key = rs.getString("address_key"),
        address1 = rs.getString("address1"),
        address2 = rs.getString("address2"),
        postalCode = rs.getString("postal_code"),
        companyName = rs.getString("company_name"),
        addressKo = rs.getString("address_ko"),
        lat = rs.getObject("lat", java.lang.Double::class.java)?.toDouble(),
        lng = rs.getObject("lng", java.lang.Double::class.java)?.toDouble(),
        status = LocationStatus.valueOf(rs.getString("status")),
        updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        detailKo = rs.getString("detail_ko"),
        koSource = rs.getString("ko_source")?.let { KoSource.valueOf(it) },
        verifyStatus = VerifyStatus.valueOf(rs.getString("verify_status")),
    )

    private companion object {
        const val IN_LIMIT = 500
    }
}
