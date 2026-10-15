package com.evnsolution.ups.driverapp

import com.evnsolution.ups.accounts.AccountStore
import com.evnsolution.ups.accounts.Role
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.LocalDate
import java.nio.file.Path
import java.time.ZoneId
import javax.sql.DataSource

@Configuration
@ConditionalOnProperty("ups.db.url")
class DriverAppConfig {
    @Bean
    fun driverAccounts(dataSource: DataSource, transactions: TransactionTemplate) =
        DriverAccounts(JdbcClient.create(dataSource), transactions)

    @Bean
    fun driverRoutes(dataSource: DataSource) = DriverRoutes(JdbcClient.create(dataSource))

    @Bean
    fun driverPositions(dataSource: DataSource) = DriverPositions(JdbcClient.create(dataSource))

    /** Photos go to `ups.photos.dir` (UPS_PHOTOS_DIR); without it, photos are refused. */
    @Bean
    fun deliveryResults(
        dataSource: DataSource,
        transactions: TransactionTemplate,
        @Value("\${ups.photos.dir:}") photos: String,
    ) = DeliveryResults(JdbcClient.create(dataSource), transactions, photos.ifBlank { null }?.let { Path.of(it) })
}

/** Where the driver is when arriving, if the phone knows. */
data class ArriveIn(val lat: Double? = null, val lng: Double? = null)

/** The ID of a stored photo. */
data class PhotoOut(val photoId: String)

/** Which driver of which center an account becomes. */
data class DriverLink(val centerId: String = "", val driverId: String = "")

/**
 * The owner ties an account to a driver; the driver reads the route of a day. Both need the database (503 without).
 * The gate lets a driver account reach only the paths under `/api/driver` and `/api/me`.
 */
@RestController
class DriverAppController(
    private val accountsOf: ObjectProvider<DriverAccounts>,
    private val routesOf: ObjectProvider<DriverRoutes>,
    private val resultsOf: ObjectProvider<DeliveryResults>,
    private val positionsOf: ObjectProvider<DriverPositions>,
    private val sessions: AccountStore,
    private val clock: Clock,
) {
    @PostMapping("/api/memberships/{accountId}/driver")
    fun link(
        @PathVariable accountId: String,
        @RequestBody body: DriverLink,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val actor = sessions.current(request) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        accounts().link(actor, accountId, body.centerId, body.driverId)
        return ResponseEntity.noContent().build()
    }

    /** The signed-in driver's route of [day] (today in Korea when left out), from the run sent for the center. */
    @GetMapping("/api/driver/route")
    fun route(
        @RequestParam("day", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) day: LocalDate?,
        request: HttpServletRequest,
    ): DriverRoute {
        val driver = driverOf(request)
        val routes = routesOf.getIfAvailable() ?: throw unavailable()
        val route = routes.of(driver.first, driver.second, day ?: LocalDate.now(clock.withZone(KOREA)))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no route was sent for this day")
        val done = results().of(route.planId)
        return route.copy(stops = route.stops.map { it.copy(result = done[it.orderId]) })
    }

    /** Where the driver's phone is now. Only the latest place is kept; 운행 현황 shows it on the map. */
    @PostMapping("/api/driver/position")
    fun position(@RequestBody body: PositionIn, request: HttpServletRequest): ResponseEntity<Void> {
        val positions = positionsOf.getIfAvailable() ?: throw unavailable()
        try {
            positions.report(driverOf(request).first, body)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
        return ResponseEntity.noContent().build()
    }

    /** The UPS delivery codes: KB and the exceptions a driver may choose. */
    @GetMapping("/api/driver/codes")
    fun codes(): List<UpsCode> = UpsCodes.all

    @PostMapping("/api/driver/route/{planId}/stops/{orderId}/arrive")
    fun arrive(
        @PathVariable planId: String,
        @PathVariable orderId: String,
        @RequestBody body: ArriveIn,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        results().arrive(driverOf(request).first, planId, orderId, body.lat, body.lng)
        return ResponseEntity.noContent().build()
    }

    /** Delivered (KB, face to face or at the door) or an exception code. A later report replaces an earlier one. */
    @PostMapping("/api/driver/route/{planId}/stops/{orderId}/result")
    fun report(
        @PathVariable planId: String,
        @PathVariable orderId: String,
        @RequestBody body: ReportIn,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        results().report(driverOf(request).first, planId, orderId, body)
        return ResponseEntity.noContent().build()
    }

    /** A photo at the stop: the request body is the JPEG or PNG itself, up to 5 MB. */
    @PostMapping("/api/driver/route/{planId}/stops/{orderId}/photos", consumes = ["image/jpeg", "image/png"])
    fun photo(
        @PathVariable planId: String,
        @PathVariable orderId: String,
        @RequestBody bytes: ByteArray,
        request: HttpServletRequest,
    ): PhotoOut = PhotoOut(results().addPhoto(driverOf(request).first, planId, orderId, request.contentType, bytes))

    private fun results(): DeliveryResults = resultsOf.getIfAvailable() ?: throw unavailable()

    /** The driver and center of the signed-in driver account; anyone else is turned away. */
    private fun driverOf(request: HttpServletRequest): Pair<String, String> {
        val account = sessions.current(request)?.takeIf { it.role == Role.DRIVER }
        return account?.let { accounts().driverOf(it.id) }
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "not a driver account tied to a driver")
    }

    private fun accounts(): DriverAccounts = accountsOf.getIfAvailable() ?: throw unavailable()

    private fun unavailable() =
        ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "driver accounts need the database")

    private companion object {
        val KOREA: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
