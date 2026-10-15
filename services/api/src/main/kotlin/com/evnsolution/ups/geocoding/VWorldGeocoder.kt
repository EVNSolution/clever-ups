package com.evnsolution.ups.geocoding

import org.slf4j.LoggerFactory
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Reads one URL and returns the body. A failure to reach or an unusable status is an [IOException]. */
fun interface JsonSource {
    fun get(url: String): String
}

/** The real source: one GET with short timeouts, since a person waits for the answer. */
class HttpJsonSource(
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_SECONDS)).build(),
) : JsonSource {
    override fun get(url: String): String {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(REQUEST_SECONDS))
            .header("Accept", "application/json")
            .header("User-Agent", "clever-ups-geocode/0.1")
            .GET()
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted", e)
        }
        if (response.statusCode() !in OK_RANGE) throw IOException("HTTP ${response.statusCode()}")
        return response.body()
    }

    private companion object {
        const val CONNECT_SECONDS = 3L
        const val REQUEST_SECONDS = 5L
        val OK_RANGE = 200..299
    }
}

/**
 * Places a Korean address with V-World (vworld.kr), the way the DSV route server does.
 *
 *  - source: EVNSolution/clever-route-server, apps/delivery-api/src/modules/geocoding/vworld-geocoding.client.ts
 *    at 55c1a72be82c506870dffbfdc7c5c8cc8678188f; the same method is in `scripts/geocode_addresses.py`.
 *  - kept: the search call (service=search, type=address, category ROAD or PARCEL, EPSG:4326, size 1); ROAD
 *    first when the address has a road number, PARCEL first otherwise; and the answer's province must be the
 *    province asked for.
 *  - changed: Kotlin; no cache table and no place search. UPS has its own key; DSV's key is not used.
 *
 * Only the address text, without anything in parentheses, goes to api.vworld.kr. A rate-limit answer or a
 * network failure is [GeoResult.Unavailable], never a thrown error, so saving an address does not depend on it.
 */
class VWorldGeocoder(
    private val key: String,
    private val source: JsonSource = HttpJsonSource(),
) : Geocoder {
    private val mapper = JsonMapper.builder().build()

    override fun locate(addressKo: String): GeoResult {
        val address = clean(addressKo)
        if (address.isEmpty()) return GeoResult.NotFound
        val order = if (ROAD_NUMBER.containsMatchIn(address)) listOf("ROAD", "PARCEL") else listOf("PARCEL", "ROAD")
        val asked = province(address)
        return try {
            order.firstNotNullOfOrNull { category -> found(address, category, asked) } ?: GeoResult.NotFound
        } catch (e: ProviderUnavailable) {
            log.warn("V-World could not answer: {}", e.message)
            GeoResult.Unavailable
        }
    }

    private fun found(address: String, category: String, asked: String?): GeoResult? {
        val hit = search(address, category) ?: return null
        val answered = hit.label?.let(::province)
        return when {
            asked != null && answered != null && asked != answered -> null
            hit.lat in LAT_RANGE && hit.lng in LNG_RANGE -> GeoResult.Found(hit.lat, hit.lng)
            else -> GeoResult.OutsideKorea
        }
    }

    private class Hit(val lat: Double, val lng: Double, val label: String?)

    private class ProviderUnavailable(reason: String, cause: Throwable? = null) : RuntimeException(reason, cause)

    private fun search(address: String, category: String): Hit? {
        val query = linkedMapOf(
            "service" to "search", "request" to "search", "version" to "2.0", "crs" to "EPSG:4326", "size" to "1",
            "page" to "1", "query" to address, "type" to "address", "category" to category, "format" to "json",
            "errorformat" to "json", "key" to key,
        ).entries.joinToString("&") { (name, value) ->
            "$name=" + URLEncoder.encode(value, StandardCharsets.UTF_8)
        }
        val response = fetch("$SEARCH_URL?$query")
        return when (response.path("status").asString("").uppercase()) {
            "OK" -> firstItem(response)
            "NOT_FOUND" -> null
            "ERROR" -> throw ProviderUnavailable("refused: " + response.path("error").path("code").asString("UNKNOWN"))
            else -> null
        }
    }

    private fun fetch(url: String): JsonNode =
        try {
            mapper.readTree(source.get(url)).path("response")
        } catch (e: IOException) {
            throw ProviderUnavailable("could not be reached", e)
        } catch (e: JacksonException) {
            throw ProviderUnavailable("unreadable answer", e)
        }

    private fun firstItem(response: JsonNode): Hit? {
        val item = response.path("result").path("items").path(0)
        if (item.isMissingNode) return null
        val point = item.path("point")
        val lat = point.path("y").asString("").toDoubleOrNull()
        val lng = point.path("x").asString("").toDoubleOrNull()
        if (lat == null || lng == null) throw ProviderUnavailable("answer without a point")
        val place = item.path("address")
        val label = place.path("road").asString("").ifEmpty { place.path("parcel").asString("") }
        return Hit(lat, lng, label.ifEmpty { null })
    }

    private fun clean(text: String): String = text.replace(PARENTHESES, " ").replace(WHITE_SPACE, " ").trim()

    /** The short province name, so "경기도" and "경기" are the same, or null when the text does not start with one. */
    private fun province(text: String): String? {
        val name = PROVINCE.find(text)?.groupValues?.get(1) ?: return null
        return HEADS.firstOrNull { name.startsWith(it) } ?: SHORT[name] ?: name
    }

    private companion object {
        const val SEARCH_URL = "https://api.vworld.kr/req/search"
        val log = LoggerFactory.getLogger(VWorldGeocoder::class.java)
        val LAT_RANGE = 33.0..39.0
        val LNG_RANGE = 124.0..132.0
        val ROAD_NUMBER = Regex("(?:대로|로|길)\\s*\\d")
        val PARENTHESES = Regex("\\([^)]*\\)")
        val WHITE_SPACE = Regex("\\s+")
        val PROVINCE = Regex(
            "^(서울(?:특별시)?|부산(?:광역시)?|대구(?:광역시)?|인천(?:광역시)?|광주(?:광역시)?|대전(?:광역시)?|" +
                "울산(?:광역시)?|세종(?:특별자치시)?|경기(?:도)?|강원(?:특별자치도|도)?|충청북도|충북|충청남도|충남|" +
                "전북특별자치도|전라북도|전북|전라남도|전남|경상북도|경북|경상남도|경남|제주특별자치도|제주도|제주)(?=\\s|$)",
        )
        val HEADS = listOf("서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기", "강원", "제주")
        val SHORT = mapOf(
            "충청북도" to "충북", "충청남도" to "충남", "전북특별자치도" to "전북", "전라북도" to "전북",
            "전라남도" to "전남", "경상북도" to "경북", "경상남도" to "경남",
        )
    }
}
