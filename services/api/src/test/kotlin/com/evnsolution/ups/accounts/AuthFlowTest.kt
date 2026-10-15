package com.evnsolution.ups.accounts

import com.evnsolution.ups.partner.writeKeyFile
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.nio.file.Files
import java.util.UUID
import javax.sql.DataSource

/** The whole sign-in path on the real beans: H2 in PostgreSQL mode, real filters, real cookies. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTest(
    @param:Autowired private val mockMvc: MockMvc,
    @param:Autowired private val accounts: AccountService,
    @param:Autowired private val dataSource: DataSource,
) {
    private val jdbc get() = JdbcClient.create(dataSource)
    private val password = "Long-Enough-Pw-1"

    private fun uniqueId() = "user" + UUID.randomUUID().toString().take(8)

    private fun login(loginId: String, pw: String = password): MvcResult =
        mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"loginId":"$loginId","password":"$pw"}"""
        }.andReturn()

    /** The value of a cookie that a response sets. */
    private fun tokenOf(result: MvcResult, name: String): String =
        result.response.getHeaders("Set-Cookie").first { it.startsWith("$name=") }
            .substringBefore(";").substringAfter("=")

    private fun setCookie(result: MvcResult, name: String): String =
        result.response.getHeaders("Set-Cookie").first { it.startsWith("$name=") }

    /** MockMvc reads `request.cookies`, not a Cookie header, so the cookie is passed as an object. */
    private fun access(result: MvcResult) = Cookie(SESSION_COOKIE, tokenOf(result, SESSION_COOKIE))

    private fun refreshCookie(result: MvcResult) = Cookie(REFRESH_COOKIE, tokenOf(result, REFRESH_COOKIE))

    private fun refresh(result: MvcResult): MvcResult =
        mockMvc.post("/api/auth/refresh") {
            cookie(refreshCookie(result))
            header(REFRESH_MARKER, "1")
        }.andReturn()

    private fun memberLogin(): Pair<MvcResult, String> {
        val id = uniqueId()
        val account = accounts.createAccount(id, password)
        jdbc.sql(
            "INSERT INTO membership (account_id, role, status, created_at, updated_at) " +
                "VALUES (:id, 'member', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
        ).param("id", account.id).update()
        return login(id) to account.id
    }

    /** Moves one time column of an account's sessions to the year 2000. [column] is a fixed name from the tests. */
    private fun expire(column: String, accountId: String) {
        jdbc.sql("UPDATE session SET $column = TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00:00' " +
            "WHERE account_id = :id").param("id", accountId).update()
    }

    @Test
    fun `an owner signs in, reads data, and signs out with the CSRF token`() {
        val id = "owner" + UUID.randomUUID().toString().take(6)
        jdbc.sql("DELETE FROM session").update()
        jdbc.sql("DELETE FROM membership").update()
        accounts.createOwner(id, password)
        val result = login(id)
        assertEquals(200, result.response.status)
        val csrf = result.response.getHeader("X-CSRF-Token")!!

        mockMvc.get("/api/drivers") { cookie(access(result)) }.andExpect { status { isOk() } }
        mockMvc.get("/api/me") { cookie(access(result)) }.andExpect {
            status { isOk() }
            header { string("X-CSRF-Token", csrf) }
            jsonPath("$.role") { value("owner") }
            jsonPath("$.membership") { value("active") }
        }
        // A POST without the token is refused and the session stays alive.
        mockMvc.post("/api/auth/logout") { cookie(access(result)) }.andExpect { status { isForbidden() } }
        mockMvc.get("/api/drivers") { cookie(access(result)) }.andExpect { status { isOk() } }
        mockMvc.post("/api/auth/logout") {
            cookie(access(result))
            header("X-CSRF-Token", csrf)
        }.andExpect { status { isNoContent() } }
        mockMvc.get("/api/drivers") { cookie(access(result)) }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `the access cookie is short and the refresh cookie is scoped to the auth path`() {
        val (result, _) = memberLogin()
        val at = setCookie(result, SESSION_COOKIE)
        val rt = setCookie(result, REFRESH_COOKIE)
        for (cookie in listOf(at, rt)) {
            assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), cookie)
        }
        assertTrue(at.contains("Path=/;") || at.contains("Path=/ ") || at.endsWith("Path=/"), at)
        assertTrue(rt.contains("Path=/api/auth"), rt)
        val atAge = Regex("Max-Age=(\\d+)").find(at)!!.groupValues[1].toLong()
        val rtAge = Regex("Max-Age=(\\d+)").find(rt)!!.groupValues[1].toLong()
        assertTrue(atAge in 1..900, "access cookie lives $atAge s")
        assertTrue(rtAge > 86_400, "refresh cookie lives $rtAge s")
        assertNotEquals(tokenOf(result, SESSION_COOKIE), tokenOf(result, REFRESH_COOKIE))
    }

    @Test
    fun `when the access token ends, the refresh token signs the person in again and replaces itself`() {
        val (first, accountId) = memberLogin()
        expire("access_expires_at", accountId)
        mockMvc.get("/api/drivers") { cookie(access(first)) }.andExpect { status { isUnauthorized() } }

        val second = refresh(first)
        assertEquals(204, second.response.status)
        assertNotEquals(tokenOf(first, SESSION_COOKIE), tokenOf(second, SESSION_COOKIE))
        assertNotEquals(tokenOf(first, REFRESH_COOKIE), tokenOf(second, REFRESH_COOKIE))
        assertNotNull(second.response.getHeader("X-CSRF-Token"))
        mockMvc.get("/api/drivers") { cookie(access(second)) }.andExpect { status { isOk() } }
        // The old access token is dead, and the data still follows the membership.
        mockMvc.get("/api/drivers") { cookie(access(first)) }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a refresh needs the marker header and a refresh cookie`() {
        val (first, _) = memberLogin()
        val noMarker = mockMvc.post("/api/auth/refresh") { cookie(refreshCookie(first)) }.andReturn()
        assertEquals(401, noMarker.response.status)
        val noCookie = mockMvc.post("/api/auth/refresh") { header(REFRESH_MARKER, "1") }.andReturn()
        assertEquals(401, noCookie.response.status)
        // The access token is not a refresh token.
        val wrong = mockMvc.post("/api/auth/refresh") {
            cookie(Cookie(REFRESH_COOKIE, tokenOf(first, SESSION_COOKIE)))
            header(REFRESH_MARKER, "1")
        }.andReturn()
        assertEquals(401, wrong.response.status)
        // None of these ended the session.
        mockMvc.get("/api/drivers") { cookie(access(first)) }.andExpect { status { isOk() } }
    }

    @Test
    fun `an old refresh token seen at once is a race, seen later it ends the whole session`() {
        val (first, accountId) = memberLogin()
        val second = refresh(first)
        assertEquals(204, second.response.status)

        // Two tabs at the same moment: refused, nothing is ended, the new tokens still work.
        assertEquals(401, refresh(first).response.status)
        mockMvc.get("/api/drivers") { cookie(access(second)) }.andExpect { status { isOk() } }

        // The same old token an hour later: it was copied. The session is ended, the new tokens die too.
        expire("previous_rotated_at", accountId)
        assertEquals(401, refresh(first).response.status)
        mockMvc.get("/api/drivers") { cookie(access(second)) }.andExpect { status { isUnauthorized() } }
        assertEquals(401, refresh(second).response.status)
    }

    @Test
    fun `the absolute limit and an expired refresh token end the session for good`() {
        val (first, accountId) = memberLogin()
        expire("refresh_expires_at", accountId)
        assertEquals(401, refresh(first).response.status)

        val (other, otherId) = memberLogin()
        expire("absolute_expires_at", otherId)
        assertEquals(401, refresh(other).response.status)
        mockMvc.get("/api/drivers") { cookie(access(other)) }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a disabled account cannot refresh`() {
        val (first, accountId) = memberLogin()
        jdbc.sql("UPDATE account SET status = 'DISABLED' WHERE account_id = :id").param("id", accountId).update()
        assertEquals(401, refresh(first).response.status)
        mockMvc.get("/api/drivers") { cookie(access(first)) }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `an account without membership signs in and sees no data`() {
        val id = uniqueId()
        accounts.createAccount(id, password)
        val result = login(id)
        assertEquals(200, result.response.status)
        mockMvc.get("/api/me") { cookie(access(result)) }.andExpect {
            status { isOk() }
            jsonPath("$.membership") { value("none") }
            jsonPath("$.role") { doesNotExist() }
        }
        for (path in listOf("/api/drivers", "/api/orders", "/api/routes", "/api/centers")) {
            mockMvc.get(path) { cookie(access(result)) }.andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("membership-none") }
            }
        }
    }

    @Test
    fun `a membership that is revoked later closes the data at the next request`() {
        val (result, accountId) = memberLogin()
        mockMvc.get("/api/drivers") { cookie(access(result)) }.andExpect { status { isOk() } }
        jdbc.sql("UPDATE membership SET status = 'REVOKED' WHERE account_id = :id").param("id", accountId).update()
        mockMvc.get("/api/drivers") { cookie(access(result)) }.andExpect {
            status { isForbidden() }
            jsonPath("$.detail") { value("membership-revoked") }
        }
        // Who-am-I still answers, and shows no role: the role means something only while the membership is active.
        mockMvc.get("/api/me") { cookie(access(result)) }.andExpect {
            status { isOk() }
            jsonPath("$.membership") { value("revoked") }
            jsonPath("$.role") { doesNotExist() }
        }
    }

    @Test
    fun `a wrong password and an unknown ID look the same, and nothing is stored in clear`() {
        val id = uniqueId()
        accounts.createAccount(id, password)
        val wrong = login(id, "This-is-not-it-1!")
        val unknown = login("nobody" + UUID.randomUUID().toString().take(6))
        assertEquals(401, wrong.response.status)
        assertEquals(401, unknown.response.status)
        assertEquals(wrong.response.contentAsString, unknown.response.contentAsString)
        val stored = jdbc.sql("SELECT password_hash FROM password_credential")
            .query(String::class.java).list().filterNotNull()
        assertTrue(stored.none { it.contains(password) })
        assertTrue(stored.all { it.startsWith("pbkdf2-sha256\$") })
    }

    @Test
    fun `five wrong passwords lock the ID, also for the right password, and an unknown ID the same way`() {
        val id = uniqueId()
        accounts.createAccount(id, password)
        repeat(LoginService.MAX_FAILURES) { assertEquals(401, login(id, "Wrong-password-$it!").response.status) }
        val locked = login(id)
        assertEquals(429, locked.response.status)
        assertNotNull(locked.response.getHeader("Retry-After"))
        val ghost = "ghost" + UUID.randomUUID().toString().take(6)
        repeat(LoginService.MAX_FAILURES) { login(ghost, "Wrong-password-$it!") }
        assertEquals(429, login(ghost).response.status)
    }

    @Test
    fun `neither token is stored in clear`() {
        val (result, _) = memberLogin()
        val stored = jdbc.sql("SELECT access_hash, refresh_hash FROM session")
            .query { rs, _ -> listOf(rs.getString(1), rs.getString(2)) }.list().flatten()
        assertFalse(stored.contains(tokenOf(result, SESSION_COOKIE)))
        assertFalse(stored.contains(tokenOf(result, REFRESH_COOKIE)))
    }

    @Test
    fun `the password rule of the owner is enforced`() {
        val id = uniqueId()
        val bad = listOf(
            "Ab1!" to "too short",
            "abcdefg1!" to "no upper-case letter",
            "Abcdefgh!" to "no digit",
            "Abcdefg12" to "no special character",
            "한글비밀번호A1!" to "no ASCII letters is fine only if the rest holds",
        )
        for ((candidate, why) in bad.take(4)) {
            assertThrows<InvalidCredentials>(why) { accounts.createAccount(uniqueId(), candidate) }
        }
        accounts.createAccount(id, "Abcdef1!")
        assertThrows<InvalidCredentials> { accounts.createAccount("abcdefgh", "Abcdefgh-1") }
        assertThrows<InvalidCredentials> { accounts.createAccount("ab", password) }
        assertThrows<AccountConflict> { accounts.createAccount(id.uppercase(), password) }
    }

    @Test
    fun `a second owner is refused`() {
        val id = "owner" + UUID.randomUUID().toString().take(6)
        jdbc.sql("DELETE FROM session").update()
        jdbc.sql("DELETE FROM membership").update()
        accounts.createOwner(id, password)
        assertThrows<AccountConflict> { accounts.createOwner("second" + id, password) }
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun settings(registry: DynamicPropertyRegistry) {
            val dir = Files.createTempDirectory("auth-flow")
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
