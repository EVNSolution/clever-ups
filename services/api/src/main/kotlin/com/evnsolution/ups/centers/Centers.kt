package com.evnsolution.ups.centers

import org.springframework.stereotype.Component

/**
 * A UPS delivery center (영업소). The ID is the LOCATION NAME code that goes into reports as `location`,
 * such as KRHWN. Provisional.
 */
data class Center(
    val id: String,
    val name: String,
    val address: String? = null,
    /** SLIC CODE. A string, because it can start with 0. */
    val slicCode: String? = null,
    /** Where the depot is, placed from [address]. Null until a geocoder finds it. */
    val lng: Double? = null,
    val lat: Double? = null,
)

/** The centers the signed-in account can use. One account can use several, whatever its role. */
fun interface CenterStore {
    fun findAll(): List<Center>
}

@Component
class EmptyCenterStore : CenterStore {
    override fun findAll(): List<Center> = emptyList()
}
