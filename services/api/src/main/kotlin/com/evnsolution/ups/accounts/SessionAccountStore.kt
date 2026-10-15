package com.evnsolution.ups.accounts

import jakarta.servlet.http.HttpServletRequest

const val SESSION_COOKIE = "UPS_SESSION"

/**
 * The person behind the request's session cookie. It also leaves the [SessionInfo] on the request, so the
 * access gate can check the CSRF token without reading the database a second time.
 */
class SessionAccountStore(private val sessions: SessionService) : AccountStore {
    override fun current(request: HttpServletRequest): Account? {
        val token = request.cookies?.firstOrNull { it.name == SESSION_COOKIE }?.value
        val info = token?.let(sessions::resolve)
        if (info != null) request.setAttribute(SESSION_ATTRIBUTE, info)
        return info?.account
    }
}
