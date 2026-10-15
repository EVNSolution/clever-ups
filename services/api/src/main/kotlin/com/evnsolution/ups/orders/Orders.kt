package com.evnsolution.ups.orders

import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * One delivery, from a row of an Ours24 Job. It has no position until the address is refined.
 * Its center is the default center for now. Provisional.
 */
data class Order(
    /** Opaque ID made by this API. */
    val id: String,
    val trackingNumber: String,
    val customerName: String? = null,
    val companyName: String? = null,
    val address1: String? = null,
    val address2: String? = null,
    /** A string, because it can start with 0. */
    val postalCode: String? = null,
    /** Row status in Ours24, such as PENDING. */
    val status: String? = null,
    /** The Ours24 Job (upload batch) the row came from. */
    val jobId: String? = null,
    val lng: Double? = null,
    val lat: Double? = null,
    /** The Korean form of the address, once a service has made it. Null until then. */
    val addressKo: String? = null,
    /** The unit or building part of the Korean address, when there is one. */
    val detailKo: String? = null,
    /** The center this order belongs to. The default center until the split of the shared feed is decided. */
    val centerCode: String? = null,
    /** Key of the stored address row this order points at: what a person edits the Korean address by. */
    val addressKey: String? = null,
    /** The fields a person corrected over the Ours24 row. Empty when nothing was corrected. */
    val edited: List<String> = emptyList(),
)

/** Which days to read, both included. No length limit is applied here; the source may refuse a long range. */
data class OrderQuery(val from: LocalDate, val to: LocalDate, val jobIds: Set<String>? = null)

/**
 * A source that can say which Jobs a range holds without reading their rows. With it, a caller that wants one
 * center reads only that center's Jobs instead of every Job of the shared feed.
 */
fun interface JobLister {
    fun jobIds(from: LocalDate, to: LocalDate): List<String>
}

/** [incomplete] is true when the source may hold more orders than were read. */
data class OrderResult(val orders: List<Order>, val incomplete: Boolean = false)

/** Where orders come from. No source is connected by default, so the default returns nothing. */
fun interface OrderStore {
    fun find(query: OrderQuery): OrderResult
}

/** The center each Job was given to, once. A Job with no row has no assigned center. */
fun interface JobCenterStore {
    fun centers(jobIds: Collection<String>): Map<String, String>
}

@Component
class EmptyJobCenterStore : JobCenterStore {
    override fun centers(jobIds: Collection<String>): Map<String, String> = emptyMap()
}

@Component
class EmptyOrderStore : OrderStore {
    override fun find(query: OrderQuery): OrderResult = OrderResult(emptyList())
}
