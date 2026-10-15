package com.evnsolution.ups.addresses

import com.evnsolution.ups.accounts.Account
import com.evnsolution.ups.accounts.MembershipStatus
import com.evnsolution.ups.accounts.Role
import com.evnsolution.ups.accounts.SESSION_ATTRIBUTE
import com.evnsolution.ups.accounts.SessionInfo
import com.evnsolution.ups.geocoding.GeoResult
import com.evnsolution.ups.geocoding.Geocoder
import com.evnsolution.ups.orders.Order
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.mock.web.MockHttpServletRequest

/** What a typed Korean address becomes for each answer of the geocoder. */
class AddressKoControllerTest {
    private class Recording : AddressStore {
        var last: LocationUpdate? = null

        override fun locate(orders: List<Order>): List<Order> = orders
        override fun withStatus(status: LocationStatus, limit: Int): List<AddressLocation> = emptyList()
        override fun update(key: String, update: LocationUpdate): Boolean {
            last = update
            return true
        }
        override fun references(limit: Int, after: String?): List<AddressReference> = emptyList()
    }

    private val key = "a".repeat(64)

    private fun put(geocoder: Geocoder?, role: Role = Role.ADMIN): Pair<AddressKoResult?, LocationUpdate?> {
        val store = Recording()
        val beans = DefaultListableBeanFactory()
        if (geocoder != null) beans.registerSingleton("geocoder", geocoder)
        val request = MockHttpServletRequest().apply {
            setAttribute(
                SESSION_ATTRIBUTE,
                SessionInfo(Account("id", "login", role, MembershipStatus.ACTIVE), "csrf"),
            )
        }
        val answer = AddressKoController(store, beans.getBeanProvider(Geocoder::class.java))
            .put(key, AddressKoBody("경기도 성남시 수정구 수정로 1"), request)
        return answer.body to store.last
    }

    @Test
    fun `a found place is written as DONE with the position and the MANUAL source`() {
        val (result, update) = put(Geocoder { GeoResult.Found(37.45, 127.13) })
        assertEquals(AddressKoResult(true), result)
        val expected = LocationUpdate(
            LocationStatus.DONE, "경기도 성남시 수정구 수정로 1", 37.45, 127.13, koSource = KoSource.MANUAL,
        )
        assertEquals(expected, update)
    }

    @Test
    fun `no place keeps the address as PENDING without a position, and says why`() {
        for ((answer, reason) in listOf(
            GeoResult.NotFound to "NOT_FOUND",
            GeoResult.OutsideKorea to "OUTSIDE_KOREA",
            GeoResult.Unavailable to "UNAVAILABLE",
        )) {
            val (result, update) = put(Geocoder { answer })
            assertEquals(AddressKoResult(false, reason), result)
            assertEquals(LocationStatus.PENDING, update?.status)
            assertEquals(null, update?.lat)
        }
    }

    @Test
    fun `without a geocoder the address is saved and waits`() {
        val (result, update) = put(null)
        assertEquals(AddressKoResult(false, "UNAVAILABLE"), result)
        assertEquals(LocationStatus.PENDING, update?.status)
    }
}
