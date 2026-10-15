package com.evnsolution.ups

import com.evnsolution.ups.accounts.Account
import com.evnsolution.ups.accounts.AccountStore
import com.evnsolution.ups.accounts.MembershipStatus
import com.evnsolution.ups.accounts.Role
import com.evnsolution.ups.orders.JobCenterStore
import com.evnsolution.ups.orders.JobLister
import com.evnsolution.ups.orders.Order
import com.evnsolution.ups.orders.OrderQuery
import com.evnsolution.ups.orders.OrderResult
import com.evnsolution.ups.orders.OrderStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
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
import java.time.LocalDate

/** Asking for one center reads only that center's Jobs from a source that can list Jobs. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OrdersJobFilterTest.Feed::class)
class OrdersJobFilterTest(
    @param:Autowired private val mockMvc: MockMvc,
    @param:Autowired private val feed: Feed.Source,
) {
    @TestConfiguration
    class Feed {
        class Source : OrderStore, JobLister {
            val asked = mutableListOf<Set<String>?>()

            override fun jobIds(from: LocalDate, to: LocalDate) = listOf("job-a", "job-b", "job-c")

            override fun find(query: OrderQuery): OrderResult {
                asked += query.jobIds
                val rows = listOf(
                    Order("a:1", "TRK1", jobId = "job-a"),
                    Order("b:1", "TRK2", jobId = "job-b"),
                    Order("c:1", "TRK3", jobId = "job-c"),
                )
                return OrderResult(rows.filter { query.jobIds == null || it.jobId in query.jobIds })
            }
        }

        @Bean
        fun source() = Source()

        @Bean @Primary
        fun orders(source: Source): OrderStore = source

        @Bean @Primary
        fun account() = AccountStore { Account("a1", "test-login", Role.ADMIN, MembershipStatus.ACTIVE) }

        @Bean @Primary
        fun jobCenters() = JobCenterStore { ids ->
            mapOf("job-a" to "KRSNA", "job-b" to "KRSNA", "job-c" to "KRHWN").filterKeys { it in ids }
        }
    }

    @BeforeEach
    fun clear() = feed.asked.clear()

    @Test
    fun `one center reads only its own Jobs`() {
        mockMvc.get("/api/orders?from=2026-10-05&centerId=KRSNA").andExpect { jsonPath("$.length()") { value(2) } }
        assertEquals(listOf(setOf("job-a", "job-b")), feed.asked)
    }

    @Test
    fun `a center with no Job reads nothing, and no center reads everything`() {
        mockMvc.get("/api/orders?from=2026-10-05&centerId=KROUL").andExpect { jsonPath("$.length()") { value(0) } }
        assertEquals(emptyList<Set<String>?>(), feed.asked)
        mockMvc.get("/api/orders?from=2026-10-05").andExpect { jsonPath("$.length()") { value(3) } }
        assertEquals(listOf<Set<String>?>(null), feed.asked)
    }
}
