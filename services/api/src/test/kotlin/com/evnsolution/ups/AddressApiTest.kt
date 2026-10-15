package com.evnsolution.ups

import com.evnsolution.ups.accounts.AccountService
import com.evnsolution.ups.accounts.SESSION_COOKIE
import com.evnsolution.ups.orders.Order
import com.evnsolution.ups.orders.OrderResult
import com.evnsolution.ups.orders.OrderStore
import com.evnsolution.ups.partner.writeKeyFile
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import javax.sql.DataSource
import java.nio.file.Files
import java.util.UUID

/** The whole loop: a person reads orders, a service reads the queue and writes a place, a person sees it. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AddressApiTest.OneOrder::class)
class AddressApiTest(
    @param:Autowired private val mockMvc: MockMvc,
    @param:Autowired private val accounts: AccountService,
    @param:Autowired private val dataSource: DataSource,
) {
    @TestConfiguration
    class OneOrder {
        @Bean @Primary
        fun orders() = OrderStore {
            OrderResult(listOf(Order("o1", "TRK1", address1 = "12 Teheran-ro", postalCode = "06134")))
        }
    }

    /** A person with an active membership, signed in through the real login. */
    private fun personCookie(): Cookie {
        val id = "member" + UUID.randomUUID().toString().take(8)
        val account = accounts.createAccount(id, "Long-Enough-Pw-1")
        JdbcClient.create(dataSource).sql(
            "INSERT INTO membership (account_id, role, status, created_at, updated_at) " +
                "VALUES (:id, 'member', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
        ).param("id", account.id).update()
        val login = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"loginId":"$id","password":"Long-Enough-Pw-1"}"""
        }.andReturn()
        val token = login.response.getHeaders("Set-Cookie").first { it.startsWith("$SESSION_COOKIE=") }
            .substringBefore(";").substringAfter("=")
        return Cookie(SESSION_COOKIE, token)
    }

    @Test
    fun `orders queue their address, a service answers, and the order gets its place`() {
        val person = personCookie()
        mockMvc.get("/api/orders") { cookie(person) }.andExpect { jsonPath("$[0].lat") { doesNotExist() } }

        val queue = mockMvc.get("/api/addresses?status=PENDING") { header("Authorization", "Bearer service-token") }
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val key = Regex("\"key\":\"([0-9a-f]{64})\"").find(queue)!!.groupValues[1]
        assertEquals(true, "/api/addresses/references" in Contract.servicePaths)
        val errors = Contract.responseSchema("/api/addresses").validate(ObjectMapper().readTree(queue))
        assertEquals(emptySet<Any>(), errors.toSet(), "GET /api/addresses violates the contract")

        mockMvc.put("/api/addresses/$key") {
            header("Authorization", "Bearer service-token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"DONE","addressKo":"서울 강남구 테헤란로 12","koSource":"TRANSLATOR","detailKo":"3층",""" +
                """"verifyStatus":"MISMATCH","lat":37.5,"lng":127.0}"""
        }.andExpect { status { isNoContent() } }
        mockMvc.get("/api/addresses?status=DONE") { header("Authorization", "Bearer service-token") }.andExpect {
            jsonPath("$[0].detailKo") { value("3층") }
            jsonPath("$[0].koSource") { value("TRANSLATOR") }
            jsonPath("$[0].verifyStatus") { value("MISMATCH") }
        }

        mockMvc.get("/api/orders") { cookie(person) }.andExpect {
            jsonPath("$[0].lat") { value(37.5) }
            jsonPath("$[0].lng") { value(127.0) }
            jsonPath("$[0].addressKo") { value("서울 강남구 테헤란로 12") }
        }
    }

    @Test
    fun `a service fills its own queue without any person signed in`() {
        mockMvc.post("/api/addresses/refresh?from=2026-10-07") { header("Authorization", "Bearer service-token") }
            .andExpect {
                status { isOk() }
                jsonPath("$.orders") { value(1) }
                jsonPath("$.incomplete") { value(false) }
            }
        mockMvc.post("/api/addresses/refresh?from=2026-10-08&to=2026-10-01") {
            header("Authorization", "Bearer service-token")
        }.andExpect { status { isBadRequest() } }
        mockMvc.post("/api/addresses/refresh?from=2026-10-07").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a service gets a reason for a bad answer`() {
        mockMvc.put("/api/addresses/${"a".repeat(64)}") {
            header("Authorization", "Bearer service-token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"DONE"}"""
        }.andExpect { status { isBadRequest() } }
        mockMvc.put("/api/addresses/${"a".repeat(64)}") {
            header("Authorization", "Bearer service-token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"FAILED"}"""
        }.andExpect { status { isNotFound() } }
        // A Korean address says who made it, and a detail without an address is refused.
        mockMvc.put("/api/addresses/${"a".repeat(64)}") {
            header("Authorization", "Bearer service-token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"FAILED","addressKo":"서울"}"""
        }.andExpect { status { isBadRequest() } }
        mockMvc.put("/api/addresses/${"a".repeat(64)}") {
            header("Authorization", "Bearer service-token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"FAILED","detailKo":"3층"}"""
        }.andExpect { status { isBadRequest() } }
        mockMvc.put("/api/addresses/not-a-key") {
            header("Authorization", "Bearer service-token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"FAILED"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `the reference set is read in pages by a service and is closed to others`() {
        mockMvc.get("/api/addresses/references?limit=2") { header("Authorization", "Bearer service-token") }
            .andExpect { status { isOk() } }
        mockMvc.get("/api/addresses/references?limit=0") { header("Authorization", "Bearer service-token") }
            .andExpect { status { isBadRequest() } }
        mockMvc.get("/api/addresses/references?after=nope") { header("Authorization", "Bearer service-token") }
            .andExpect { status { isBadRequest() } }
        mockMvc.get("/api/addresses/references").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `without the token the queue is closed`() {
        mockMvc.get("/api/addresses").andExpect { status { isUnauthorized() } }
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun settings(registry: DynamicPropertyRegistry) {
            val dir = Files.createTempDirectory("address-api")
            registry.add("ups.service-token-file") { writeKeyFile(dir, "service-token").toString() }
            // UPS_TEST_DB_URL (and UPS_TEST_DB_USER) run the same tests on a real PostgreSQL.
            registry.add("ups.signup.code-required") { "false" }
            registry.add("ups.db.url") {
                System.getenv("UPS_TEST_DB_URL")
                    ?: "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            }
            System.getenv("UPS_TEST_DB_USER")?.let { user -> registry.add("ups.db.user") { user } }
        }
    }
}
