package com.evnsolution.ups.centers

import com.evnsolution.ups.accounts.Role
import com.evnsolution.ups.accounts.SESSION_ATTRIBUTE
import com.evnsolution.ups.accounts.SessionInfo
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import javax.sql.DataSource

/** A center to make or change. [id] is the LOCATION NAME code, such as KRSNA. */
data class CenterIn(val id: String = "", val name: String = "", val depotAddress: String = "")

/** A driver to register: the DRIVER ID code of a center. No name or phone number is kept. */
data class DriverIn(val centerId: String = "", val driverId: String = "")

/** Writes the master tables people manage on the web: centers (owner only) and drivers. */
class MasterAdmin(private val jdbc: JdbcClient) {
    /** False when the code is taken. */
    fun createCenter(input: CenterIn): Boolean =
        try {
            jdbc.sql("INSERT INTO center (center_code, name_ko, depot_address_ko) VALUES (:c, :n, :a)")
                .param("c", input.id).param("n", input.name).param("a", input.depotAddress).update()
            true
        } catch (_: DuplicateKeyException) {
            false
        }

    /** False when there is no such center. */
    fun updateCenter(input: CenterIn): Boolean =
        jdbc.sql(
            "UPDATE center SET name_ko = :n, depot_address_ko = :a, updated_at = CURRENT_TIMESTAMP " +
                "WHERE center_code = :c",
        ).param("c", input.id).param("n", input.name).param("a", input.depotAddress).update() == 1

    /** False when the center already has the code. */
    fun createDriver(input: DriverIn): Boolean =
        try {
            jdbc.sql("INSERT INTO driver (driver_id, center_code, driver_code) VALUES (:id, :c, :d)")
                .param("id", UUID.randomUUID().toString()).param("c", input.centerId).param("d", input.driverId)
                .update()
            true
        } catch (_: DuplicateKeyException) {
            false
        }
}

@Configuration
@ConditionalOnProperty("ups.db.url")
class MasterAdminConfig {
    @Bean
    fun masterAdmin(dataSource: DataSource) = MasterAdmin(JdbcClient.create(dataSource))
}

/**
 * Centers are made and named by the owner, who then gives them to people on approval. Drivers are registered by
 * an administrator or the owner, in a center the person has.
 */
@RestController
@RequestMapping("/api")
class MasterAdminController(private val admins: ObjectProvider<MasterAdmin>, private val centers: CenterStore) {
    @PostMapping("/centers")
    fun createCenter(@RequestBody body: CenterIn, request: HttpServletRequest): Center {
        val input = checked(body, request)
        if (!admin().createCenter(input)) throw ResponseStatusException(HttpStatus.CONFLICT, "the code is taken")
        return found(input.id)
    }

    @PutMapping("/centers/{id}")
    fun updateCenter(@PathVariable id: String, @RequestBody body: CenterIn, request: HttpServletRequest): Center {
        val input = checked(body.copy(id = id), request)
        if (!admin().updateCenter(input)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "no center")
        return found(input.id)
    }

    @PostMapping("/drivers")
    fun createDriver(@RequestBody body: DriverIn, request: HttpServletRequest): DriverIn {
        val role = (request.getAttribute(SESSION_ATTRIBUTE) as? SessionInfo)?.account?.role
        val input = DriverIn(body.centerId.trim().uppercase(), body.driverId.trim())
        val problem = when {
            role != Role.OWNER && role != Role.ADMIN -> HttpStatus.FORBIDDEN
            centers.findAll().none { it.id == input.centerId } -> HttpStatus.FORBIDDEN
            !DRIVER_CODE.matches(input.driverId) -> HttpStatus.BAD_REQUEST
            else -> null
        }
        if (problem != null) throw ResponseStatusException(problem, "a driver ID of 1..30 letters, digits, - or _")
        if (!admin().createDriver(input)) throw ResponseStatusException(HttpStatus.CONFLICT, "the center has it")
        return input
    }

    private fun checked(body: CenterIn, request: HttpServletRequest): CenterIn {
        val role = (request.getAttribute(SESSION_ATTRIBUTE) as? SessionInfo)?.account?.role
        if (role != Role.OWNER) throw ResponseStatusException(HttpStatus.FORBIDDEN, "owner only")
        val input = CenterIn(body.id.trim().uppercase(), body.name.trim(), body.depotAddress.trim())
        val valid = CENTER_CODE.matches(input.id) && input.name.length in 1..MAX_TEXT &&
            input.depotAddress.length <= MAX_TEXT
        if (!valid) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "code of 3..12 letters or digits, a name")
        return input
    }

    private fun found(id: String): Center =
        centers.findAll().firstOrNull { it.id == id } ?: Center(id, id)

    private fun admin(): MasterAdmin =
        admins.getIfAvailable() ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "needs the database")

    private companion object {
        const val MAX_TEXT = 200
        val CENTER_CODE = Regex("[A-Z0-9]{3,12}")
        val DRIVER_CODE = Regex("[A-Za-z0-9_-]{1,30}")
    }
}
