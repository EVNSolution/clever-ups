package com.evnsolution.ups

import com.evnsolution.ups.accounts.Account
import com.evnsolution.ups.accounts.AccountStore
import com.evnsolution.ups.accounts.MembershipStatus
import com.evnsolution.ups.accounts.Role
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** With no data source connected, a signed-in caller gets empty arrays. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SignedIn::class)
class DefaultsTest(@param:Autowired private val mockMvc: MockMvc) {
    @Test
    fun `lists are empty without a data source`() {
        for (path in listOf("/api/drivers", "/api/orders", "/api/routes", "/api/centers")) {
            mockMvc.get(path).andExpect {
                status { isOk() }
                content { json("[]") }
            }
        }
    }
}

/** Only the account, so the lists behind the gate stay on their empty defaults. */
@TestConfiguration
class SignedIn {
    @Bean @Primary
    fun account() = AccountStore { Account("a1", "test-login", Role.ADMIN, MembershipStatus.ACTIVE) }
}
