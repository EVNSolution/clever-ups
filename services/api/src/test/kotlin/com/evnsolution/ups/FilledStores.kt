package com.evnsolution.ups

import com.evnsolution.ups.accounts.Account
import com.evnsolution.ups.accounts.AccountStore
import com.evnsolution.ups.accounts.MembershipStatus
import com.evnsolution.ups.accounts.Role
import com.evnsolution.ups.centers.Center
import com.evnsolution.ups.centers.CenterStore
import com.evnsolution.ups.drivers.Driver
import com.evnsolution.ups.drivers.DriverStore
import com.evnsolution.ups.orders.Order
import com.evnsolution.ups.orders.OrderResult
import com.evnsolution.ups.orders.OrderStore
import com.evnsolution.ups.routes.Route
import com.evnsolution.ups.routes.RouteStore
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Test-only items prove that real model objects serialize into the contract. They are not shipped data.
 * Item ids carry the center id, so a test can see that the center reached the store.
 */
@TestConfiguration
class FilledStores {
    @Bean @Primary
    fun account() = AccountStore { Account("a1", "test-login", Role.ADMIN, MembershipStatus.ACTIVE) }

    @Bean @Primary
    fun centers() = CenterStore {
        listOf(Center("c1", "test center 1", "test address", "0001"), Center("c2", "test center 2"))
    }

    @Bean @Primary
    fun drivers() = DriverStore { centerId ->
        listOf(
            Driver("d-$centerId", "c1", "007", 127.0, 37.5, Instant.parse("2026-10-08T01:00:00Z")),
            Driver("d2", "c1"),
        )
    }

    /** The order ID shows the days that reached the store. Days before 2026 are answered as incomplete. */
    @Bean @Primary
    fun orders() = OrderStore { query ->
        OrderResult(
            listOf(
                Order("o-${query.from}..${query.to}", "TRK1", "test customer", "test company", "test address 1",
                    "test address 2", "01234", "PENDING", "job1", 127.0, 37.5),
                Order("o2", "TRK2"),
            ),
            incomplete = query.from.year < 2026,
        )
    }

    @Bean @Primary
    fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-10-07T16:00:00Z"), ZoneId.of("Asia/Seoul"))

    // Two drivers share route code 017: duplicates are allowed.
    @Bean @Primary
    fun routes() = RouteStore { centerId ->
        listOf(
            Route("r-$centerId", "c1", "017", listOf("d1", "d2")),
            Route("r2", "c2", "010"),
        )
    }
}
