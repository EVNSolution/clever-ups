package com.evnsolution.ups.driver

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.net.InetSocketAddress

/** The driver app reads what the contract promises, and talks to the API the way the gate expects. */
class DriverApiContractTest {
    @Suppress("UNCHECKED_CAST")
    private val schemas: Map<String, Map<String, Any>> by lazy {
        val spec = Yaml().load<Map<String, Any>>(File("../../../contract/openapi.yaml").readText())
        (spec["components"] as Map<String, Any>)["schemas"] as Map<String, Map<String, Any>>
    }

    @Test
    fun `the parser reads every field the contract requires of a route and a stop`() {
        val route = schemas.getValue("DriverRoute")
        @Suppress("UNCHECKED_CAST")
        val stop = ((route["properties"] as Map<String, Any>)["stops"] as Map<String, Any>)["items"] as Map<String, Any>
        assertTrue(Json.ROUTE_FIELDS.containsAll(route["required"] as List<*>))
        assertTrue(Json.STOP_FIELDS.containsAll(stop["required"] as List<*>))
    }

    private class Seen(val method: String, val path: String, val csrf: String?, val body: String)

    @Test
    fun `a 401 renews the session once, and reports carry the CSRF token`() {
        val seen = mutableListOf<Seen>()
        var expired = true
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.rawPath
            seen += Seen(exchange.requestMethod, path, exchange.requestHeaders.getFirst("X-CSRF-Token"), exchange.requestBody.readBytes().decodeToString())
            val (status, body) = when {
                path == "/api/auth/login" -> 200 to """{"id":"a","loginId":"kim","role":"driver","membership":"active"}"""
                path == "/api/auth/refresh" -> { expired = false; 200 to "{}" }
                path == "/api/driver/route" && expired -> 401 to ""
                path == "/api/driver/route" -> 200 to ROUTE
                else -> 204 to ""
            }
            exchange.responseHeaders.add("X-CSRF-Token", "token-1")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
        server.start()
        try {
            val api = UpsApi("http://127.0.0.1:${server.address.port}")
            assertEquals("driver", api.login("kim", "pw"))
            val route = api.route()
            assertEquals(listOf("J1:2", "J1:1"), route.stops.map { it.orderId })
            assertEquals("서울 성남대로 1 101호", route.stops.first().title)
            assertEquals("DELIVERED", route.stops.last().result?.outcome)
            api.delivered(route.planId, "J1:2", "R", "KIM")
            val report = seen.last()
            assertEquals("/api/driver/route/p1/stops/J1%3A2/result", report.path)
            assertEquals("token-1", report.csrf)
            assertTrue(report.body.contains("\"podMethod\":\"FACE\""))
            assertEquals(listOf("/api/auth/login", "/api/driver/route", "/api/auth/refresh", "/api/driver/route"), seen.take(4).map { it.path })
            api.position(37.43, 127.17, 8.0)
            assertEquals("/api/driver/position", seen.last().path)
            assertTrue(seen.last().body.contains("\"lat\":37.43"))
            api.arrive(route.planId, "J1:1", null, null)
            assertTrue(seen.last().body.contains("\"lat\":null"))
        } finally {
            server.stop(0)
        }
    }

    private companion object {
        val ROUTE = """
            {"planId":"p1","day":"2026-10-14","driverId":"7001","routeCode":"정자1","departAt":"2026-10-14T00:00:00Z",
             "start":null,"distanceMeters":2000,"durationSeconds":600,"geometry":null,"stops":[
              {"orderId":"J1:2","seq":1,"arrival":"2026-10-14T00:05:00Z","distanceMeters":1000,"trackingNumber":"T2",
               "addressKo":"서울 성남대로 1","detailKo":"101호","address1":"x","address2":"","postalCode":"",
               "lng":127.1,"lat":37.4,"result":null},
              {"orderId":"J1:1","seq":2,"arrival":"2026-10-14T00:10:00Z","distanceMeters":2000,"trackingNumber":"T1",
               "addressKo":null,"detailKo":null,"address1":"1 Main St","address2":"","postalCode":"",
               "lng":127.2,"lat":37.5,"result":{"arrivedAt":null,"completedAt":"2026-10-14T00:11:00Z",
               "outcome":"DELIVERED","code":"KB","density":"R","podMethod":"DOOR","recipientName":null,"photoIds":["x"]}}]}
        """.trimIndent()
    }
}
