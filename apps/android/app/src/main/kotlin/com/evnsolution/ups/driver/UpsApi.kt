package com.evnsolution.ups.driver

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A refused call: the HTTP status says why (401 signed out, 403 not a driver, 404 nothing sent today). */
class ApiRefused(val status: Int) : IOException("HTTP $status")

/** The sign-in itself was refused: a wrong ID or password (401), or too many tries (429). */
class LoginRefused(val status: Int) : IOException("sign-in refused: HTTP $status")

/**
 * The driver app's door to the UPS API. Session cookies live in a [CookieManager] for the life of the process;
 * the CSRF token comes with sign-in and refresh. A 401 renews the session once with the refresh cookie and asks
 * again. Every call blocks: run it off the main thread.
 */
class UpsApi(private val baseUrl: String) {
    private var csrf: String? = null

    init {
        if (CookieHandler.getDefault() == null) CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
    }

    /** Signs in. Returns the account's role; anything but `driver` cannot use this app. */
    fun login(loginId: String, password: String): String {
        val body = JSONObject().put("loginId", loginId).put("password", password)
        val (status, answer) = send("POST", "/api/auth/login", body.toString().toByteArray(), JSON)
        if (status !in OK) throw LoginRefused(status)
        return JSONObject(answer).optString("role")
    }

    fun route(): DriverRoute = Json.route(JSONObject(call("GET", "/api/driver/route")))

    fun codes(): List<UpsCode> = Json.codes(JSONArray(call("GET", "/api/driver/codes")))

    fun arrive(planId: String, orderId: String, lat: Double?, lng: Double?) {
        val body = JSONObject().put("lat", lat ?: JSONObject.NULL).put("lng", lng ?: JSONObject.NULL)
        call("POST", "${stop(planId, orderId)}/arrive", body.toString().toByteArray(), JSON)
    }

    /** The phone's place now; the server keeps only the latest one. */
    fun position(lat: Double, lng: Double, accuracy: Double) {
        val body = JSONObject().put("lat", lat).put("lng", lng).put("accuracy", accuracy)
        call("POST", "/api/driver/position", body.toString().toByteArray(), JSON)
    }

    fun delivered(planId: String, orderId: String, density: String, recipientName: String?) {
        val body = JSONObject().put("outcome", "DELIVERED").put("density", density)
            .put("podMethod", if (recipientName == null) "DOOR" else "FACE").put("recipientName", recipientName)
        call("POST", "${stop(planId, orderId)}/result", body.toString().toByteArray(), JSON)
    }

    fun exception(planId: String, orderId: String, code: String) {
        val body = JSONObject().put("outcome", "EXCEPTION").put("code", code)
        call("POST", "${stop(planId, orderId)}/result", body.toString().toByteArray(), JSON)
    }

    fun photo(planId: String, orderId: String, jpeg: ByteArray) {
        call("POST", "${stop(planId, orderId)}/photos", jpeg, "image/jpeg")
    }

    private fun call(
        method: String,
        path: String,
        body: ByteArray? = null,
        type: String? = null,
        retry: Boolean = true,
    ): String {
        val (status, text) = send(method, path, body, type)
        val renewed = status == HttpURLConnection.HTTP_UNAUTHORIZED && retry && refresh()
        if (renewed) return call(method, path, body, type, false)
        if (status !in OK) throw ApiRefused(status)
        return text
    }

    private fun refresh(): Boolean =
        send("POST", "/api/auth/refresh", ByteArray(0), JSON, mapOf("X-UPS-Refresh" to "1")).first in OK

    private fun send(
        method: String,
        path: String,
        body: ByteArray?,
        type: String?,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            csrf?.let { connection.setRequestProperty("X-CSRF-Token", it) }
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                type?.let { connection.setRequestProperty("Content-Type", it) }
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            connection.getHeaderField("X-CSRF-Token")?.let { csrf = it }
            val stream = if (status in OK) connection.inputStream else connection.errorStream
            return status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val JSON = "application/json"
        const val TIMEOUT_MS = 15_000
        val OK = 200..299
    }
}

/** The path of one stop of the driver's route. Order IDs carry a colon, so both parts are encoded. */
private fun stop(planId: String, orderId: String) = "/api/driver/route/${encode(planId)}/stops/${encode(orderId)}"

private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
