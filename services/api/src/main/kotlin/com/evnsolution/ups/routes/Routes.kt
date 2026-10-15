package com.evnsolution.ups.routes

import org.springframework.stereotype.Component

/**
 * A route code (구역코드, ROUTE ID) of one center, with the drivers that use it.
 * A code is unique only inside a center, and several drivers may share it. Provisional.
 */
data class Route(
    val id: String,
    val centerId: String,
    /** ROUTE ID. A string, because of leading zeros and letters. */
    val code: String,
    val driverIds: List<String> = emptyList(),
)

/** Where route codes come from. No source is connected yet, so the default returns nothing. */
fun interface RouteStore {
    /** Route codes of one center, or of every center the account can use when [centerId] is null. */
    fun findAll(centerId: String?): List<Route>
}

@Component
class EmptyRouteStore : RouteStore {
    override fun findAll(centerId: String?): List<Route> = emptyList()
}
