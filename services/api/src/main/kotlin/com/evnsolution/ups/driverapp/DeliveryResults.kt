package com.evnsolution.ups.driverapp

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID

/** What the driver reports when leaving a stop. */
data class ReportIn(
    /** DELIVERED or EXCEPTION. */
    val outcome: String = "",
    /** An exception code (for EXCEPTION); DELIVERED is always KB. */
    val code: String? = null,
    /** C (commercial) or R (residential). */
    val density: String? = null,
    /** FACE (the recipient's name typed) or DOOR (a photo at the door), for DELIVERED. */
    val podMethod: String? = null,
    val recipientName: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
)

/** What happened at a stop, as the web and the app read it. */
data class ResultOut(
    val arrivedAt: OffsetDateTime?,
    val completedAt: OffsetDateTime?,
    val outcome: String?,
    val code: String?,
    val density: String?,
    val podMethod: String?,
    val recipientName: String?,
    val photoIds: List<String>,
)

/** A stored photo. */
class Photo(val contentType: String, val bytes: ByteArray)

/**
 * Keeps what drivers report at their stops. A driver reports only on a stop of the driver's own route in a SENT run.
 * Photos are files under [photos] named by their ID; without that folder, photos are refused (503).
 */
class DeliveryResults(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val photos: Path?,
) {
    fun arrive(driverId: String, planId: String, orderId: String, lat: Double?, lng: Double?) =
        onStop(driverId, planId, orderId) {
            jdbc.sql(
                "UPDATE delivery_result SET arrived_at = COALESCE(arrived_at, CURRENT_TIMESTAMP), " +
                    "lat = COALESCE(:lat, lat), lng = COALESCE(:lng, lng) WHERE plan_id = :plan AND task_key = :key",
            ).param("lat", lat).param("lng", lng).param("plan", planId).param("key", orderId).update()
        }

    fun report(driverId: String, planId: String, orderId: String, report: ReportIn) {
        val problem = problem(report)
        if (problem != null) throw ResponseStatusException(HttpStatus.BAD_REQUEST, problem)
        val delivered = report.outcome == "DELIVERED"
        onStop(driverId, planId, orderId) {
            jdbc.sql(
                "UPDATE delivery_result SET arrived_at = COALESCE(arrived_at, CURRENT_TIMESTAMP), " +
                    "completed_at = CURRENT_TIMESTAMP, outcome = :outcome, code = :code, density = :density, " +
                    "pod_method = :pod, recipient_name = :name, lat = COALESCE(:lat, lat), lng = COALESCE(:lng, lng) " +
                    "WHERE plan_id = :plan AND task_key = :key",
            ).param("outcome", report.outcome).param("code", if (delivered) UpsCodes.DELIVERED else report.code)
                .param("density", report.density).param("pod", if (delivered) report.podMethod else null)
                .param("name", if (delivered && report.podMethod == "FACE") report.recipientName?.trim() else null)
                .param("lat", report.lat).param("lng", report.lng).param("plan", planId).param("key", orderId)
                .update()
        }
    }

    fun addPhoto(driverId: String, planId: String, orderId: String, contentType: String, bytes: ByteArray): String {
        val folder = photos ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "no photo folder set up")
        if (contentType !in IMAGE_TYPES || bytes.isEmpty() || bytes.size > MAX_PHOTO_BYTES) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "a JPEG or PNG photo up to 5 MB")
        }
        val id = UUID.randomUUID().toString()
        onStop(driverId, planId, orderId) {
            Files.createDirectories(folder)
            Files.write(folder.resolve(id), bytes)
            jdbc.sql(
                "INSERT INTO delivery_photo (photo_id, plan_id, task_key, content_type, size_bytes) " +
                    "VALUES (:id, :plan, :key, :type, :size)",
            ).param("id", id).param("plan", planId).param("key", orderId).param("type", contentType)
                .param("size", bytes.size).update()
        }
        return id
    }

    /** What happened at each stop of a run, by order ID. Stops with nothing reported are left out. */
    fun of(planId: String): Map<String, ResultOut> {
        val photoIds = jdbc.sql("SELECT task_key, photo_id FROM delivery_photo WHERE plan_id = :plan ORDER BY taken_at")
            .param("plan", planId).query { rs, _ -> rs.getString(1) to rs.getString(2).trim() }.list()
            .groupBy({ it.first }, { it.second })
        return jdbc.sql("SELECT * FROM delivery_result WHERE plan_id = :plan").param("plan", planId).query { rs, _ ->
            rs.getString("task_key") to ResultOut(
                arrivedAt = rs.getObject("arrived_at", OffsetDateTime::class.java),
                completedAt = rs.getObject("completed_at", OffsetDateTime::class.java),
                outcome = rs.getString("outcome"),
                code = rs.getString("code"),
                density = rs.getString("density"),
                podMethod = rs.getString("pod_method"),
                recipientName = rs.getString("recipient_name"),
                photoIds = photoIds[rs.getString("task_key")].orEmpty(),
            )
        }.list().toMap()
    }

    /** A photo of a run, or null. */
    fun photo(planId: String, photoId: String): Photo? {
        val type = jdbc.sql("SELECT content_type FROM delivery_photo WHERE plan_id = :plan AND photo_id = :id")
            .param("plan", planId).param("id", photoId).query(String::class.java).optional().orElse(null)
        val file = photos?.resolve(photoId)
        return if (type == null || file == null || !Files.exists(file)) null else Photo(type, Files.readAllBytes(file))
    }

    private fun problem(report: ReportIn): String? = when {
        report.outcome !in setOf("DELIVERED", "EXCEPTION") -> "outcome must be DELIVERED or EXCEPTION"
        report.density != null && report.density !in setOf("C", "R") -> "density must be C or R"
        report.outcome == "EXCEPTION" && (report.code == null || !UpsCodes.isException(report.code)) ->
            "an exception needs a UPS exception code"
        report.outcome == "DELIVERED" && report.podMethod !in setOf("FACE", "DOOR") -> "a delivery is FACE or DOOR"
        report.outcome == "DELIVERED" && report.podMethod == "FACE" && report.recipientName.isNullOrBlank() ->
            "a face-to-face delivery needs the recipient's name"
        else -> null
    }

    /**
     * Runs [change] on the stop's result row, made on first touch. The stop must be on the driver's own route of a
     * SENT run, else 404, so one driver cannot report on another's stops.
     */
    private fun onStop(driverId: String, planId: String, orderId: String, change: () -> Unit) {
        transactions.executeWithoutResult {
            val mine = jdbc.sql(
                "SELECT COUNT(*) FROM dispatch_stop s JOIN dispatch_route r ON r.route_id = s.route_id " +
                    "JOIN dispatch_plan p ON p.plan_id = s.plan_id WHERE s.plan_id = :plan AND s.task_key = :key " +
                    "AND r.driver_id = :driver AND p.status = 'SENT'",
            ).param("plan", planId).param("key", orderId).param("driver", driverId).query(Int::class.java).single()
            if (mine == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND, "not a stop of your sent route")
            val exists = jdbc.sql("SELECT COUNT(*) FROM delivery_result WHERE plan_id = :plan AND task_key = :key")
                .param("plan", planId).param("key", orderId).query(Int::class.java).single()
            if (exists == 0) {
                jdbc.sql("INSERT INTO delivery_result (plan_id, task_key, driver_id) VALUES (:plan, :key, :driver)")
                    .param("plan", planId).param("key", orderId).param("driver", driverId).update()
            }
            change()
        }
    }

    private companion object {
        val IMAGE_TYPES = setOf("image/jpeg", "image/png")
        const val MAX_PHOTO_BYTES = 5 * 1024 * 1024
    }
}
