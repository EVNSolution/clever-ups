package com.evnsolution.ups.boundaries

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import javax.sql.DataSource

/** One boundary as the map draws it. [shape] is a GeoJSON geometry. */
data class BoundaryOut(
    val code: String,
    val level: String,
    val name: String,
    val parentCode: String?,
    val shape: JsonNode,
)

/** Reads boundaries in a box. SI is what a person calls a 시: a CITY, or an SGG that is not a 일반구. */
class BoundaryReader(private val jdbc: JdbcClient) {
    private val mapper = JsonMapper.builder().build()

    fun inBox(level: String, box: DoubleArray, limit: Int): List<BoundaryOut> {
        val which =
            if (level == "SI") "(level = 'CITY' OR (level = 'SGG' AND parent_code IS NULL))" else "level = 'DONG'"
        return jdbc.sql(
            "SELECT code, level, name, parent_code, geometry FROM admin_boundary WHERE $which " +
                "AND max_lng >= :w AND min_lng <= :e AND max_lat >= :s AND min_lat <= :n ORDER BY code LIMIT :limit",
        )
            .param("w", box[WEST]).param("s", box[SOUTH]).param("e", box[EAST]).param("n", box[NORTH])
            .param("limit", limit + 1)
            .query { rs, _ ->
                BoundaryOut(
                    rs.getString("code"),
                    rs.getString("level"),
                    rs.getString("name"),
                    rs.getString("parent_code"),
                    mapper.readTree(rs.getString("geometry")),
                )
            }.list()
    }

    /** The shapes of these codes, for making a zone. Unknown codes are left out. */
    fun shapes(codes: Collection<String>): Map<String, String> =
        if (codes.isEmpty()) {
            emptyMap()
        } else {
            jdbc.sql("SELECT code, geometry FROM admin_boundary WHERE code IN (:codes)")
                .param("codes", codes.distinct())
                .query { rs, _ -> rs.getString("code") to rs.getString("geometry") }.list().toMap()
        }
}

private const val WEST = 0
private const val SOUTH = 1
private const val EAST = 2
private const val NORTH = 3

@Configuration
@ConditionalOnProperty("ups.db.url")
class BoundaryConfig {
    @Bean
    fun boundaryReader(dataSource: DataSource) = BoundaryReader(JdbcClient.create(dataSource))
}

/**
 * Public administrative boundaries in a box ([west, south, east, north]) for any signed-in member: level SI for
 * 시 (권역 editing) or DONG for 행정동 (세부권역 editing). More than [MAX] answers sets X-Data-Incomplete.
 */
@RestController
@RequestMapping("/api/boundaries")
class BoundaryController(private val readers: ObjectProvider<BoundaryReader>) {
    @GetMapping
    fun list(@RequestParam level: String, @RequestParam bbox: String): ResponseEntity<List<BoundaryOut>> {
        val box = bbox.split(',').mapNotNull { it.trim().toDoubleOrNull() }.toDoubleArray()
        val valid = level in setOf("SI", "DONG") && box.size == BOX_SIZE &&
            box[WEST] <= box[EAST] && box[SOUTH] <= box[NORTH]
        if (!valid) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "level SI or DONG, bbox west,south,east,north")
        }
        val reader = readers.getIfAvailable()
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "boundaries need the database")
        val found = reader.inBox(level, box, MAX)
        val response = ResponseEntity.ok()
        if (found.size > MAX) response.header("X-Data-Incomplete", "true")
        return response.body(found.take(MAX))
    }

    private companion object {
        const val MAX = 600
        const val BOX_SIZE = 4
    }
}
