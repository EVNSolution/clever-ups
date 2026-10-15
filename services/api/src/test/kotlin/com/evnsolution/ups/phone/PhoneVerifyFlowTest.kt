package com.evnsolution.ups.phone

import com.evnsolution.ups.accounts.AccountService
import com.evnsolution.ups.partner.writeKeyFile
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.nio.file.Files
import java.time.Instant
import java.util.Collections
import java.util.UUID
import javax.sql.DataSource

private val sentTo: MutableList<String> = Collections.synchronizedList(mutableListOf())

/** The real sender and the real Firebase check are replaced: nothing here can send a message. */
@TestConfiguration
class FakePhoneBeans {
    @Bean
    @Primary
    fun fakeSender(): PhoneSender = PhoneSender { phone, _ -> sentTo.add(phone); "session-ref" }

    @Bean
    @Primary
    fun fakeProof(): PhoneProof = PhoneProof { token ->
        if (!token.startsWith("proved:")) throw PhoneProofException("The phone proof was refused")
        VerifiedPhone(token.removePrefix("proved:"), "uid", Instant.now())
    }
}

/** The person's side of phone proof, on the real beans with a fake provider. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakePhoneBeans::class)
class PhoneVerifyFlowTest(
    @param:Autowired private val mockMvc: MockMvc,
    @param:Autowired private val dataSource: DataSource,
) {
    private val jdbc get() = JdbcClient.create(dataSource)
    private val password = "Long-Enough-Pw-1"

    private class Session(val cookie: Cookie, val csrf: String, val accountId: String)

    @BeforeEach
    fun clean() {
        sentTo.clear()
        jdbc.sql("DELETE FROM phone_send").update()
    }

    private fun signUp(id: String, proof: String, phone: String = "010-1234-5678"): MvcResult =
        mockMvc.post("/api/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"loginId":"$id","password":"$password","fullName":"Kim Test","phone":"$phone",""" +
                """"phoneProof":"$proof"}"""
        }.andReturn()

    /**
     * An account whose number was proved at sign-up (the only way in while the provider is configured), then
     * set back to unverified so the signed-in proof flow can be exercised too.
     */
    private fun person(phone: String = "010-1234-5678"): Session {
        val id = "phone" + UUID.randomUUID().toString().take(8)
        assertEquals(201, signUp(id, "proved:+821012345678", phone).response.status)
        jdbc.sql("UPDATE access_request SET phone_verified = FALSE WHERE account_id = " +
            "(SELECT account_id FROM account WHERE login_key = :k)").param("k", id.lowercase()).update()
        val login = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"loginId":"$id","password":"$password"}"""
        }.andReturn()
        val token = login.response.getHeaders("Set-Cookie").first { it.startsWith("UPS_SESSION=") }
            .substringBefore(";").substringAfter("=")
        val accountId = jdbc.sql("SELECT account_id FROM account WHERE login_key = :k").param("k", id.lowercase())
            .query(String::class.java).single().trim()
        return Session(Cookie("UPS_SESSION", token), login.response.getHeader("X-CSRF-Token")!!, accountId)
    }

    private fun post(session: Session, path: String, body: String, csrf: Boolean = true): MvcResult =
        mockMvc.post(path) {
            cookie(session.cookie)
            if (csrf) header("X-CSRF-Token", session.csrf)
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andReturn()

    private fun verified(session: Session): Boolean =
        jdbc.sql("SELECT phone_verified FROM access_request WHERE account_id = :a").param("a", session.accountId)
            .query(Boolean::class.java).single()

    @Test
    fun `status shows a hint of the number and the public web settings, only to a signed-in account`() {
        mockMvc.get("/api/phone").andExpect { status { isUnauthorized() } }
        val me = person()
        mockMvc.get("/api/phone") { cookie(me.cookie) }.andExpect {
            status { isOk() }
            jsonPath("$.configured") { value(true) }
            jsonPath("$.verified") { value(false) }
            jsonPath("$.phoneHint") { value("010-****-5678") }
        }
    }

    @Test
    fun `sign-up needs a proof of the same number and marks the request verified`() {
        val none = signUp("nopf" + UUID.randomUUID().toString().take(6), "")
        assertEquals(400, none.response.status)
        val other = signUp("othr" + UUID.randomUUID().toString().take(6), "proved:+821099999999")
        assertEquals(400, other.response.status)
        val forged = signUp("forg" + UUID.randomUUID().toString().take(6), "forged")
        assertEquals(400, forged.response.status)
        val id = "good" + UUID.randomUUID().toString().take(6)
        assertEquals(201, signUp(id, "proved:+821012345678").response.status)
        val verified = jdbc.sql(
            "SELECT r.phone_verified FROM access_request r JOIN account a ON a.account_id = r.account_id " +
                "WHERE a.login_key = :k",
        ).param("k", id.lowercase()).query(Boolean::class.java).single()
        assertTrue(verified)
    }

    @Test
    fun `before sign-up the number is texted through the same ledger and the config is public`() {
        mockMvc.get("/api/auth/phone").andExpect {
            status { isOk() }
            jsonPath("$.configured") { value(true) }
            jsonPath("$.projectId") { value("clever-ups-test") }
        }
        fun sendTo(phone: String, key: String) = mockMvc.post("/api/auth/phone/send") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"phone":"$phone","requestKey":"$key","challenge":"c"}"""
        }.andReturn().response.status
        assertEquals(200, sendTo("010-5555-6666", "s1"))
        assertEquals(listOf("+821055556666"), sentTo.toList())
        assertEquals(409, sendTo("010-5555-6666", "s1"), "the same key is never sent twice")
        assertEquals(429, sendTo("01055556666", "s2"), "the cooldown holds per number")
        assertEquals(400, sendTo("02-123-4567", "s3"))
        assertEquals(1, sentTo.size)
    }

    @Test
    fun `a send needs the csrf token and goes to the number on the request, not one the caller names`() {
        val me = person()
        val withoutToken = post(me, "/api/phone/send", """{"requestKey":"k1","challenge":"c"}""", csrf = false)
        assertEquals(403, withoutToken.response.status)
        assertTrue(sentTo.isEmpty())
        val sent = post(me, "/api/phone/send", """{"requestKey":"k1","challenge":"c","phone":"+821099999999"}""")
        assertEquals(200, sent.response.status)
        assertTrue(sent.response.contentAsString.contains("session-ref"))
        assertEquals(listOf("+821012345678"), sentTo.toList())
    }

    @Test
    fun `a repeat is not sent again and a quick second request waits out the cooldown`() {
        val me = person()
        assertEquals(200, post(me, "/api/phone/send", """{"requestKey":"k1","challenge":"c"}""").response.status)
        assertEquals(409, post(me, "/api/phone/send", """{"requestKey":"k1","challenge":"c"}""").response.status)
        assertEquals(429, post(me, "/api/phone/send", """{"requestKey":"k2","challenge":"c"}""").response.status)
        assertEquals(1, sentTo.size)
    }

    @Test
    fun `a send without a challenge is refused before anything is reserved`() {
        val me = person()
        assertEquals(400, post(me, "/api/phone/send", """{"requestKey":"k1"}""").response.status)
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM phone_send").query(Int::class.java).single())
    }

    @Test
    fun `a proof of the same number marks the request, a proof of another number does not`() {
        val me = person()
        assertEquals(400, post(me, "/api/phone/verify", """{"idToken":"proved:+821099999999"}""").response.status)
        assertEquals(400, post(me, "/api/phone/verify", """{"idToken":"forged"}""").response.status)
        assertFalse(verified(me))
        assertEquals(204, post(me, "/api/phone/verify", """{"idToken":"proved:+821012345678"}""").response.status)
        assertTrue(verified(me))
        assertEquals(409, post(me, "/api/phone/send", """{"requestKey":"k9","challenge":"c"}""").response.status)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun settings(registry: DynamicPropertyRegistry) {
            val dir = Files.createTempDirectory("phone-flow")
            registry.add("ups.service-token-file") { writeKeyFile(dir, "service-token").toString() }
            registry.add("ups.firebase.project-id") { "clever-ups-test" }
            registry.add("ups.firebase.api-key-file") { writeKeyFile(dir, "firebase-key").toString() }
            registry.add("ups.signup.code-required") { "false" }
            registry.add("ups.db.url") {
                System.getenv("UPS_TEST_DB_URL")
                    ?: "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            }
            System.getenv("UPS_TEST_DB_USER")?.let { user -> registry.add("ups.db.user") { user } }
        }
    }
}

/** Sign-up needs a code: no message is paid for, and no account is made, without a valid one. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakePhoneBeans::class)
class SignUpGateTest(
    @param:Autowired private val mockMvc: MockMvc,
    @param:Autowired private val dataSource: DataSource,
    @param:Autowired private val accounts: AccountService,
) {
    private val jdbc get() = JdbcClient.create(dataSource)
    private val password = "Long-Enough-Pw-1"

    private fun ownerCode(centers: String = """["KRSNA","KRHWN"]""", role: String = "member"): String {
        val id = "gate" + UUID.randomUUID().toString().take(6)
        jdbc.sql("DELETE FROM membership WHERE role = 'owner'").update()
        accounts.createOwner(id, password)
        val login = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"loginId":"$id","password":"$password"}"""
        }.andReturn()
        val token = login.response.getHeaders("Set-Cookie").first { it.startsWith("UPS_SESSION=") }
            .substringBefore(";").substringAfter("=")
        val made = mockMvc.post("/api/center-invites") {
            cookie(Cookie("UPS_SESSION", token))
            header("X-CSRF-Token", login.response.getHeader("X-CSRF-Token")!!)
            contentType = MediaType.APPLICATION_JSON
            content = """{"centerCodes":$centers,"role":"$role"}"""
        }.andReturn()
        assertEquals(201, made.response.status)
        return Regex("\"code\":\"([A-Z0-9-]+)\"").find(made.response.contentAsString)!!.groupValues[1]
    }

    private fun sendStatus(phone: String, code: String, key: String): Int =
        mockMvc.post("/api/auth/phone/send") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"phone":"$phone","requestKey":"$key","challenge":"c","inviteCode":"$code"}"""
        }.andReturn().response.status

    private fun signUp(id: String, code: String, proof: String = "proved:+821012345678"): MvcResult =
        mockMvc.post("/api/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"loginId":"$id","password":"$password","fullName":"Kim Gate","phone":"010-1234-5678",""" +
                """"phoneProof":"$proof","inviteCode":"$code"}"""
        }.andReturn()

    @BeforeEach
    fun clean() {
        sentTo.clear()
        jdbc.sql("DELETE FROM phone_send").update()
    }

    @Test
    fun `the form is told that a code is needed`() {
        mockMvc.get("/api/auth/signup").andExpect { jsonPath("$.codeRequired") { value(true) } }
    }

    @Test
    fun `no message is sent without a valid code, and wrong codes lock the number`() {
        assertEquals(400, sendStatus("010-5555-0001", "", "g1"))
        assertEquals(400, sendStatus("010-5555-0001", "AAAA-AAAA-AAAA", "g2"))
        assertTrue(sentTo.isEmpty())
        repeat(3) { assertEquals(400, sendStatus("010-5555-0001", "AAAA-AAAA-AAAA", "gx$it")) }
        assertEquals(429, sendStatus("010-5555-0001", "AAAA-AAAA-AAAA", "g3"))
        assertTrue(sentTo.isEmpty(), "nothing was sent for any wrong code")
    }

    @Test
    fun `a valid code lets the message go and then makes an active account with its centers, once`() {
        val code = ownerCode()
        assertEquals(200, sendStatus("010-5555-0002", code, "ok1"))
        assertEquals(listOf("+821055550002"), sentTo.toList())
        val id = "coded" + UUID.randomUUID().toString().take(6)
        val made = signUp(id, code)
        assertEquals(201, made.response.status)
        assertTrue(made.response.contentAsString.contains("\"membership\":\"active\""))
        val centers = jdbc.sql(
            "SELECT center_code FROM account_center c JOIN account a ON a.account_id = c.account_id " +
                "WHERE a.login_key = :k ORDER BY center_code",
        ).param("k", id.lowercase()).query(String::class.java).list()
        assertEquals(listOf("KRHWN", "KRSNA"), centers)
        val again = signUp("again" + UUID.randomUUID().toString().take(6), code)
        assertEquals(400, again.response.status)
    }

    @Test
    fun `sign-up without a code or with a wrong one makes nothing`() {
        val before = jdbc.sql("SELECT COUNT(*) FROM account").query(Int::class.java).single()
        assertEquals(400, signUp("nocode" + UUID.randomUUID().toString().take(6), "").response.status)
        assertEquals(400, signUp("wrong" + UUID.randomUUID().toString().take(6), "AAAA-AAAA-AAAA").response.status)
        assertEquals(before, jdbc.sql("SELECT COUNT(*) FROM account").query(Int::class.java).single())
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun settings(registry: DynamicPropertyRegistry) {
            val dir = Files.createTempDirectory("signup-gate")
            registry.add("ups.service-token-file") { writeKeyFile(dir, "service-token").toString() }
            registry.add("ups.firebase.project-id") { "clever-ups-test" }
            registry.add("ups.firebase.api-key-file") { writeKeyFile(dir, "firebase-key").toString() }
            registry.add("ups.phone.cooldown-seconds") { "0" }
            registry.add("ups.db.url") {
                System.getenv("UPS_TEST_DB_URL")
                    ?: "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            }
            System.getenv("UPS_TEST_DB_USER")?.let { user -> registry.add("ups.db.user") { user } }
        }
    }
}

