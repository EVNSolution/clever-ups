package com.evnsolution.ups.boundaries

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LinearRing
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.union.UnaryUnionOp
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** One boundary row: what the table keeps, before the source and date are added. */
data class Boundary(
    val code: String,
    val level: String,
    val name: String,
    val parentCode: String?,
    val statCode: String?,
    val geometry: Geometry,
)

/**
 * Reads the 행정동 GeoJSON of vuski/admdongkor (properties adm_cd2, adm_cd, adm_nm, sgg, sggnm, sidonm) and makes
 * every level from it: each 행정동, each 시·군·구 or 일반구 as the union of its 행정동, and each 시 with 일반구 as
 * the union of its 구. Returned parents first, so rows can be written in order.
 */
object AdmDongKor {
    private val mapper = JsonMapper.builder().build()

    fun read(json: String): List<Boundary> {
        val features = mapper.readTree(json).path("features")
        require(features.isArray && !features.isEmpty) { "no features" }
        val dongs = features.values().map { feature ->
            val p = feature.path("properties")
            val sgg = p.text("sgg")
            Boundary(p.text("adm_cd2"), "DONG", p.text("adm_nm"), sgg, p.path("adm_cd").asString(null), shape(feature))
        }
        val sggs = features.values().groupBy { it.path("properties").text("sgg") }.map { (sgg, members) ->
            val p = members.first().path("properties")
            val (city, gu) = split(p.text("sggnm"))
            val parent = if (gu == null) null else cityCode(sgg)
            val name = listOfNotNull(p.text("sidonm"), city, gu).joinToString(" ")
            Boundary(sgg, "SGG", name, parent, null, union(dongs.filter { it.parentCode == sgg }))
        }
        val cities = sggs.filter { it.parentCode != null }.groupBy { it.parentCode!! }.map { (code, gus) ->
            Boundary(code, "CITY", gus.first().name.substringBeforeLast(' '), null, null, union(gus))
        }
        val all = cities + sggs + dongs
        val repeated = all.groupBy { it.code }.filterValues { it.size > 1 }.keys
        require(repeated.isEmpty()) { "codes used twice: $repeated" }
        return all
    }

    /** "성남시분당구" is 일반구 분당구 of 성남시; "종로구" and "가평군" have no 시 part. */
    fun split(sggName: String): Pair<String, String?> {
        val at = sggName.indexOf('시')
        return if (at in 1 until sggName.length - 1 && sggName.endsWith("구")) {
            sggName.substring(0, at + 1) to sggName.substring(at + 1)
        } else {
            sggName to null
        }
    }

    private fun cityCode(sgg: String) = sgg.take(CITY_PREFIX) + "0"

    private fun union(parts: List<Boundary>): Geometry = UnaryUnionOp.union(parts.map { it.geometry })

    private fun JsonNode.text(field: String): String =
        path(field).asString(null)?.trim()?.ifEmpty { null } ?: throw IllegalArgumentException("no $field in $this")

    private fun shape(feature: JsonNode): Geometry =
        runCatching { GeoJson.read(feature.path("geometry")) }
            .getOrElse { throw IllegalArgumentException("not a polygon: ${feature.path("properties")}", it) }

    private const val CITY_PREFIX = 4
}

/** GeoJSON polygons and multipolygons in WGS84 longitude and latitude, to and from JTS. */
object GeoJson {
    val factory = GeometryFactory()

    fun read(geometry: JsonNode): Geometry {
        val coordinates = geometry.path("coordinates")
        return when (geometry.path("type").asString("")) {
            "Polygon" -> polygon(coordinates)
            "MultiPolygon" -> factory.createMultiPolygon(coordinates.values().map(::polygon).toTypedArray())
            else -> throw IllegalArgumentException("not a polygon")
        }
    }

    private fun polygon(rings: JsonNode): Polygon {
        val all = rings.values().map(::ring)
        require(all.isNotEmpty()) { "a polygon needs a ring" }
        return factory.createPolygon(all.first(), all.drop(1).toTypedArray())
    }

    private fun ring(points: JsonNode): LinearRing =
        factory.createLinearRing(points.values().map { Coordinate(it[0].asDouble(), it[1].asDouble()) }.toTypedArray())

    /** Coordinates are rounded to about 1 cm. A collection keeps its polygons only. */
    fun write(geometry: Geometry): String {
        fun ring(points: Array<Coordinate>) = points.joinToString(",", "[", "]") { "[${round(it.x)},${round(it.y)}]" }
        fun polygon(shape: Polygon) =
            (listOf(shape.exteriorRing) + List(shape.numInteriorRing) { shape.getInteriorRingN(it) })
                .joinToString(",", "[", "]") { ring(it.coordinates) }
        val polygons = List(geometry.numGeometries) { geometry.getGeometryN(it) }.filterIsInstance<Polygon>()
        require(polygons.isNotEmpty()) { "no polygon in ${geometry.geometryType}" }
        return if (polygons.size == 1) {
            """{"type":"Polygon","coordinates":${polygon(polygons.single())}}"""
        } else {
            polygons.joinToString(",", """{"type":"MultiPolygon","coordinates":[""", "]}") { polygon(it) }
        }
    }
}

/** A JTS polygon or multipolygon as a GeoJSON geometry. */
fun geoJson(geometry: Geometry): String = GeoJson.write(geometry)

private const val SCALE = 1e7

private fun round(value: Double): Double = Math.round(value * SCALE) / SCALE
