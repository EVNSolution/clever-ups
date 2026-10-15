package com.evnsolution.ups.dispatch

import com.evnsolution.ups.accounts.Role
import com.evnsolution.ups.accounts.SESSION_ATTRIBUTE
import com.evnsolution.ups.accounts.SessionInfo
import com.evnsolution.ups.centers.CenterStore
import com.evnsolution.ups.centers.DepotPlaces
import com.evnsolution.ups.sequence.StopSequencer
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.format.annotation.DateTimeFormat
import com.evnsolution.ups.driverapp.DeliveryResults
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import javax.sql.DataSource

/** Saving dispatches needs the database. Without it the endpoints answer 503. */
@Configuration
@ConditionalOnProperty("ups.db.url")
class DispatchConfig {
    @Bean
    fun dispatchService(
        dataSource: DataSource,
        transactions: TransactionTemplate,
        sequencers: ObjectProvider<StopSequencer>,
        depots: DepotPlaces,
    ) = DispatchService(JdbcClient.create(dataSource), transactions, sequencers.getIfAvailable(), depots)
}

/**
 * Saved dispatches of a center and day. An administrator or the owner saves one; any active member of the center
 * may read the list. A center the person does not have is refused with 403.
 */
@RestController
@RequestMapping("/api/dispatches")
class DispatchController(
    private val services: ObjectProvider<DispatchService>,
    private val centers: CenterStore,
    private val results: ObjectProvider<DeliveryResults>,
) {
    @PostMapping
    fun save(@RequestBody body: DispatchIn, request: HttpServletRequest): DispatchSummary {
        val account = (request.getAttribute(SESSION_ATTRIBUTE) as? SessionInfo)?.account
        if (account?.role != Role.OWNER && account?.role != Role.ADMIN) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "admin only")
        }
        return service(body.centerId).save(account.id, body)
    }

    @GetMapping
    fun list(
        @RequestParam("centerId") centerId: String,
        @RequestParam("day") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) day: LocalDate,
    ): List<DispatchSummary> = service(centerId).reader.list(centerId, day)

    /** Makes the run the one drivers get for its center and day. Administrator or owner; a DRAFT only, else 409. */
    @PostMapping("/{id}/send")
    fun send(@PathVariable id: String, request: HttpServletRequest): DispatchOut =
        act(id, request) { it.states.send(id) }

    /** Withdraws a DRAFT or SENT run. Administrator or owner; any other state answers 409. */
    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: String, request: HttpServletRequest): DispatchOut =
        act(id, request) { it.states.cancel(id) }

    /** A draft's route goes to another driver of the center. Administrator or owner. */
    @PostMapping("/{id}/routes/{driverId}/driver")
    fun changeDriver(
        @PathVariable id: String,
        @PathVariable driverId: String,
        @RequestBody body: DriverChange,
        request: HttpServletRequest,
    ): DispatchOut = act(id, request) { it.edits.changeDriver(id, driverId, body.driverId) }

    /** Unassigned orders of a draft join a route, which is ordered again by the route engine. */
    @PostMapping("/{id}/routes/{driverId}/orders")
    fun addOrders(
        @PathVariable id: String,
        @PathVariable driverId: String,
        @RequestBody body: OrdersAdd,
        request: HttpServletRequest,
    ): DispatchOut = act(id, request) { it.edits.addOrders(id, driverId, body.orderIds) }

    private fun act(id: String, request: HttpServletRequest, change: (DispatchService) -> Unit): DispatchOut {
        val role = (request.getAttribute(SESSION_ATTRIBUTE) as? SessionInfo)?.account?.role
        if (role != Role.OWNER && role != Role.ADMIN) throw ResponseStatusException(HttpStatus.FORBIDDEN, "admin only")
        get(id)
        change(services.getObject())
        return get(id)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): DispatchOut {
        val service = services.getIfAvailable()
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "dispatches need the database")
        // An unknown ID and a center the person does not have answer the same 404, so neither tells the other apart.
        val center = service.reader.centerOf(id)
        val found = if (center == null || centers.findAll().none { it.id == center }) null else service.reader.get(id)
        val run = found ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no dispatch")
        val done = results.getIfAvailable()?.of(id).orEmpty()
        if (done.isEmpty()) return run
        val routes = run.routes.map { route ->
            route.copy(stops = route.stops.map { it.copy(result = done[it.orderId]) })
        }
        return run.copy(routes = routes)
    }

    /** A photo a driver took at a stop of the run, for the person checking the delivery. */
    @GetMapping("/{id}/photos/{photoId}")
    fun photo(@PathVariable id: String, @PathVariable photoId: String): ResponseEntity<ByteArray> {
        get(id)
        val photo = results.getIfAvailable()?.photo(id, photoId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no photo")
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.contentType)).body(photo.bytes)
    }

    private fun service(centerId: String): DispatchService {
        if (centers.findAll().none { it.id == centerId }) throw ResponseStatusException(HttpStatus.FORBIDDEN, "center")
        return services.getIfAvailable()
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "dispatches need the database")
    }
}
