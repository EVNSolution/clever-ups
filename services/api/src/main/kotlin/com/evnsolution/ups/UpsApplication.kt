package com.evnsolution.ups

import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock
import java.time.ZoneId

@SpringBootApplication
class UpsApplication {
    /** "Today" means today in Korea, whatever the server's zone is. */
    @Bean
    fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))
}

/**
 * Terminal commands (first owner, owner transfer, boundary import) need no web server, so they cannot clash with a
 * running API.
 */
private val TERMINAL_COMMANDS = listOf("--ups.bootstrap-owner", "--ups.transfer-owner", "--ups.import-boundaries")

fun main(args: Array<String>) {
    val bootstrap = args.any { argument -> TERMINAL_COMMANDS.any { argument.startsWith(it) } }
    runApplication<UpsApplication>(*args) {
        if (bootstrap) setWebApplicationType(WebApplicationType.NONE)
    }
}
