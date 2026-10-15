package com.evnsolution.ups

import com.evnsolution.ups.partner.writeKeyFile
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.nio.file.Files

/**
 * A service reaches /api/addresses with its token and nothing else. A signed-in person does not reach
 * /api/addresses. GET on /api/addresses/{key} has no handler, so a request the gate lets in is 405, and one
 * it turns away is 401. The gate runs before routing, so the answer does not depend on the route.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SignedIn::class)
class ServiceGateTest(@param:Autowired private val mockMvc: MockMvc) {
    @Test
    fun `the token opens the addresses door`() {
        mockMvc.get("/api/addresses/probe") { header("Authorization", "Bearer service-token") }
            .andExpect { status { isMethodNotAllowed() } }
    }

    @Test
    fun `a wrong token or no token is turned away`() {
        mockMvc.get("/api/addresses/probe") { header("Authorization", "Bearer wrong") }
            .andExpect { status { isUnauthorized() } }
        mockMvc.get("/api/addresses/probe").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a signed-in person is not a service`() {
        // SignedIn makes every caller a signed-in person, and that still does not open the addresses door.
        mockMvc.get("/api/addresses").andExpect { status { isUnauthorized() } }
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun token(registry: DynamicPropertyRegistry) {
            val dir = Files.createTempDirectory("service-token")
            registry.add("ups.service-token-file") { writeKeyFile(dir, "service-token").toString() }
        }
    }
}
