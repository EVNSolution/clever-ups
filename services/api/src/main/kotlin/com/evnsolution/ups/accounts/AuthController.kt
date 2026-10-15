package com.evnsolution.ups.accounts

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class LoginRequest(val loginId: String?, val password: String?)

const val REFRESH_COOKIE = "UPS_REFRESH"
const val REFRESH_PATH = "/api/auth"

/** A cross-site form cannot set this header, so a refresh that has it came from our own page. */
const val REFRESH_MARKER = "X-UPS-Refresh"

/**
 * Sign in, refresh and sign out. Present only when a database is connected (`ups.db.url`).
 *
 *  - Access token: cookie `UPS_SESSION`, sent to every API path, lives minutes.
 *  - Refresh token: cookie `UPS_REFRESH`, scoped to `/api/auth`, so it is not sent with ordinary API calls.
 * Both are HttpOnly and SameSite=Strict, and Secure on HTTPS (or always, with `ups.session.secure-cookie`).
 */
@RestController
@ConditionalOnProperty("ups.db.url")
class AuthController(
    private val logins: LoginService,
    private val sessions: SessionService,
    private val clock: Clock,
    @param:Value("\${ups.session.secure-cookie:false}") private val secureAlways: Boolean,
) {
    @PostMapping("/api/auth/login")
    fun login(@RequestBody body: LoginRequest, request: HttpServletRequest): ResponseEntity<Account> {
        val loginId = body.loginId
        val password = body.password
        val acceptable = !loginId.isNullOrBlank() && !password.isNullOrEmpty() &&
            password.length <= AccountService.MAX_PASSWORD
        if (!acceptable) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return when (val result = logins.login(loginId, password)) {
            is LoginResult.Success -> ResponseEntity.ok()
                .headers { it.addAll(tokenHeaders(result.session, request)) }
                .body(result.info.account)
            is LoginResult.Failure -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
            is LoginResult.Locked -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, result.retryAfter.seconds.coerceAtLeast(1).toString()).build()
        }
    }

    /**
     * Trades the refresh token for a new pair. Called by the page when an API call came back 401, then the
     * call is repeated. No body. The old refresh token stops working at once.
     */
    @PostMapping("/api/auth/refresh")
    fun refresh(request: HttpServletRequest): ResponseEntity<Void> {
        val token = request.cookies?.firstOrNull { it.name == REFRESH_COOKIE }?.value
        val marked = request.getHeader(REFRESH_MARKER) != null
        val result = if (token == null || !marked) Refreshed.Rejected else sessions.refresh(token)
        return if (result is Refreshed.Ok) {
            ResponseEntity.noContent().headers { it.addAll(tokenHeaders(result.session, request)) }.build()
        } else {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).headers { it.addAll(clearHeaders(request)) }.build()
        }
    }

    /** The access gate has already checked the session and the CSRF token. */
    @PostMapping("/api/auth/logout")
    fun logout(request: HttpServletRequest): ResponseEntity<Void> {
        request.cookies?.firstOrNull { it.name == SESSION_COOKIE }?.let { sessions.revoke(it.value) }
        return ResponseEntity.noContent().headers { it.addAll(clearHeaders(request)) }.build()
    }

    private fun tokenHeaders(session: IssuedSession, request: HttpServletRequest): HttpHeaders {
        val now = clock.instant()
        val headers = HttpHeaders()
        val accessAge = until(now, session.accessExpiresAt)
        val refreshAge = until(now, session.refreshExpiresAt)
        val access = cookie(SESSION_COOKIE, session.accessToken, "/", accessAge, request)
        val refresh = cookie(REFRESH_COOKIE, session.refreshToken, REFRESH_PATH, refreshAge, request)
        headers.add(HttpHeaders.SET_COOKIE, access)
        headers.add(HttpHeaders.SET_COOKIE, refresh)
        headers.set(CSRF_HEADER, session.csrfToken)
        return headers
    }

    private fun clearHeaders(request: HttpServletRequest): HttpHeaders {
        val headers = HttpHeaders()
        headers.add(HttpHeaders.SET_COOKIE, cookie(SESSION_COOKIE, "", "/", Duration.ZERO, request))
        headers.add(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, "", REFRESH_PATH, Duration.ZERO, request))
        return headers
    }

    private fun until(now: Instant, end: Instant): Duration = Duration.between(now, end).coerceAtLeast(Duration.ZERO)

    private fun cookie(
        name: String,
        value: String,
        path: String,
        maxAge: Duration,
        request: HttpServletRequest,
    ): String =
        ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(secureAlways || request.isSecure)
            .sameSite("Strict")
            .path(path)
            .maxAge(maxAge)
            .build()
            .toString()
}
