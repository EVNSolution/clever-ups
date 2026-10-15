package com.evnsolution.ups.accounts

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

data class SignUpBody(
    val loginId: String?,
    val password: String?,
    val fullName: String?,
    val centerCode: String?,
    val driverId: String?,
    val phone: String?,
    val termsAccepted: Boolean?,
    val phoneProof: String? = null,
    val inviteCode: String? = null,
)

/** What the sign-up form needs to know before anyone is signed in. */
data class SignUpOptions(val codeRequired: Boolean)

data class ApproveBody(val role: String?, val centerCodes: List<String>?)

data class AccessProfileBody(
    val fullName: String?,
    val centerCode: String?,
    val driverId: String?,
    val phone: String?,
    val termsAccepted: Boolean?,
)

/** Sign-up is open to anyone and creates no access. Present only when a database is connected. */
@RestController
@ConditionalOnProperty("ups.db.url")
class SignUpController(private val signUps: SignUpService, private val accounts: AccountStore) {
    /**
     * A signed-in account without access asks again. The gate lets this one POST through without an active
     * membership; it still needs the session and the CSRF token. It gives no access: the owner decides.
     */
    @PostMapping("/api/access-requests")
    fun requestAgain(request: HttpServletRequest, @RequestBody body: AccessProfileBody): ResponseEntity<Account> {
        val actor = accounts.current(request) ?: throw NotAllowed("Not signed in")
        val account = signUps.requestAgain(
            actor,
            AccessProfile(
                body.fullName.orEmpty(), body.centerCode.orEmpty(), body.driverId.orEmpty(), body.phone.orEmpty(),
                body.termsAccepted == true,
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(account)
    }

    @PostMapping("/api/auth/signup")
    fun signUp(@RequestBody body: SignUpBody): ResponseEntity<Account> {
        val account = signUps.signUp(
            SignUp(
                body.loginId.orEmpty(), body.password.orEmpty(), body.fullName.orEmpty(), body.centerCode.orEmpty(),
                body.driverId.orEmpty(), body.phone.orEmpty(), body.termsAccepted == true, body.phoneProof.orEmpty(),
                body.inviteCode.orEmpty(),
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(account)
    }

    @GetMapping("/api/auth/signup")
    fun options(): SignUpOptions = SignUpOptions(signUps.codeRequired)
}

/** The owner's side: the queue, approve, reject, revoke. The person door of the gate has checked the session. */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty("ups.db.url")
class AccessController(private val requests: AccessRequestService, private val accounts: AccountStore) {
    @GetMapping("/access-requests")
    fun pending(
        request: HttpServletRequest,
        @RequestParam("limit", defaultValue = "100") limit: Int,
    ): List<AccessRequestView> = requests.pending(actor(request), limit.coerceIn(1, MAX_LIMIT))

    @PostMapping("/access-requests/{id}/approve")
    fun approve(
        request: HttpServletRequest,
        @PathVariable id: String,
        @RequestBody body: ApproveBody,
    ): ResponseEntity<Void> {
        val role = Role.entries.firstOrNull { it.value == body.role } ?: throw BadDecision("The role is not known")
        requests.approve(actor(request), id, role, body.centerCodes)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/access-requests/{id}/reject")
    fun reject(request: HttpServletRequest, @PathVariable id: String): ResponseEntity<Void> {
        requests.reject(actor(request), id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/memberships")
    fun members(
        request: HttpServletRequest,
        @RequestParam("limit", defaultValue = "200") limit: Int,
    ): List<MemberView> = requests.members(actor(request), limit.coerceIn(1, MAX_LIMIT))

    @PostMapping("/memberships/{accountId}/revoke")
    fun revoke(request: HttpServletRequest, @PathVariable accountId: String): ResponseEntity<Void> {
        requests.revoke(actor(request), accountId)
        return ResponseEntity.noContent().build()
    }

    private fun actor(request: HttpServletRequest): Account =
        accounts.current(request) ?: throw NotAllowed("Not signed in")

    private companion object {
        const val MAX_LIMIT = 500
    }
}

/** Short answers with a reason a person can act on. No stack, no internals. */
@RestControllerAdvice
class AccessErrors {
    private fun answer(status: HttpStatus, detail: String): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ProblemDetail.forStatusAndDetail(status, detail))

    @ExceptionHandler(InvalidSignUp::class, InvalidCredentials::class, BadDecision::class)
    fun bad(e: IllegalArgumentException) = answer(HttpStatus.BAD_REQUEST, e.message.orEmpty())

    @ExceptionHandler(AccountConflict::class, StateConflict::class)
    fun conflict(e: RuntimeException) = answer(HttpStatus.CONFLICT, e.message.orEmpty())

    @ExceptionHandler(InviteLocked::class)
    fun locked(e: InviteLocked): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .header("Retry-After", e.retryAfter.seconds.coerceAtLeast(1).toString())
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.message.orEmpty()))

    @ExceptionHandler(SignUpClosed::class)
    fun closed(e: SignUpClosed) = answer(HttpStatus.TOO_MANY_REQUESTS, e.message.orEmpty())

    @ExceptionHandler(NotAllowed::class)
    fun forbidden(e: NotAllowed) = answer(HttpStatus.FORBIDDEN, e.message.orEmpty())

    @ExceptionHandler(NotFound::class)
    fun missing(e: NotFound) = answer(HttpStatus.NOT_FOUND, e.message.orEmpty())
}
