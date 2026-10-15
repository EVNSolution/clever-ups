package com.evnsolution.ups.accounts

import com.evnsolution.ups.centers.CenterStore
import com.evnsolution.ups.centers.SessionCenterStore
import com.evnsolution.ups.phone.PhoneVerificationService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource

/**
 * Turns real sign-in on. It needs the database, so it is off unless `ups.db.url` is set; then the empty
 * account store is replaced and the data endpoints answer only to a signed-in person with an active membership.
 */
@Configuration
@ConditionalOnProperty("ups.db.url")
class AuthConfig {
    @Bean
    fun passwordHasher() = PasswordHasher()

    @Bean
    fun accountService(dataSource: DataSource, hasher: PasswordHasher, clock: Clock) =
        AccountService(
            JdbcClient.create(dataSource),
            TransactionTemplate(DataSourceTransactionManager(dataSource)),
            hasher,
            clock,
        )

    @Bean
    fun sessionService(
        dataSource: DataSource,
        clock: Clock,
        @Value("\${ups.session.access-minutes:15}") accessMinutes: Long,
        @Value("\${ups.session.refresh-days:30}") refreshDays: Long,
        @Value("\${ups.session.absolute-days:90}") absoluteDays: Long,
    ) = SessionService(
        JdbcClient.create(dataSource),
        clock,
        SessionLifetimes(
            Duration.ofMinutes(accessMinutes),
            Duration.ofDays(refreshDays),
            Duration.ofDays(absoluteDays),
        ),
    )

    @Bean
    fun loginService(dataSource: DataSource, hasher: PasswordHasher, sessions: SessionService, clock: Clock) =
        LoginService(JdbcClient.create(dataSource), hasher, sessions, clock)

    @Bean
    fun accountTransactions(dataSource: DataSource) = TransactionTemplate(DataSourceTransactionManager(dataSource))

    @Bean
    fun signUpService(
        dataSource: DataSource,
        transactions: TransactionTemplate,
        accounts: AccountService,
        clock: Clock,
        @Value("\${ups.signup.daily-limit:100}") dailyLimit: Int,
        @Value("\${ups.signup.code-required:true}") codeRequired: Boolean,
        phone: ObjectProvider<PhoneVerificationService>,
        invites: CenterInviteService,
    ) = SignUpService(
        JdbcClient.create(dataSource), transactions, accounts, clock, dailyLimit, phone.ifAvailable?.proof, invites,
        codeRequired,
    )

    @Bean
    fun ownerTransferService(dataSource: DataSource, transactions: TransactionTemplate, clock: Clock) =
        OwnerTransferService(JdbcClient.create(dataSource), transactions, clock)

    @Bean
    fun centerInviteService(dataSource: DataSource, transactions: TransactionTemplate, clock: Clock) =
        CenterInviteService(JdbcClient.create(dataSource), transactions, clock)

    @Bean
    fun accessRequestService(dataSource: DataSource, transactions: TransactionTemplate, clock: Clock) =
        AccessRequestService(JdbcClient.create(dataSource), transactions, clock)

    @Bean
    @Primary
    fun sessionCenterStore(dataSource: DataSource): CenterStore = SessionCenterStore(JdbcClient.create(dataSource))

    @Bean
    @Primary
    fun sessionAccountStore(sessions: SessionService): AccountStore = SessionAccountStore(sessions)
}
