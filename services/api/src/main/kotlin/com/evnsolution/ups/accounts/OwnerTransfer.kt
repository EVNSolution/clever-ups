package com.evnsolution.ups.accounts

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.system.exitProcess

/**
 * Hands the 개발 관리자 role to another account. There is still exactly one owner afterwards: the old owner
 * becomes an admin. Only the terminal can do this (see [OwnerTransferRunner]); no web call reaches it.
 * It writes one audit line for each of the two accounts, with the evidence `owner-transfer`.
 */
class OwnerTransferService(
    private val jdbc: JdbcClient,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
) {
    /** [centers] replaces the new owner's center list (an owner sees every center that any account holds). */
    fun transfer(toLoginId: String, centers: List<String>) {
        val codes = centers.map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct()
        if (codes.any { !CENTER.matches(it) }) {
            throw BadDecision("A center code is 2 to 20 letters, digits, dashes or underscores")
        }
        transactions.executeWithoutResult { move(toLoginId.lowercase(Locale.ROOT), codes) }
    }

    private fun move(key: String, codes: List<String>) {
        val at = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val target = jdbc.sql("SELECT account_id FROM account WHERE login_key = :k").param("k", key)
            .query(String::class.java).optional().orElseThrow { NotFound("No account has this login ID") }
            .orEmpty().trim()
        val previous = jdbc.sql("SELECT account_id FROM membership WHERE role = 'owner' AND status = 'ACTIVE'")
            .query(String::class.java).list().map { it.orEmpty().trim() }
        if (previous.size != 1) throw StateConflict("There must be exactly one owner to hand over from")
        if (previous.single() == target) throw StateConflict("This account is already the owner")
        val from = previous.single()
        jdbc.sql(
            "UPDATE membership SET role = 'admin', decided_by = :to, decided_at = :at, updated_at = :at " +
                "WHERE account_id = :a",
        )
            .param("to", target).param("at", at).param("a", from).update()
        val has = jdbc.sql("SELECT COUNT(*) FROM membership WHERE account_id = :a").param("a", target)
            .query(Int::class.java).single()
        if (has > 0) {
            jdbc.sql(
                "UPDATE membership SET role = 'owner', status = 'ACTIVE', decided_by = :from, decided_at = :at, " +
                    "updated_at = :at WHERE account_id = :a",
            ).param("from", from).param("at", at).param("a", target).update()
        } else {
            jdbc.sql(
                "INSERT INTO membership (account_id, role, status, decided_by, decided_at, created_at, updated_at) " +
                    "VALUES (:a, 'owner', 'ACTIVE', :from, :at, :at, :at)",
            ).param("a", target).param("from", from).param("at", at).update()
        }
        jdbc.sql(
            "UPDATE access_request SET status = 'APPROVED', decided_by = :from, decided_at = :at, " +
                "decision_role = 'owner' WHERE account_id = :a AND status = 'PENDING'",
        ).param("from", from).param("at", at).param("a", target).update()
        jdbc.sql("DELETE FROM account_center WHERE account_id = :a").param("a", target).update()
        codes.forEach {
            jdbc.sql(
                "INSERT INTO account_center (account_id, center_code, granted_by, granted_at) " +
                    "VALUES (:a, :c, :from, :at)",
            ).param("a", target).param("c", it).param("from", from).param("at", at).update()
        }
        audit(target, from, "owner", codes.joinToString(","), at)
        audit(from, from, "admin", null, at)
    }

    private fun audit(account: String, actor: String, role: String, centers: String?, at: OffsetDateTime) {
        jdbc.sql(
            "INSERT INTO access_decision (decision_id, request_id, account_id, actor_id, action, role, " +
                "center_codes, evidence, decided_at) " +
                "VALUES (:id, NULL, :acc, :actor, 'APPROVE', :role, :centers, 'owner-transfer', :at)",
        ).param("id", UUID.randomUUID().toString()).param("acc", account).param("actor", actor).param("role", role)
            .param("centers", centers).param("at", at).update()
    }

    private companion object {
        val CENTER = Regex("[A-Z0-9_-]{2,20}")
    }
}

/**
 * The terminal command that moves the owner role, then ends the process:
 *
 *     java -jar ups-api.jar --ups.transfer-owner=<login-id> --ups.transfer-centers=KRHWN,KRSNA
 *
 * The login ID is typed again to confirm. Needs the database settings of a normal run.
 */
@Component
@ConditionalOnProperty("ups.transfer-owner")
class OwnerTransferRunner(
    private val service: OwnerTransferService,
    private val context: ConfigurableApplicationContext,
    @param:Value("\${ups.transfer-owner}") private val loginId: String,
    @param:Value("\${ups.transfer-centers:}") private val centers: String,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val console = System.console() ?: stop("Run this in a terminal: the confirmation is typed by a person")
        println("The owner role will move to: $loginId")
        println("Centers given to it: ${centers.ifBlank { "(none)" }}")
        val again = console.readLine("Type the login ID again to confirm: ")
        if (again != loginId) stop("The two login IDs differ. Nothing was changed.")
        try {
            service.transfer(loginId, centers.split(","))
        } catch (e: NotFound) {
            stop("${e.message}. Nothing was changed.")
        } catch (e: StateConflict) {
            stop("${e.message}. Nothing was changed.")
        } catch (e: BadDecision) {
            stop("${e.message}. Nothing was changed.")
        }
        println("Owner role moved to: $loginId")
        exitProcess(SpringApplication.exit(context))
    }

    private fun stop(message: String): Nothing {
        System.err.println(message)
        exitProcess(1)
    }
}
