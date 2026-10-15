package com.evnsolution.ups.accounts

import com.evnsolution.ups.partner.writeKeyFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.nio.file.Files
import java.util.UUID

/** A flood of sign-ups stops at the daily cap, for everyone. */
@SpringBootTest
@AutoConfigureMockMvc
class SignUpCapTest(@param:Autowired private val mockMvc: MockMvc) {
    private fun signUp(loginId: String) = mockMvc.post("/api/auth/signup") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"loginId":"$loginId","password":"Long-Enough-Pw-1","fullName":"Kim","centerCode":"KRHWN",""" +
            """"driverId":"D1","phone":"01012345678","termsAccepted":true}"""
    }.andReturn().response.status

    @Test
    fun `the third sign-up of the day is refused when the cap is two`() {
        assertEquals(201, signUp("cap" + UUID.randomUUID().toString().take(6)))
        assertEquals(201, signUp("cap" + UUID.randomUUID().toString().take(6)))
        assertEquals(429, signUp("cap" + UUID.randomUUID().toString().take(6)))
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun settings(registry: DynamicPropertyRegistry) {
            val dir = Files.createTempDirectory("signup-cap")
            registry.add("ups.service-token-file") { writeKeyFile(dir, "service-token").toString() }
            registry.add("ups.signup.daily-limit") { "2" }
            registry.add("ups.signup.code-required") { "false" }
            registry.add("ups.db.url") {
                "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            }
        }
    }
}
