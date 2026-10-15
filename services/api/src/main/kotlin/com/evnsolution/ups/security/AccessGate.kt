package com.evnsolution.ups.security

import com.evnsolution.ups.accounts.AccountStore
import com.evnsolution.ups.accounts.CSRF_HEADER
import com.evnsolution.ups.accounts.MembershipStatus
import com.evnsolution.ups.accounts.Role
import com.evnsolution.ups.accounts.SESSION_ATTRIBUTE
import com.evnsolution.ups.accounts.SessionInfo
import com.evnsolution.ups.partner.KeyFile
import com.evnsolution.ups.partner.Ours24Exception
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Two kinds of caller reach this API, and they never share a door.
 *
 *  - A person signs in on the web. The API reads that person's account from [AccountStore].
 *    Not signed in: 401. Signed in without an active membership: 403 with the reason. Active: may read data
 *    (orders, drivers, routes, centers), and a POST must carry the session's CSRF token.
 *    A person cannot reach `/api/addresses`.
 *  - A service of ours (for example the job that translates and geocodes addresses) sends a bearer token.
 *    The token is the content of the file named by `ups.service-token-file`. A service can reach
 *    `/api/addresses` and nothing else.
 *
 * This is a servlet filter, not an interceptor, so it runs before routing: a path or a method that no
 * controller knows is turned away the same way as a known one. Everything under `/api` is closed unless a
 * rule below opens it. `/api/me` answers its own 401. The Ours24 key stays on the server; no caller,
 * person or service, ever receives it.
 */
@Component
class AccessGate(
    private val accounts: AccountStore,
    @param:Value("\${ups.service-token-file:}") private val tokenFile: String,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        // The container has already decoded and normalized this path (no "..", no ";params").
        val path = request.servletPath + request.pathInfo.orEmpty()
        when {
            path == ME || path == LOGIN || path == REFRESH || path == SIGNUP || path in SIGNUP_PHONE ->
                chain.doFilter(request, response)
            path == ADDRESSES || path.startsWith("$ADDRESSES/") ->
                if (serviceTokenMatches(request.getHeader(AUTHORIZATION))) {
                    chain.doFilter(request, response)
                } else {
                    response.status = HttpStatus.UNAUTHORIZED.value()
                }
            path == API || path.startsWith("$API/") -> personDoor(request, response, chain, path)
            else -> chain.doFilter(request, response)
        }
    }

    private fun personDoor(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
        path: String,
    ) {
        val account = accounts.current(request)
        val reason = when {
            account == null -> null
            needsMembership(request, path) && account.membership != MembershipStatus.ACTIVE ->
                "membership-" + account.membership.value
            // A driver account sees its own route only, never the center's lists.
            account.role == Role.DRIVER && !driverMay(path) -> "role-driver"
            request.method in UNSAFE && !csrfMatches(request) -> "csrf"
            else -> ""
        }
        when {
            account == null -> response.status = HttpStatus.UNAUTHORIZED.value()
            reason.isNullOrEmpty() -> chain.doFilter(request, response)
            else -> forbid(response, reason)
        }
    }

    /**
     * Paths a signed-in account may use without an active membership: leaving, asking again, entering a center
     * code, and proving its own phone number. They still need the session and the CSRF token.
     */
    private fun needsMembership(request: HttpServletRequest, path: String): Boolean {
        val own = request.method == "POST" && (path == ACCESS_REQUESTS || path == REDEEM)
        val phone = path == PHONE || path.startsWith("$PHONE/")
        return path != LOGOUT && !own && !phone
    }

    private fun driverMay(path: String): Boolean =
        path == DRIVER || path.startsWith("$DRIVER/") || path == LOGOUT || path == PHONE || path.startsWith("$PHONE/")

    /** Fails closed: a request whose session was not resolved by a session store has no token to match. */
    private fun csrfMatches(request: HttpServletRequest): Boolean {
        val expected = (request.getAttribute(SESSION_ATTRIBUTE) as? SessionInfo)?.csrfToken
        val sent = request.getHeader(CSRF_HEADER)
        return expected != null && sent != null && MessageDigest.isEqual(bytes(sent), bytes(expected))
    }

    private fun bytes(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)

    private fun forbid(response: HttpServletResponse, reason: String) {
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = "application/problem+json"
        response.writer.write(
            "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"detail\":\"$reason\"}",
        )
    }

    /** Fails closed: no token file, an unusable file or no header all mean no access. */
    private fun serviceTokenMatches(header: String?): Boolean {
        if (tokenFile.isBlank() || header == null || !header.startsWith(BEARER)) return false
        val expected = try {
            KeyFile.read(Path.of(tokenFile), "service token")
        } catch (e: Ours24Exception) {
            log.warn("The service token file is unusable: {}", e.message)
            null
        }
        return expected != null && MessageDigest.isEqual(
            header.removePrefix(BEARER).toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private companion object {
        const val API = "/api"
        const val ME = "/api/me"
        const val LOGIN = "/api/auth/login"
        const val SIGNUP = "/api/auth/signup"
        const val REFRESH = "/api/auth/refresh"
        const val LOGOUT = "/api/auth/logout"
        const val ACCESS_REQUESTS = "/api/access-requests"
        const val REDEEM = "/api/center-invites/redeem"
        const val PHONE = "/api/phone"
        const val DRIVER = "/api/driver"
        val SIGNUP_PHONE = setOf("/api/auth/phone", "/api/auth/phone/send")
        val UNSAFE = setOf("POST", "PUT", "PATCH", "DELETE")
        const val ADDRESSES = "/api/addresses"
        const val AUTHORIZATION = "Authorization"
        const val BEARER = "Bearer "
        val log = LoggerFactory.getLogger(AccessGate::class.java)
    }
}
