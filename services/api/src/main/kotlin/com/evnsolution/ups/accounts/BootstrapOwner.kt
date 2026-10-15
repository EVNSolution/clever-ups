package com.evnsolution.ups.accounts

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

/**
 * Makes the first 개발 관리자 from a terminal, then ends the process:
 *
 *     java -jar ups-api.jar --ups.bootstrap-owner=<login-id>
 *
 * The password is typed twice without echo. It is never an argument, an environment variable or a file.
 * Needs the database settings of a normal run.
 */
@Component
@ConditionalOnProperty("ups.bootstrap-owner")
class BootstrapOwner(
    private val accounts: AccountService,
    private val context: ConfigurableApplicationContext,
    @param:Value("\${ups.bootstrap-owner}") private val loginId: String,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val console = System.console() ?: stop("Run this in a terminal: the password is typed without echo")
        try {
            AccountService.validateLoginId(loginId)
            println("Login ID: $loginId")
            val first = console.readPassword("Password (not shown): ")
            AccountService.validatePassword(loginId, String(first))
            val second = console.readPassword("Type it again: ")
            if (!first.contentEquals(second)) stop("The two passwords differ. Nothing was created.")
            accounts.createOwner(loginId, String(first))
        } catch (e: InvalidCredentials) {
            stop("${e.message}. Nothing was created.")
        } catch (e: AccountConflict) {
            stop("${e.message}. Nothing was created.")
        }
        println("Owner account created: $loginId")
        exitProcess(SpringApplication.exit(context))
    }

    private fun stop(message: String): Nothing {
        System.err.println(message)
        exitProcess(1)
    }
}
