package com.evnsolution.ups.addresses

import com.evnsolution.ups.orders.Order
import org.springframework.stereotype.Component

@Component
class EmptyAddressStore : AddressStore {
    override fun locate(orders: List<Order>): List<Order> = orders

    override fun withStatus(status: LocationStatus, limit: Int): List<AddressLocation> = emptyList()

    override fun update(key: String, update: LocationUpdate): Boolean = false

    override fun references(limit: Int, after: String?): List<AddressReference> = emptyList()
}
