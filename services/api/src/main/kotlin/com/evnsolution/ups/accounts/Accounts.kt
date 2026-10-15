package com.evnsolution.ups.accounts

import com.fasterxml.jackson.annotation.JsonValue
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

/** Role at the UPS organization. The names follow the HQ identity contract. */
enum class Role(@get:JsonValue val value: String) {
    OWNER("owner"),
    ADMIN("admin"),
    MEMBER("member"),
    VIEWER("viewer"),
    DRIVER("driver"),
}

/**
 * Whether the account may see data. NONE: no membership row. Only ACTIVE opens the data endpoints; every
 * other value answers 403 with the value as the reason, so the screen can say what to do next.
 */
enum class MembershipStatus(@get:JsonValue val value: String) {
    NONE("none"),
    PENDING("pending"),
    ACTIVE("active"),
    REJECTED("rejected"),
    REVOKED("revoked"),
}

/** The signed-in person. The login ID is shown as the nickname. [role] is null until a membership is active. */
data class Account(val id: String, val loginId: String, val role: Role?, val membership: MembershipStatus)

/** A session found for a request. [csrfToken] must come back in the X-CSRF-Token header of every POST. */
data class SessionInfo(val account: Account, val csrfToken: String)

/** The request attribute that holds the [SessionInfo] once a store has resolved the request's cookie. */
const val SESSION_ATTRIBUTE = "ups.session"

/** Who sent this request. Nobody until a login exists; then the person behind the session cookie. */
fun interface AccountStore {
    fun current(request: HttpServletRequest): Account?
}

@Component
class EmptyAccountStore : AccountStore {
    override fun current(request: HttpServletRequest): Account? = null
}
