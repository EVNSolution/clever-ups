package com.evnsolution.ups

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** The dates of /api/orders reach the store as given. No length limit is applied here. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FilledStores::class)
class OrdersQueryTest(@param:Autowired private val mockMvc: MockMvc) {
    @Test
    fun `no dates means today in Korea`() {
        // The fixed clock is 2026-10-07T16:00Z, which is already 2026-10-08 in Korea.
        mockMvc.get("/api/orders").andExpect { jsonPath("$[0].id") { value("o-2026-10-08..2026-10-08") } }
    }

    @Test
    fun `only from means that one day`() {
        mockMvc.get("/api/orders?from=2026-10-01")
            .andExpect { jsonPath("$[0].id") { value("o-2026-10-01..2026-10-01") } }
    }

    @Test
    fun `a long range is passed through unchanged`() {
        mockMvc.get("/api/orders?from=2026-09-01&to=2026-10-08")
            .andExpect { jsonPath("$[0].id") { value("o-2026-09-01..2026-10-08") } }
    }

    @Test
    fun `every order carries the default center, or none when it is not set`() {
        // The test context sets no default center, so the field is present and null.
        mockMvc.get("/api/orders?from=2026-10-01").andExpect { jsonPath("$[0].centerCode") { value(null as String?) } }
    }

    @Test
    fun `bad dates are rejected`() {
        mockMvc.get("/api/orders?from=2026-10-08&to=2026-10-01").andExpect { status { isBadRequest() } }
        mockMvc.get("/api/orders?from=not-a-date").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `incomplete answers are flagged in a header`() {
        mockMvc.get("/api/orders?from=2025-01-01").andExpect { header { string("X-Data-Incomplete", "true") } }
        mockMvc.get("/api/orders?from=2026-10-01").andExpect { header { doesNotExist("X-Data-Incomplete") } }
    }
}
