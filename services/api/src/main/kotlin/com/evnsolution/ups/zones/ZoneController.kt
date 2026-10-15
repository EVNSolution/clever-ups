package com.evnsolution.ups.zones

import com.evnsolution.ups.accounts.Role
import com.evnsolution.ups.accounts.SESSION_ATTRIBUTE
import com.evnsolution.ups.accounts.SessionInfo
import com.evnsolution.ups.boundaries.BoundaryReader
import com.evnsolution.ups.centers.CenterStore
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import javax.sql.DataSource

@Configuration
@ConditionalOnProperty("ups.db.url")
class ZoneConfig {
    @Bean
    fun zoneShapes(
        dataSource: DataSource,
        boundaries: BoundaryReader,
        clock: Clock,
        transactions: TransactionTemplate,
    ) = ZoneShapes(JdbcClient.create(dataSource), boundaries, clock, transactions)
}

/**
 * Zones (권역) and 세부권역 of a center with their shapes. Any member of the center reads; an administrator or the
 * owner makes zones and changes shapes. A zone of a center the person does not have answers 404.
 */
@RestController
@RequestMapping("/api/zones")
class ZoneController(private val shapes: ObjectProvider<ZoneShapes>, private val centers: CenterStore) {
    @GetMapping
    fun list(@RequestParam centerId: String): List<ZoneOut> = service(mine(centerId)).list(centerId)

    @PostMapping
    fun create(@RequestBody body: ZoneIn, request: HttpServletRequest): ZoneOut {
        admin(request)
        return rules { service(mine(body.centerId)).create(body) }
    }

    @PutMapping("/{id}/shape")
    fun reshape(@PathVariable id: String, @RequestBody body: ShapeIn, request: HttpServletRequest): ZoneOut {
        val account = admin(request)
        val service = service(true)
        val center = service.centerOf(id)
        if (center == null || !mine(center)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "no zone")
        return rules { service.reshape(id, account, body) }
    }

    /** Sets the zone's 담당 기사. Administrator or owner, for a zone of the person's centers. */
    @PutMapping("/{id}/driver")
    fun assignDriver(@PathVariable id: String, @RequestBody body: ZoneDriverIn, request: HttpServletRequest): ZoneOut {
        admin(request)
        val service = service(true)
        val center = service.centerOf(id)
        if (center == null || !mine(center)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "no zone")
        rules { service.assignDriver(id, body.driverId?.trim()?.ifEmpty { null }) }
        return service.list(center).single { it.id == id }
    }

    private fun <T> rules(action: () -> T): T =
        try {
            action()
        } catch (e: ZoneInvalid) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        } catch (e: ZoneConflict) {
            throw ResponseStatusException(HttpStatus.CONFLICT, e.message, e)
        }

    private fun mine(centerId: String): Boolean = centers.findAll().any { it.id == centerId }

    private fun service(allowed: Boolean): ZoneShapes {
        if (!allowed) throw ResponseStatusException(HttpStatus.FORBIDDEN, "center")
        return shapes.getIfAvailable()
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "zones need the database")
    }

    private fun admin(request: HttpServletRequest): String {
        val account = (request.getAttribute(SESSION_ATTRIBUTE) as? SessionInfo)?.account
        if (account?.role != Role.OWNER && account?.role != Role.ADMIN) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "admin only")
        }
        return account.id
    }
}
