package com.evnsolution.ups

import com.evnsolution.ups.accounts.Account
import com.evnsolution.ups.accounts.AccountStore
import com.evnsolution.ups.accounts.MembershipStatus
import com.evnsolution.ups.accounts.Role
import com.evnsolution.ups.orders.JobCenterStore
import com.evnsolution.ups.orders.Order
import com.evnsolution.ups.orders.OrderResult
import com.evnsolution.ups.orders.OrderStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** One row per tracking number, and the center of an order: its Job's center, else the default center. */
@SpringBootTest(properties = ["ups.default-center=krsna"])
@AutoConfigureMockMvc
@Import(OrdersCenterTest.Feed::class)
class OrdersCenterTest(@param:Autowired private val mockMvc: MockMvc) {
    @TestConfiguration
    class Feed {
        @Bean @Primary
        fun account() = AccountStore { Account("a1", "test-login", Role.ADMIN, MembershipStatus.ACTIVE) }

        // The same file uploaded twice: job-a and job-b hold the same tracking numbers.
        @Bean @Primary
        fun orders() = OrderStore {
            OrderResult(
                listOf(
                    Order("job-a:1", "TRK1", jobId = "job-a"),
                    Order("job-a:2", "TRK2", jobId = "job-a"),
                    Order("job-b:1", "TRK1", jobId = "job-b"),
                    Order("job-b:2", "TRK2", jobId = "job-b"),
                    Order("job-c:1", "TRK3", jobId = "job-c"),
                    Order("loose", "TRK4"),
                ),
            )
        }

        @Bean @Primary
        fun jobCenters() = JobCenterStore { ids ->
            mapOf("job-a" to "KRHWN", "job-b" to "KRHWN").filterKeys { it in ids }
        }
    }

    @Test
    fun `a tracking number is listed once, and each order carries its Job's center or the default`() {
        mockMvc.get("/api/orders?from=2026-10-05").andExpect {
            jsonPath("$.length()") { value(4) }
            jsonPath("$[0].id") { value("job-a:1") }
            jsonPath("$[0].centerCode") { value("KRHWN") }
            jsonPath("$[2].centerCode") { value("KRSNA") }
            jsonPath("$[3].centerCode") { value("KRSNA") }
        }
    }

    @Test
    fun `asking for a center returns only its orders, in any letter case`() {
        mockMvc.get("/api/orders?from=2026-10-05&centerId=krhwn").andExpect { jsonPath("$.length()") { value(2) } }
        mockMvc.get("/api/orders?from=2026-10-05&centerId=KRSNA").andExpect { jsonPath("$.length()") { value(2) } }
        mockMvc.get("/api/orders?from=2026-10-05&centerId=KROUL").andExpect { jsonPath("$.length()") { value(0) } }
    }
}
