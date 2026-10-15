package com.evnsolution.ups.geocoding

/** What a geocoder answers for one Korean address. */
sealed interface GeoResult {
    data class Found(val lat: Double, val lng: Double) : GeoResult

    /** The provider answered and has no place for this text. */
    data object NotFound : GeoResult

    /** The provider answered with a place outside Korea, which is never written. */
    data object OutsideKorea : GeoResult

    /** No provider is set up, or it refused, was rate limited or could not be reached. */
    data object Unavailable : GeoResult
}

/** Turns a Korean address into a place. Only the address text is given; nothing about the person. */
fun interface Geocoder {
    fun locate(addressKo: String): GeoResult
}
