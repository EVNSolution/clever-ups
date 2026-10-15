package com.evnsolution.ups

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** Nobody is signed in here, so data is closed. Health stays open and /api/me answers its own 401. */
@SpringBootTest
@AutoConfigureMockMvc
class AccessGateTest(@param:Autowired private val mockMvc: MockMvc) {
    @Test
    fun `data is closed to a caller that is not signed in`() {
        for (path in listOf("/api/drivers", "/api/orders", "/api/routes", "/api/centers", "/api/unknown")) {
            mockMvc.get(path).andExpect { status { isUnauthorized() } }
        }
    }

    @Test
    fun `a bearer header does not open the user data`() {
        mockMvc.get("/api/orders") { header("Authorization", "Bearer anything") }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `nobody is signed in without an account source`() {
        mockMvc.get("/api/me").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `health stays open`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
    }
}
