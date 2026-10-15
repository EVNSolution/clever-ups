package com.evnsolution.ups.centers

import com.evnsolution.ups.geocoding.GeoResult
import com.evnsolution.ups.geocoding.Geocoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory

/** A center carries the place of its depot when the geocoder finds the depot address. */
class CenterPlaceTest {
    private val store = CenterStore {
        listOf(Center("KRSNM", "KRSNM · 성남", "경기도 성남시 중원구 둔촌대로 1"), Center("KRXXX", "KRXXX"))
    }

    private fun controller(geocoder: Geocoder?): CenterController {
        val beans = DefaultListableBeanFactory()
        if (geocoder != null) beans.registerSingleton("geocoder", geocoder)
        return CenterController(store, DepotPlaces(beans.getBeanProvider(Geocoder::class.java)))
    }

    @Test
    fun `a found depot gives the center a place, asked once`() {
        var asked = 0
        val centers = controller { asked++; GeoResult.Found(37.43, 127.15) }
        centers.list()
        val listed = centers.list()
        assertEquals(127.15 to 37.43, listed[0].lng to listed[0].lat)
        assertEquals(null to null, listed[1].lng to listed[1].lat)
        assertEquals(1, asked)
    }

    @Test
    fun `without a geocoder, or when it cannot answer, the center has no place and is asked again`() {
        assertEquals(null, controller(null).list()[0].lng)
        var asked = 0
        val centers = controller { asked++; GeoResult.Unavailable }
        centers.list()
        centers.list()
        assertEquals(2, asked)
    }
}
