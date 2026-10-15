package com.evnsolution.ups.addresses

import com.evnsolution.ups.orders.OrderQuery
import com.evnsolution.ups.orders.OrderStore
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate

/** [incomplete] is true when the order source may hold more orders than were read. */
data class Refreshed(val orders: Int, val incomplete: Boolean)

/** The door for services (see security/AccessGate.kt). People do not reach this controller. */
@RestController
@RequestMapping("/api/addresses")
class AddressController(private val store: AddressStore, private val orders: OrderStore) {
    @GetMapping
    fun list(
        @RequestParam("status", defaultValue = "PENDING") status: LocationStatus,
        @RequestParam("limit", defaultValue = "100") limit: Int,
    ): List<AddressLocation> {
        if (limit !in 1..MAX_LIMIT) throw bad("limit must be 1..$MAX_LIMIT")
        return store.withStatus(status, limit)
    }

    /**
     * Reads the orders of the days from the order source and queues their new addresses as PENDING.
     * This is how a service fills its own queue; no person has to be signed in. It reads only.
     */
    @PostMapping("/refresh")
    fun refresh(
        @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam("to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): Refreshed {
        val last = to ?: from
        if (last.isBefore(from)) throw bad("to is before from")
        val result = orders.find(OrderQuery(from, last))
        store.locate(result.orders)
        return Refreshed(result.orders.size, result.incomplete)
    }

    /** The reference set, a page at a time. Read only. Page with `after` = the last key of the previous page. */
    @GetMapping("/references")
    fun references(
        @RequestParam("limit", defaultValue = "100") limit: Int,
        @RequestParam("after", required = false) after: String?,
    ): List<AddressReference> {
        if (limit !in 1..MAX_LIMIT) throw bad("limit must be 1..$MAX_LIMIT")
        if (after != null && !KEY.matches(after)) throw bad("after is not an address key")
        return store.references(limit, after)
    }

    @PutMapping("/{key}")
    fun put(@PathVariable key: String, @RequestBody update: LocationUpdate): ResponseEntity<Void> {
        if (!KEY.matches(key)) throw bad("key is not an address key")
        validate(update)
        return if (store.update(key, update)) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        }
    }

    /** Mirrors the database check, so a caller gets a reason instead of a server error. */
    private fun validate(update: LocationUpdate) {
        val reason = problem(update)
        if (reason != null) throw bad(reason)
    }

    private fun problem(update: LocationUpdate): String? = positionProblem(update) ?: koreanProblem(update)

    private fun positionProblem(update: LocationUpdate): String? {
        val placed = update.lat != null && update.lng != null
        return when {
            update.status == LocationStatus.DONE && !placed -> "DONE needs lat and lng"
            update.status != LocationStatus.DONE && (update.lat != null || update.lng != null) ->
                "only DONE may carry lat and lng"
            update.lat != null && update.lat !in LAT_RANGE -> "lat is out of range"
            update.lng != null && update.lng !in LNG_RANGE -> "lng is out of range"
            else -> null
        }
    }

    /** A Korean address says who made it; a detail or a source without an address is refused. */
    private fun koreanProblem(update: LocationUpdate): String? =
        when {
            update.addressKo == null && (update.detailKo != null || update.koSource != null) ->
                "detailKo and koSource need addressKo"
            update.addressKo != null && update.koSource == null -> "addressKo needs koSource"
            else -> null
        }

    private fun bad(reason: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, reason)

    private companion object {
        const val MAX_LIMIT = 500
        val LAT_RANGE = -90.0..90.0
        val LNG_RANGE = -180.0..180.0
        val KEY = Regex("[0-9a-f]{64}")
    }
}
