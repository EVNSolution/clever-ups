package com.evnsolution.ups.addresses

import com.evnsolution.ups.accounts.Role
import com.evnsolution.ups.accounts.SESSION_ATTRIBUTE
import com.evnsolution.ups.accounts.SessionInfo
import com.evnsolution.ups.geocoding.GeoResult
import com.evnsolution.ups.geocoding.Geocoder
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** The Korean address an administrator types for an address that has none. */
data class AddressKoBody(val addressKo: String = "")

/**
 * What came of it. [placed] is true when a position was found and written. Otherwise [reason] says why not:
 * NOT_FOUND (the provider has no place for the text), OUTSIDE_KOREA, or UNAVAILABLE (no provider is set up or
 * it could not answer); the Korean address is saved in every case.
 */
data class AddressKoResult(val placed: Boolean, val reason: String? = null)

/**
 * The door for people: an administrator or the owner types the Korean address of an order. It is kept as MANUAL
 * and placed on the spot when a geocoder is set up. When no place is found it waits as PENDING without one, so
 * a later geocoding run tries again. The service door `/api/addresses` stays closed to people.
 */
@RestController
@RequestMapping("/api/address-ko")
class AddressKoController(private val store: AddressStore, private val geocoders: ObjectProvider<Geocoder>) {
    @PutMapping("/{key}")
    fun put(
        @PathVariable key: String,
        @RequestBody body: AddressKoBody,
        request: HttpServletRequest,
    ): ResponseEntity<AddressKoResult> {
        val role = (request.getAttribute(SESSION_ATTRIBUTE) as? SessionInfo)?.account?.role
        if (role != Role.OWNER && role != Role.ADMIN) throw ResponseStatusException(HttpStatus.FORBIDDEN, "admin only")
        val text = body.addressKo.trim().replace(WHITE_SPACE, " ")
        val problem = when {
            !KEY.matches(key) -> "key is not an address key"
            text.isEmpty() || text.length > MAX_LENGTH -> "addressKo must be 1..$MAX_LENGTH characters"
            else -> null
        }
        if (problem != null) throw ResponseStatusException(HttpStatus.BAD_REQUEST, problem)
        val result = geocoders.getIfAvailable()?.locate(text) ?: GeoResult.Unavailable
        if (!store.update(key, updateFor(text, result))) return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        return ResponseEntity.ok(outcomeOf(result))
    }

    private fun updateFor(text: String, result: GeoResult): LocationUpdate =
        if (result is GeoResult.Found) {
            LocationUpdate(LocationStatus.DONE, text, result.lat, result.lng, koSource = KoSource.MANUAL)
        } else {
            LocationUpdate(LocationStatus.PENDING, text, koSource = KoSource.MANUAL)
        }

    private fun outcomeOf(result: GeoResult): AddressKoResult =
        when (result) {
            is GeoResult.Found -> AddressKoResult(true)
            GeoResult.NotFound -> AddressKoResult(false, "NOT_FOUND")
            GeoResult.OutsideKorea -> AddressKoResult(false, "OUTSIDE_KOREA")
            GeoResult.Unavailable -> AddressKoResult(false, "UNAVAILABLE")
        }

    private companion object {
        const val MAX_LENGTH = 200
        val KEY = Regex("[0-9a-f]{64}")
        val WHITE_SPACE = Regex("\\s+")
    }
}
