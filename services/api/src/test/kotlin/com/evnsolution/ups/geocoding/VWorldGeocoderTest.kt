package com.evnsolution.ups.geocoding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URLDecoder

class VWorldGeocoderTest {
    private val asked = mutableListOf<String>()

    private fun hit(label: String, lng: String = "127.1", lat: String = "37.4") =
        """{"response":{"status":"OK","result":{"items":[{"point":{"x":"$lng","y":"$lat"},""" +
            """"address":{"road":"$label"}}]}}}"""

    private val none = """{"response":{"status":"NOT_FOUND"}}"""

    private fun geocoder(vararg answers: String) =
        VWorldGeocoder("test-key", JsonSource { url ->
            asked += URLDecoder.decode(url, Charsets.UTF_8)
            answers[minOf(asked.size, answers.size) - 1]
        })

    @Test
    fun `a road address is searched as ROAD first and the key and text go in the query`() {
        val result = geocoder(hit("경기도 성남시 수정구 수정로 1")).locate("경기도 성남시 수정구 수정로 1 (복정동) ")
        assertEquals(GeoResult.Found(37.4, 127.1), result)
        assertTrue(asked.single().contains("category=ROAD"))
        assertTrue(asked.single().contains("query=경기도 성남시 수정구 수정로 1&"), "parentheses are removed")
    }

    @Test
    fun `an address without a road number is searched as PARCEL first, then ROAD`() {
        val result = geocoder(none, hit("경기도 성남시 수정구 복정동")).locate("경기도 성남시 수정구 복정동")
        assertTrue(result is GeoResult.Found)
        assertEquals(2, asked.size)
        assertTrue(asked[0].contains("category=PARCEL") && asked[1].contains("category=ROAD"))
    }

    @Test
    fun `an answer from another province is not taken`() {
        val result = geocoder(hit("서울특별시 강남구 테헤란로 1"), none).locate("경기도 성남시 수정구 수정로 1")
        assertEquals(GeoResult.NotFound, result)
    }

    @Test
    fun `a place outside Korea is not written`() {
        val far = geocoder(hit("경기도 성남시", lng = "10.0", lat = "50.0"))
        assertEquals(GeoResult.OutsideKorea, far.locate("경기도 성남시 수정로 1"))
    }

    @Test
    fun `a refusal, an unreadable answer and a lost connection are unavailable`() {
        val refused = """{"response":{"status":"ERROR","error":{"code":"OVER_REQUEST_LIMIT"}}}"""
        assertEquals(GeoResult.Unavailable, geocoder(refused).locate("경기도 성남시 수정로 1"))
        assertEquals(GeoResult.Unavailable, geocoder("not json").locate("경기도 성남시 수정로 1"))
        val down = VWorldGeocoder("k", JsonSource { throw IOException("down") })
        assertEquals(GeoResult.Unavailable, down.locate("경기도 성남시 수정로 1"))
    }

    @Test
    fun `an empty text sends nothing`() {
        assertEquals(GeoResult.NotFound, geocoder(none).locate("  (참고)  "))
        assertFalse(asked.isNotEmpty())
    }
}
