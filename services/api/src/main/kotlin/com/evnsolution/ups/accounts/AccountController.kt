package com.evnsolution.ups.accounts

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class AccountController(private val store: AccountStore) {
    /**
     * Answers for every signed-in account, also one without data access, so the screen can show its status.
     * The CSRF token travels in a header: the page needs it for POST, and a cookie would not be a defense.
     */
    @GetMapping("/api/me")
    fun me(request: HttpServletRequest): ResponseEntity<Account> {
        val account = store.current(request) ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val response = ResponseEntity.ok()
        (request.getAttribute(SESSION_ATTRIBUTE) as? SessionInfo)?.let { response.header(CSRF_HEADER, it.csrfToken) }
        return response.body(account)
    }
}

const val CSRF_HEADER = "X-CSRF-Token"
