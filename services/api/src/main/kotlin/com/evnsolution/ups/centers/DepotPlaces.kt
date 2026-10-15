package com.evnsolution.ups.centers

import com.evnsolution.ups.geocoding.GeoResult
import com.evnsolution.ups.geocoding.Geocoder
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * The place of a depot address, found with the geocoder. Answers are kept while the server runs; a provider that
 * could not answer is asked again next time. Nothing is written to the database.
 */
@Component
class DepotPlaces(private val geocoders: ObjectProvider<Geocoder>) {
    private val places = ConcurrentHashMap<String, GeoResult>()

    fun of(address: String?): GeoResult.Found? {
        val geocoder = geocoders.getIfAvailable()
        if (geocoder == null || address.isNullOrBlank()) return null
        val result = places[address] ?: geocoder.locate(address).also {
            if (it != GeoResult.Unavailable) places[address] = it
        }
        return result as? GeoResult.Found
    }
}
