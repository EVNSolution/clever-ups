package com.evnsolution.ups.phone

import com.evnsolution.ups.accounts.AccountStore
import com.evnsolution.ups.accounts.BadDecision
import com.evnsolution.ups.accounts.CenterInviteService
import com.evnsolution.ups.accounts.NotAllowed
import com.evnsolution.ups.accounts.SignUpRules
import com.evnsolution.ups.accounts.NotFound
import com.evnsolution.ups.accounts.StateConflict
import com.evnsolution.ups.partner.KeyFile
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource

/**
 * Proves that the person who asked for access holds the phone number they gave, and nothing else.
 *
 * The number to verify is the one on the person's own open request. A caller cannot name another number,
 * so this cannot be used to text strangers. The send goes through [PhoneSendService]: the ledger reserves
 * first (daily cap, per-account cap, cooldown), so the service-wide daily limit holds on the server.
 * A passed proof sets `phone_verified` on the open request. It gives no access: the owner still decides.
 */
class PhoneVerificationService(
    private val jdbc: JdbcClient,
    private val sends: PhoneSendService,
    val proof: PhoneProof,
    val projectId: String,
    val webApiKey: String,
) {
    data class Target(val requestId: String, val phone: String, val verified: Boolean)

    /** The account's open request, or null. Only an open request is worth verifying. */
    fun target(accountId: String): Target? =
        jdbc.sql(
            "SELECT request_id, phone, phone_verified FROM access_request " +
                "WHERE account_id = :a AND status = 'PENDING' AND phone IS NOT NULL " +
                "ORDER BY created_at DESC LIMIT 1",
        ).param("a", accountId)
            .query { rs, _ ->
                Target(rs.getString("request_id").trim(), rs.getString("phone"), rs.getBoolean("phone_verified"))
            }
            .optional().orElse(null)

    fun send(accountId: String, requestKey: String, challenge: String): SendResult {
        if (requestKey.isBlank() || requestKey.length > MAX_KEY || challenge.isBlank()) {
            throw BadDecision("The request key and the challenge are required")
        }
        return sends.send(accountId, unverified(accountId).phone, requestKey, challenge)
    }

    private fun unverified(accountId: String): Target {
        val target = target(accountId) ?: throw NotFound("There is no open access request to verify")
        if (target.verified) throw StateConflict("The phone number is already verified")
        return target
    }

    /**
     * A send for a person who has no account yet (sign-up). The ledger counts it under the number itself, so the
     * same caps apply: the daily cap for everyone, the per-number cap and the cooldown. The browser's reCAPTCHA
     * token is still required by the provider.
     */
    fun sendToNumber(number: String, requestKey: String, challenge: String): SendResult {
        if (requestKey.isBlank() || requestKey.length > MAX_KEY || challenge.isBlank()) {
            throw BadDecision("The request key and the challenge are required")
        }
        return sends.send("phone:$number", number, requestKey, challenge)
    }

    /** True when the proof passed and the request is now marked. A number that differs is refused. */
    fun confirm(accountId: String, idToken: String): Boolean {
        val target = target(accountId) ?: throw NotFound("There is no open access request to verify")
        val proved = proof.verify(idToken)
        if (proved.number != target.phone) throw BadDecision("The verified number is not the number on the request")
        return jdbc.sql("UPDATE access_request SET phone_verified = TRUE WHERE request_id = :r AND status = 'PENDING'")
            .param("r", target.requestId).update() == 1
    }

    private companion object {
        const val MAX_KEY = 64
    }
}

/**
 * On only when the database, the Firebase project ID and the web API key file are all set. Without them
 * the endpoints answer `configured: false` and nothing can send.
 */
@Configuration
@ConditionalOnProperty(value = ["ups.db.url", "ups.firebase.project-id", "ups.firebase.api-key-file"])
class PhoneVerificationConfig {
    @Bean
    fun phoneSender(@Value("\${ups.firebase.api-key-file}") keyFile: String): PhoneSender =
        IdentityPlatformSender(KeyFile.read(Path.of(keyFile), "Firebase web API key"))

    @Bean
    fun phoneSendLedger(
        dataSource: DataSource,
        @Value("\${ups.phone.daily-limit:10}") daily: Int,
        @Value("\${ups.phone.per-account-limit:3}") perAccount: Int,
        @Value("\${ups.phone.cooldown-seconds:60}") cooldownSeconds: Long,
    ) = PhoneSendLedger(
        JdbcClient.create(dataSource),
        TransactionTemplate(DataSourceTransactionManager(dataSource)),
        SendLimits(daily = daily, perAccount = perAccount, cooldown = Duration.ofSeconds(cooldownSeconds)),
    )

    @Bean
    fun phoneVerificationService(
        dataSource: DataSource,
        ledger: PhoneSendLedger,
        sender: PhoneSender,
        proof: PhoneProof,
        clock: Clock,
        @Value("\${ups.firebase.project-id}") projectId: String,
        @Value("\${ups.firebase.api-key-file}") keyFile: String,
    ) = PhoneVerificationService(
        JdbcClient.create(dataSource),
        PhoneSendService(ledger, sender, clock),
        proof,
        projectId,
        KeyFile.read(Path.of(keyFile), "Firebase web API key"),
    )
}

data class PhoneStatus(
    val configured: Boolean,
    val verified: Boolean,
    val phoneHint: String?,
    val projectId: String?,
    val apiKey: String?,
)

data class PhoneSendBody(val requestKey: String?, val challenge: String?)

data class PublicPhoneSendBody(
    val phone: String?,
    val requestKey: String?,
    val challenge: String?,
    val inviteCode: String? = null,
)

/** What a sign-up form needs before anyone is signed in. The web API key is public by design. */
data class PublicPhoneConfig(val configured: Boolean, val projectId: String?, val apiKey: String?)

data class PhoneSendAnswer(val reference: String)

data class PhoneConfirmBody(val idToken: String?)

/** `+821012345678` → `010-****-5678`. The full number never leaves the server. */
fun phoneHint(e164: String): String {
    val national = e164.removePrefix("+82")
    return "0" + national.take(HINT_HEAD) + "-****-" + national.takeLast(HINT_TAIL)
}

private const val HINT_HEAD = 2
private const val HINT_TAIL = 4

/**
 * The person's own side of phone proof. The gate lets a signed-in account without membership in (a person
 * waiting for approval has to be able to verify); a POST still needs the session's CSRF token.
 */
@RestController
@RequestMapping("/api/phone")
@ConditionalOnProperty("ups.db.url")
class PhoneController(
    private val accounts: AccountStore,
    private val service: ObjectProvider<PhoneVerificationService>,
) {
    @GetMapping
    fun status(request: HttpServletRequest): PhoneStatus {
        val account = accounts.current(request) ?: throw NotAllowed("Not signed in")
        val verification = service.ifAvailable ?: return PhoneStatus(false, false, null, null, null)
        val target = verification.target(account.id)
        return PhoneStatus(
            true,
            target?.verified ?: false,
            target?.let { phoneHint(it.phone) },
            verification.projectId,
            verification.webApiKey,
        )
    }

    @PostMapping("/send")
    fun send(request: HttpServletRequest, @RequestBody body: PhoneSendBody): ResponseEntity<Any> {
        val account = accounts.current(request) ?: throw NotAllowed("Not signed in")
        return answerFor(configured().send(account.id, body.requestKey.orEmpty(), body.challenge.orEmpty()))
    }

    @PostMapping("/verify")
    fun verify(request: HttpServletRequest, @RequestBody body: PhoneConfirmBody): ResponseEntity<Void> {
        val account = accounts.current(request) ?: throw NotAllowed("Not signed in")
        configured().confirm(account.id, body.idToken.orEmpty())
        return ResponseEntity.noContent().build()
    }

    private fun configured(): PhoneVerificationService =
        service.ifAvailable ?: throw PhoneNotConfigured()
}

private fun problem(status: HttpStatus, detail: String): ResponseEntity<Any> =
    ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(ProblemDetail.forStatusAndDetail(status, detail))

/** One mapping of a send result to an HTTP answer, shared by the signed-in and the sign-up doors. */
private fun answerFor(result: SendResult): ResponseEntity<Any> =
    when (result) {
        is SendResult.Sent ->
            ResponseEntity.ok(PhoneSendAnswer(result.reference ?: throw StateConflict("No session reference")))
        is SendResult.Denied -> problem(HttpStatus.TOO_MANY_REQUESTS, "send-denied:" + result.reason.name)
        is SendResult.AlreadyHandled -> problem(HttpStatus.CONFLICT, "send-already-handled")
        is SendResult.Failed -> problem(HttpStatus.BAD_GATEWAY, "send-refused-nothing-sent")
        is SendResult.Uncertain -> problem(HttpStatus.BAD_GATEWAY, "send-uncertain-ask-again-later")
    }

/**
 * The sign-up door of phone proof: a person with no account yet proves the number they are about to give.
 * Open like sign-up itself (no session), so the only brakes are the ledger and the provider's reCAPTCHA.
 */
@RestController
@RequestMapping("/api/auth/phone")
@ConditionalOnProperty("ups.db.url")
class PhoneSignUpController(
    private val service: ObjectProvider<PhoneVerificationService>,
    private val invites: ObjectProvider<CenterInviteService>,
    @param:Value("\${ups.signup.code-required:true}") private val codeRequired: Boolean,
) {
    @GetMapping
    fun config(): PublicPhoneConfig {
        val verification = service.ifAvailable ?: return PublicPhoneConfig(false, null, null)
        return PublicPhoneConfig(true, verification.projectId, verification.webApiKey)
    }

    @PostMapping("/send")
    fun send(@RequestBody body: PublicPhoneSendBody): ResponseEntity<Any> {
        val verification = service.ifAvailable ?: throw PhoneNotConfigured()
        val number = SignUpRules.normalizePhone(body.phone.orEmpty())
            ?: throw BadDecision("The phone number is not a Korean mobile number")
        // No message is paid for before the sign-up code is known to be valid. Wrong codes count per number.
        if (codeRequired) {
            invites.getObject().requireValid(body.inviteCode.orEmpty(), "signup:$number")
        }
        return answerFor(verification.sendToNumber(number, body.requestKey.orEmpty(), body.challenge.orEmpty()))
    }
}

class PhoneNotConfigured : RuntimeException("Phone verification is not configured")

@RestControllerAdvice(assignableTypes = [PhoneController::class, PhoneSignUpController::class])
class PhoneErrors {
    @ExceptionHandler(PhoneProofException::class)
    fun refused(e: PhoneProofException) = answer(HttpStatus.BAD_REQUEST, e.message.orEmpty())

    @ExceptionHandler(PhoneNotConfigured::class)
    fun off(e: PhoneNotConfigured) = answer(HttpStatus.SERVICE_UNAVAILABLE, e.message.orEmpty())

    private fun answer(status: HttpStatus, detail: String): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ProblemDetail.forStatusAndDetail(status, detail))
}
