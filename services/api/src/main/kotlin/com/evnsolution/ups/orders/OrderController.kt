package com.evnsolution.ups.orders

import com.evnsolution.ups.accounts.Role
import com.evnsolution.ups.accounts.SESSION_ATTRIBUTE
import com.evnsolution.ups.accounts.SessionInfo
import com.evnsolution.ups.addresses.AddressStore
import com.evnsolution.ups.centers.CenterStore
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.LocalDate

@RestController
@RequestMapping("/api/orders")
class OrderController(
    private val store: OrderStore,
    private val addresses: AddressStore,
    private val jobCenters: JobCenterStore,
    private val tasks: TaskStore,
    private val edits: OrderEditStore,
    private val centers: CenterStore,
    private val clock: Clock,
    /** The one center every order is given while the shared feed is not split. Blank means "not set". */
    @param:Value("\${ups.default-center:}") private val defaultCenter: String,
) {
    @GetMapping
    fun list(
        @RequestParam("from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam("to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam("centerId", required = false) centerId: String?,
    ): ResponseEntity<List<Order>> {
        val first = from ?: LocalDate.now(clock)
        val last = to ?: first
        if (last.isBefore(first)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "to is before from")
        val fallback = defaultCenter.trim().uppercase().ifEmpty { null }
        val wanted = centerId?.trim()?.uppercase()?.ifEmpty { null }
        val jobs = if (wanted == null) null else jobsOf(first, last, wanted, fallback)
        // A center that holds no Job of the day has nothing to read: the shared feed is not touched.
        val result =
            if (jobs != null && jobs.isEmpty()) OrderResult(emptyList()) else store.find(OrderQuery(first, last, jobs))
        val response = ResponseEntity.ok()
        if (result.incomplete) response.header(INCOMPLETE_HEADER, "true")
        // One row per tracking number: the same file uploaded twice is two Jobs with the same deliveries.
        val unique = result.orders.distinctBy { it.trackingNumber }
        val assigned = jobCenters.centers(unique.mapNotNull { it.jobId }.toSet())
        val located = addresses.locate(unique).map { it.copy(centerCode = assigned[it.jobId] ?: fallback) }
        tasks.record(located)
        val shown = edits.apply(located)
        return response.body(if (wanted == null) shown else shown.filter { it.centerCode == wanted })
    }

    /**
     * Saves the cells a person changed in the order table of one day. Ours24 is not written. Administrator or owner,
     * and every order must be of a center the person has: an order never listed or of another center answers 404.
     */
    @PutMapping("/edits")
    fun saveEdits(@RequestBody body: OrderEditsIn, request: HttpServletRequest): ResponseEntity<Unit> {
        val account = (request.getAttribute(SESSION_ATTRIBUTE) as? SessionInfo)?.account
        val status = when {
            account?.role != Role.OWNER && account?.role != Role.ADMIN -> HttpStatus.FORBIDDEN
            edits is EmptyOrderEditStore -> HttpStatus.SERVICE_UNAVAILABLE
            body.day == null || !valid(body.edits) -> HttpStatus.BAD_REQUEST
            !allMine(body.edits.map { it.orderId }.toSet()) -> HttpStatus.NOT_FOUND
            else -> null
        }
        if (status != null || account == null || body.day == null) {
            throw ResponseStatusException(status ?: HttpStatus.BAD_REQUEST)
        }
        edits.save(account.id, body.day, body.edits)
        return ResponseEntity.noContent().build()
    }

    private fun valid(changes: List<OrderEditIn>): Boolean =
        changes.size in 1..MAX_EDITS &&
            changes.all { it.field in EDITABLE_FIELDS && (it.value?.trim()?.length ?: 0) <= MAX_VALUE }

    /** Every order was listed before and belongs to a center the person has. */
    private fun allMine(ids: Set<String>): Boolean {
        val jobs = edits.jobsOf(ids)
        val fallback = defaultCenter.trim().uppercase().ifEmpty { null }
        val centerOf = jobCenters.centers(jobs.values.toSet())
        val mine = centers.findAll().map { it.id }.toSet()
        return ids.all { id -> jobs[id]?.let { centerOf[it] ?: fallback } in mine }
    }

    /**
     * The Jobs of the range that belong to [wanted]: assigned to it, or assigned to nobody when it is the default
     * center. Null when the source cannot list Jobs, and then every Job is read and the orders are filtered.
     */
    private fun jobsOf(first: LocalDate, last: LocalDate, wanted: String, fallback: String?): Set<String>? {
        val ids = (store as? JobLister)?.jobIds(first, last).orEmpty()
        val assigned = if (ids.isEmpty()) emptyMap() else jobCenters.centers(ids)
        return if (ids.isEmpty()) null else ids.filter { (assigned[it] ?: fallback) == wanted }.toSet()
    }

    private companion object {
        const val INCOMPLETE_HEADER = "X-Data-Incomplete"
        const val MAX_EDITS = 5000
        const val MAX_VALUE = 200
    }
}
