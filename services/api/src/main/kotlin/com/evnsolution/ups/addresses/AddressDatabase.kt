package com.evnsolution.ups.addresses

import com.evnsolution.ups.orders.JdbcOrderEdits
import com.evnsolution.ups.orders.JdbcTaskStore
import com.evnsolution.ups.orders.OrderEditStore
import com.evnsolution.ups.orders.TaskStore
import com.evnsolution.ups.partner.KeyFile
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.nio.file.Path
import javax.sql.DataSource

/**
 * Connects the API to its database. It is off unless `ups.db.url` is set, so a plain run and most tests
 * need no database. The password is never a setting: `ups.db.password-file` names a mode-600 file.
 * Migrations in `db/migration` run at startup; a failed migration stops the start.
 */
@Configuration
@ConditionalOnProperty("ups.db.url")
@EnableScheduling
class AddressDatabase {
    @Bean(destroyMethod = "close")
    fun addressDataSource(
        @Value("\${ups.db.url}") url: String,
        @Value("\${ups.db.user:}") user: String,
        @Value("\${ups.db.password-file:}") passwordFile: String,
    ): DataSource {
        val config = HikariConfig()
        config.jdbcUrl = url
        if (user.isNotBlank()) config.username = user
        if (passwordFile.isNotBlank()) config.password = KeyFile.read(Path.of(passwordFile), "database password")
        config.maximumPoolSize = POOL_SIZE
        val dataSource = HikariDataSource(config)
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
        return dataSource
    }

    @Bean
    @Primary
    fun addressStore(addressDataSource: DataSource): AddressStore =
        JdbcAddressStore(JdbcClient.create(addressDataSource))

    @Bean
    @Primary
    fun taskStore(addressDataSource: DataSource): TaskStore = JdbcTaskStore(JdbcClient.create(addressDataSource))

    @Bean
    @Primary
    fun orderEditStore(addressDataSource: DataSource, transactions: TransactionTemplate, clock: Clock): OrderEditStore =
        JdbcOrderEdits(JdbcClient.create(addressDataSource), transactions, clock)

    private companion object {
        const val POOL_SIZE = 5
    }
}
