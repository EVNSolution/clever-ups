package com.evnsolution.ups.drivers

import org.springframework.stereotype.Component
import java.time.Instant

/**
 * A delivery driver (배송원). The ID is the DRIVER ID code and is shown as the driver's name.
 * Its format differs by center, so it must not be parsed. Provisional.
 */
data class Driver(
    val id: String,
    val centerId: String,
    /** ROUTE ID of the driver. Null until one is assigned. Several drivers may share one code. */
    val routeCode: String? = null,
    val lng: Double? = null,
    val lat: Double? = null,
    /** When the position was recorded, not when it was fetched. */
    val positionAt: Instant? = null,
    /** The zones the driver serves, the main zone first. Empty until zones are assigned. */
    val zones: List<String> = emptyList(),
)

/** Where drivers come from. No source is connected yet, so the default returns nothing. */
fun interface DriverStore {
    /** Drivers of one center, or of every center the account can use when [centerId] is null. */
    fun findAll(centerId: String?): List<Driver>
}

@Component
class EmptyDriverStore : DriverStore {
    override fun findAll(centerId: String?): List<Driver> = emptyList()
}
