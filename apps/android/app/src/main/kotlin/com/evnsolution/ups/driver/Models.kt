package com.evnsolution.ups.driver

import org.json.JSONArray
import org.json.JSONObject

/** What the driver reported at a stop (contract: DeliveryResult). */
data class StopResult(
    val arrivedAt: String?,
    val completedAt: String?,
    val outcome: String?,
    val code: String?,
    val podMethod: String?,
    val recipientName: String?,
    val photoCount: Int,
)

/** One stop of the route (contract: DriverRoute.stops). No customer name or phone number comes to the phone. */
data class DriverStop(
    val orderId: String,
    val seq: Int,
    val arrival: String,
    val distanceMeters: Int,
    val trackingNumber: String,
    val addressKo: String?,
    val detailKo: String?,
    val address1: String,
    val address2: String,
    val postalCode: String,
    val lng: Double?,
    val lat: Double?,
    val result: StopResult?,
) {
    /** The address to read: the Korean one when it exists, else the address as the sender wrote it. */
    val title: String get() = addressKo?.let { ko -> listOfNotNull(ko, detailKo).joinToString(" ") } ?: original
    val original: String get() = listOf(address1, address2).filter { it.isNotBlank() }.joinToString(" ")
}

/** The driver's route of a day (contract: DriverRoute). */
data class DriverRoute(
    val planId: String,
    val day: String,
    val driverId: String,
    val routeCode: String?,
    val departAt: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val stops: List<DriverStop>,
)

/** A UPS delivery code (contract: UpsCode). */
data class UpsCode(val code: String, val category: String, val short: String, val korean: String?) {
    val label: String get() = "$code · ${korean ?: short}"
}

/** Reads the API's JSON into the models. A missing required field throws, so a broken contract shows at once. */
object Json {
    /** The fields the parser reads; DriverRouteContractTest checks they cover the contract's required fields. */
    val ROUTE_FIELDS = setOf(
        "planId", "day", "driverId", "routeCode", "departAt", "start", "distanceMeters", "durationSeconds",
        "geometry", "stops",
    )
    val STOP_FIELDS = setOf(
        "orderId", "seq", "arrival", "distanceMeters", "trackingNumber", "addressKo", "detailKo", "address1",
        "address2", "postalCode", "lng", "lat", "result",
    )

    fun route(json: JSONObject) = DriverRoute(
        planId = json.getString("planId"),
        day = json.getString("day"),
        driverId = json.getString("driverId"),
        routeCode = json.text("routeCode"),
        departAt = json.getString("departAt"),
        distanceMeters = json.getInt("distanceMeters"),
        durationSeconds = json.getInt("durationSeconds"),
        stops = json.getJSONArray("stops").objects().map(::stop),
    )

    fun codes(array: JSONArray): List<UpsCode> = array.objects().map {
        UpsCode(it.getString("code"), it.getString("category"), it.getString("short"), it.text("korean"))
    }

    private fun stop(json: JSONObject) = DriverStop(
        orderId = json.getString("orderId"),
        seq = json.getInt("seq"),
        arrival = json.getString("arrival"),
        distanceMeters = json.getInt("distanceMeters"),
        trackingNumber = json.getString("trackingNumber"),
        addressKo = json.text("addressKo"),
        detailKo = json.text("detailKo"),
        address1 = json.getString("address1"),
        address2 = json.getString("address2"),
        postalCode = json.getString("postalCode"),
        lng = json.number("lng"),
        lat = json.number("lat"),
        result = json.optJSONObject("result")?.let(::result),
    )

    private fun result(json: JSONObject) = StopResult(
        arrivedAt = json.text("arrivedAt"),
        completedAt = json.text("completedAt"),
        outcome = json.text("outcome"),
        code = json.text("code"),
        podMethod = json.text("podMethod"),
        recipientName = json.text("recipientName"),
        photoCount = json.optJSONArray("photoIds")?.length() ?: 0,
    )

    private fun JSONObject.text(name: String): String? = if (isNull(name)) null else getString(name)

    private fun JSONObject.number(name: String): Double? = if (isNull(name)) null else getDouble(name)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
