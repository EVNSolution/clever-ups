package com.evnsolution.ups.zones

import com.evnsolution.ups.boundaries.BoundaryReader
import com.evnsolution.ups.boundaries.GeoJson
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/** A zone as the map draws it. With [parentId] it is a 세부권역. [shape] is a GeoJSON geometry, null until drawn. */
data class ZoneOut(val id: String, val centerId: String, val name: String, val parentId: String?, val shape: JsonNode?)

/** A new zone of a center. With [parentId] it is a 세부권역 of that zone. */
data class ZoneIn(val centerId: String = "", val name: String = "", val parentId: String? = null)

/** The new shape: the union of these boundaries, or a drawn polygon. Neither clears the shape. */
data class ShapeIn(val boundaryCodes: List<String> = emptyList(), val polygon: JsonNode? = null)

/** A request the zone rules refuse: 400. */
class ZoneInvalid(message: String) : RuntimeException(message)

/** A request that clashes with what is stored: 409. */
class ZoneConflict(message: String) : RuntimeException(message)

/**
 * Zones and their shapes. A shape never overlaps another zone of the same center and level: what another zone
 * holds is cut away, so the new edge follows the old one. A 세부권역 is cut to its parent.
 */
class ZoneShapes(
    private val jdbc: JdbcClient,
    private val boundaries: BoundaryReader,
    private val clock: Clock,
) {
    private val mapper = JsonMapper.builder().build()

    private data class Row(
        val id: String,
        val center: String,
        val name: String,
        val parent: String?,
        val shape: String?,
    )

    private fun rows(where: String, value: String): List<Row> =
        jdbc.sql("SELECT zone_id, center_code, name, parent_zone_id, shape FROM zone WHERE $where ORDER BY name")
            .param("v", value)
            .query { rs, _ ->
                Row(
                    rs.getString("zone_id").trim(),
                    rs.getString("center_code"),
                    rs.getString("name"),
                    rs.getString("parent_zone_id")?.trim(),
                    rs.getString("shape"),
                )
            }.list()

    private fun out(row: Row) = ZoneOut(row.id, row.center, row.name, row.parent, row.shape?.let(mapper::readTree))

    fun list(centerId: String): List<ZoneOut> = rows("center_code = :v", centerId).map(::out)

    fun centerOf(zoneId: String): String? = rows("zone_id = :v", zoneId).firstOrNull()?.center

    fun create(input: ZoneIn): ZoneOut {
        val name = input.name.trim()
        val parent = input.parentId?.let { id -> rows("zone_id = :v", id).firstOrNull() }
        val parentFits = parent != null && parent.center == input.centerId && parent.parent == null
        val problem = when {
            name.isEmpty() || name.length > MAX_NAME -> "name must be 1..$MAX_NAME characters"
            input.parentId != null && !parentFits -> "the parent must be a 권역 of the same center"
            else -> null
        }
        if (problem != null) throw ZoneInvalid(problem)
        val id = UUID.randomUUID().toString()
        try {
            jdbc.sql("INSERT INTO zone (zone_id, center_code, name, parent_zone_id) VALUES (:id, :c, :n, :p)")
                .param("id", id).param("c", input.centerId).param("n", name).param("p", parent?.id).update()
        } catch (_: DuplicateKeyException) {
            throw ZoneConflict("the center already has a zone named $name")
        }
        return out(rows("zone_id = :v", id).single())
    }

    fun reshape(zoneId: String, accountId: String, input: ShapeIn): ZoneOut {
        val zone = rows("zone_id = :v", zoneId).single()
        val drawn = drawn(input)
        val shape = drawn?.let { fitted(zone, it) }
        val box = shape?.envelopeInternal
        jdbc.sql(
            "UPDATE zone SET shape = :shape, min_lng = :x0, min_lat = :y0, max_lng = :x1, max_lat = :y1, " +
                "shape_by = :by, shape_at = :at WHERE zone_id = :id",
        )
            .param("shape", shape?.let(GeoJson::write))
            .param("x0", box?.minX).param("y0", box?.minY).param("x1", box?.maxX).param("y1", box?.maxY)
            .param("by", accountId).param("at", OffsetDateTime.now(clock)).param("id", zoneId)
            .update()
        return out(rows("zone_id = :v", zoneId).single())
    }

    private fun drawn(input: ShapeIn): Geometry? =
        when {
            input.boundaryCodes.isNotEmpty() -> joined(input.boundaryCodes)
            // A drawn ring may cross itself; buffer(0) makes it a valid area.
            input.polygon != null -> runCatching { GeoJson.read(input.polygon) }
                .getOrElse { throw ZoneInvalid("polygon is not a GeoJSON polygon") }
                .buffer(0.0)
            else -> null
        }

    private fun joined(codes: List<String>): Geometry {
        val found = boundaries.shapes(codes)
        val missing = codes.toSet() - found.keys
        if (missing.isNotEmpty()) throw ZoneInvalid("unknown boundaries: $missing")
        return UnaryUnionOp.union(found.values.map { GeoJson.read(mapper.readTree(it)) })
    }

    /** Cut to the parent, then away from every other zone of the same level. */
    private fun fitted(zone: Row, drawn: Geometry): Geometry {
        var shape = drawn
        if (zone.parent != null) {
            val parent = rows("zone_id = :v", zone.parent).single().shape
                ?: throw ZoneConflict("draw the parent 권역 first")
            shape = shape.intersection(GeoJson.read(mapper.readTree(parent)))
        }
        val others = rows("center_code = :v", zone.center)
            .filter { it.id != zone.id && it.parent == zone.parent && it.shape != null }
            .map { GeoJson.read(mapper.readTree(it.shape)) }
        if (others.isNotEmpty()) shape = shape.difference(UnaryUnionOp.union(others))
        if (shape.isEmpty || shape.area <= 0.0) throw ZoneConflict("nothing is left after other zones are cut away")
        return shape
    }

    private companion object {
        const val MAX_NAME = 40
    }
}
