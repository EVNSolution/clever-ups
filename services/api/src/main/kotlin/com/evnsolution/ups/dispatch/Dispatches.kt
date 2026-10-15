package com.evnsolution.ups.dispatch

import java.time.LocalDate
import java.time.OffsetDateTime

/** One driver and the orders given to the driver. [driverId] is the driver code, as the API shows it. */
data class RouteIn(val driverId: String = "", val orderIds: List<String> = emptyList())

/**
 * A dispatch to save: the center, the operating day, when drivers leave (HH:mm, Korea), each driver's orders and
 * the orders no driver got. The server puts each route in order with the route engine and keeps the result.
 */
data class DispatchIn(
    val centerId: String = "",
    val day: LocalDate? = null,
    val departAt: String = "",
    val routes: List<RouteIn> = emptyList(),
    val unassigned: List<String> = emptyList(),
)

/** A saved dispatch, as the list of runs shows it. */
data class DispatchSummary(
    val id: String,
    val centerId: String,
    val day: LocalDate,
    val method: String,
    val status: String,
    val departAt: OffsetDateTime,
    val createdAt: OffsetDateTime,
    val routes: Int,
    val stops: Int,
    val unassigned: Int,
)

/** A stop of a saved route: the order, its number, when the driver should arrive, and the road distance so far. */
data class StopOut(
    val orderId: String,
    val seq: Int,
    val arrival: OffsetDateTime,
    val distanceMeters: Int,
    /** What the driver reported at this stop, once the run is sent. */
    val result: com.evnsolution.ups.driverapp.ResultOut? = null,
)

/** A start position, as the map gives it. */
data class PlaceOut(val lng: Double, val lat: Double)

/** A saved route of one driver, as it was computed. [driverId] is the driver code. */
data class RouteOut(
    val driverId: String,
    val routeCode: String?,
    val status: String,
    val departAt: OffsetDateTime,
    val start: PlaceOut?,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val geometry: String?,
    val stops: List<StopOut>,
)

/** One saved dispatch with its routes and the orders no driver got. Orders are given by ID only. */
data class DispatchOut(val summary: DispatchSummary, val routes: List<RouteOut>, val unassigned: List<String>)

/** The driver a route goes to, by driver code. */
data class DriverChange(val driverId: String = "")

/** Unassigned orders of the run to add to a route. */
data class OrdersAdd(val orderIds: List<String> = emptyList())
