package com.evnsolution.ups.accounts

import com.evnsolution.ups.partner.writeKeyFile
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.nio.file.Files
import java.util.UUID
import javax.sql.DataSource

/** Sign-up, the owner's decisions and what each state of an account can see, on the real beans. */
@SpringBootTest
@AutoConfigureMockMvc
class AccessFlowTest(
    @param:Autowired private val mockMvc: MockMvc,
    @param:Autowired private val accounts: AccountService,
    @param:Autowired private val dataSource: DataSource,
) {
    private val jdbc get() = JdbcClient.create(dataSource)
    private val password = "Long-Enough-Pw-1"

    private class Session(val cookie: Cookie, val csrf: String, val accountId: String)

    private fun unique(prefix: String) = prefix + UUID.randomUUID().toString().take(8)

    private fun signUpBody(
        loginId: String,
        phone: String = "010-1234-5678",
        terms: Boolean = true,
        pw: String = password,
    ) =
        """{"loginId":"$loginId","password":"$pw","fullName":"Kim Test","centerCode":"krhwn",""" +
            """"driverId":"D-100","phone":"$phone","termsAccepted":$terms}"""

    private fun signUp(loginId: String, body: String = signUpBody(loginId)): MvcResult =
        mockMvc.post("/api/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andReturn()

    private fun login(loginId: String): Session {
        val result = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"loginId":"$loginId","password":"$password"}"""
        }.andReturn()
        assertEquals(200, result.response.status, "login of $loginId")
        val token = result.response.getHeaders("Set-Cookie").first { it.startsWith("$SESSION_COOKIE=") }
            .substringBefore(";").substringAfter("=")
        val id = jdbc.sql("SELECT account_id FROM account WHERE login_key = :k").param("k", loginId.lowercase())
            .query(String::class.java).single().trim()
        return Session(Cookie(SESSION_COOKIE, token), result.response.getHeader("X-CSRF-Token")!!, id)
    }

    /** The one owner of this test context. */
    private val owner: Session by lazy {
        val id = "owner" + UUID.randomUUID().toString().take(6)
        jdbc.sql("DELETE FROM membership WHERE role = 'owner'").update()
        accounts.createOwner(id, password)
        login(id)
    }

    /** The owner exists before a test makes anyone else, so no later step has to touch sessions. */
    @BeforeEach
    fun ownerExists() {
        owner
    }

    private fun requestIdOf(accountId: String): String =
        jdbc.sql("SELECT request_id FROM access_request WHERE account_id = :a ORDER BY created_at DESC LIMIT 1")
            .param("a", accountId).query(String::class.java).single().trim()

    private fun decide(session: Session, path: String, body: String? = null): MvcResult =
        mockMvc.post(path) {
            cookie(session.cookie)
            header("X-CSRF-Token", session.csrf)
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
        }.andReturn()

    private fun statusOf(requestId: String): String =
        jdbc.sql("SELECT status FROM access_request WHERE request_id = :r").param("r", requestId)
            .query(String::class.java).single()

    private fun approve(requestId: String, role: String, by: Session = owner): MvcResult =
        decide(by, "/api/access-requests/$requestId/approve", """{"role":"$role"}""")

    private fun signedUpAndApproved(role: String = "viewer"): Session {
        val id = unique("member")
        assertEquals(201, signUp(id).response.status)
        val person = login(id)
        assertEquals(204, approve(requestIdOf(person.accountId), role).response.status)
        return person
    }

    @Test
    fun `sign-up makes an account that can sign in and sees nothing`() {
        val id = unique("newbie")
        val result = signUp(id)
        assertEquals(201, result.response.status)
        val person = login(id)
        mockMvc.get("/api/me") { cookie(person.cookie) }.andExpect {
            status { isOk() }
            jsonPath("$.membership") { value("pending") }
            jsonPath("$.role") { doesNotExist() }
        }
        for (path in listOf("/api/drivers", "/api/orders", "/api/routes", "/api/centers")) {
            mockMvc.get(path) { cookie(person.cookie) }.andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("membership-pending") }
            }
        }
        val stored = jdbc.sql(
            "SELECT center_code, phone, phone_verified, status FROM access_request WHERE account_id = :a",
        )
            .param("a", person.accountId).query { rs, _ ->
                listOf(rs.getString(1), rs.getString(2), rs.getBoolean(3).toString(), rs.getString(4))
            }.single()
        assertEquals(listOf("KRHWN", "+821012345678", "false", "PENDING"), stored)
    }

    @Test
    fun `bad input is refused field by field and creates nothing`() {
        val cases = mapOf(
            "phone" to signUpBody(unique("p"), phone = "02-123-4567"),
            "password" to signUpBody(unique("w"), pw = "alllowercase1!"),
        )
        for ((why, body) in cases) {
            assertEquals(400, signUp("x", body).response.status, why)
        }
        val before = jdbc.sql("SELECT COUNT(*) FROM account").query(Int::class.java).single()
        signUp("x", signUpBody(unique("z"), phone = "abc"))
        assertEquals(before, jdbc.sql("SELECT COUNT(*) FROM account").query(Int::class.java).single())
    }

    @Test
    fun `sign-up needs no center or driver ID, and the owner names the center when approving`() {
        val id = unique("slim")
        val body = """{"loginId":"$id","password":"$password","fullName":"Kim Slim","phone":"010-1234-5678",""" +
            """"termsAccepted":true}"""
        assertEquals(201, signUp(id, body).response.status)
        val person = login(id)
        val row = jdbc.sql("SELECT center_code, driver_id FROM access_request WHERE account_id = :a")
            .param("a", person.accountId).query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
        assertEquals(null to null, row)
        val requestId = requestIdOf(person.accountId)
        assertEquals(400, approve(requestId, "member").response.status, "no center was named anywhere")
        val path = "/api/access-requests/$requestId/approve"
        val named = decide(owner, path, """{"role":"member","centerCodes":["KRSEL"]}""")
        assertEquals(204, named.response.status)
        mockMvc.get("/api/centers") { cookie(person.cookie) }.andExpect { jsonPath("$[0].id") { value("KRSEL") } }
    }

    @Test
    fun `a taken login ID is a conflict in any letter case`() {
        val id = unique("taken")
        assertEquals(201, signUp(id).response.status)
        assertEquals(409, signUp(id.uppercase(), signUpBody(id.uppercase())).response.status)
    }

    @Test
    fun `the owner approves, and the person sees data of the granted center only`() {
        val person = signedUpAndApproved("member")
        mockMvc.get("/api/me") { cookie(person.cookie) }.andExpect {
            jsonPath("$.role") { value("member") }
            jsonPath("$.membership") { value("active") }
        }
        mockMvc.get("/api/drivers") { cookie(person.cookie) }.andExpect { status { isOk() } }
        mockMvc.get("/api/centers") { cookie(person.cookie) }.andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value("KRHWN") }
        }
        val audit = jdbc.sql("SELECT action, role FROM access_decision WHERE account_id = :a")
            .param("a", person.accountId).query { rs, _ -> rs.getString(1) to rs.getString(2) }.list()
        assertEquals(listOf("APPROVE" to "member"), audit)
    }

    @Test
    fun `only the owner decides, and approving twice is a conflict`() {
        val person = signedUpAndApproved("member")
        val other = unique("other")
        signUp(other)
        val requestId = requestIdOf(login(other).accountId)
        assertEquals(403, mockMvc.get("/api/access-requests") { cookie(person.cookie) }.andReturn().response.status)
        assertEquals(403, approve(requestId, "viewer", person).response.status)
        assertEquals(204, approve(requestId, "viewer", owner).response.status)
        assertEquals(409, approve(requestId, "viewer", owner).response.status)
        assertEquals(409, decide(owner, "/api/access-requests/$requestId/reject").response.status)
    }

    @Test
    fun `a decision needs the CSRF token`() {
        val id = unique("csrf")
        signUp(id)
        val requestId = requestIdOf(login(id).accountId)
        val result = mockMvc.post("/api/access-requests/$requestId/approve") {
            cookie(owner.cookie)
            contentType = MediaType.APPLICATION_JSON
            content = """{"role":"viewer"}"""
        }.andReturn()
        assertEquals(403, result.response.status)
        assertEquals("PENDING", statusOf(requestId))
    }

    @Test
    fun `the owner role cannot be given by approval, an admin can`() {
        val id = unique("admin")
        signUp(id)
        val requestId = requestIdOf(login(id).accountId)
        assertEquals(403, approve(requestId, "owner", owner).response.status)
        assertEquals(400, approve(requestId, "king", owner).response.status)
        assertEquals(204, approve(requestId, "admin", owner).response.status)
    }

    @Test
    fun `a rejected request keeps the data closed`() {
        val id = unique("rejected")
        signUp(id)
        val person = login(id)
        assertEquals(204, decide(owner, "/api/access-requests/${requestIdOf(person.accountId)}/reject").response.status)
        mockMvc.get("/api/drivers") { cookie(person.cookie) }.andExpect {
            status { isForbidden() }
            jsonPath("$.detail") { value("membership-rejected") }
        }
    }

    @Test
    fun `revoking closes the data at the next request, and the owner and self cannot be revoked`() {
        val person = signedUpAndApproved("viewer")
        mockMvc.get("/api/drivers") { cookie(person.cookie) }.andExpect { status { isOk() } }
        assertEquals(204, decide(owner, "/api/memberships/${person.accountId}/revoke").response.status)
        mockMvc.get("/api/drivers") { cookie(person.cookie) }.andExpect {
            status { isForbidden() }
            jsonPath("$.detail") { value("membership-revoked") }
        }
        assertEquals(403, decide(owner, "/api/memberships/${owner.accountId}/revoke").response.status)
        assertEquals(404, decide(owner, "/api/memberships/${person.accountId}/revoke").response.status)
    }

    @Test
    fun `a request past 14 days expires, the person may ask again, and old personal data is cleared`() {
        val id = unique("late")
        signUp(id)
        val person = login(id)
        val requestId = requestIdOf(person.accountId)
        jdbc.sql(
            "UPDATE access_request SET expires_at = TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00:00' " +
                "WHERE request_id = :r",
        ).param("r", requestId).update()
        // Listing runs the housekeeping.
        mockMvc.get("/api/access-requests") { cookie(owner.cookie) }.andExpect { status { isOk() } }
        assertEquals("EXPIRED", statusOf(requestId))
        mockMvc.get("/api/me") { cookie(person.cookie) }.andExpect { jsonPath("$.membership") { value("none") } }
        assertEquals(409, approve(requestId, "viewer", owner).response.status)
        // Long after: the name, driver ID and phone are gone, the row stays.
        val name = jdbc.sql("SELECT full_name FROM access_request WHERE request_id = :r").param("r", requestId)
            .query(String::class.java).optional().orElse(null)
        assertNull(name, "personal data of a request expired in 2000 must be cleared")
    }

    @Test
    fun `the pending list shows what the owner decides on`() {
        val id = unique("listed")
        signUp(id)
        val body = mockMvc.get("/api/access-requests") { cookie(owner.cookie) }.andReturn().response.contentAsString
        assertTrue(body.contains(id), "the new request is in the list")
        assertTrue(body.contains("\"phoneVerified\":false"))
        assertTrue(body.contains("KRHWN"))
        assertFalse(body.contains(password))
        assertNotNull(body)
    }

    private fun profileBody(center: String = "krsel", terms: Boolean = true, phone: String = "010-9999-8888") =
        """{"fullName":"Kim Again","centerCode":"$center","driverId":"D-200","phone":"$phone","termsAccepted":$terms}"""

    private fun requestAgain(session: Session, body: String = profileBody()): MvcResult =
        mockMvc.post("/api/access-requests") {
            cookie(session.cookie)
            header("X-CSRF-Token", session.csrf)
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andReturn()

    private fun centersOf(accountId: String): List<String> =
        jdbc.sql("SELECT center_code FROM account_center WHERE account_id = :a ORDER BY center_code")
            .param("a", accountId).query(String::class.java).list().filterNotNull()

    @Test
    fun `the owner lists active members with their centers, and others cannot`() {
        val person = signedUpAndApproved("member")
        val body = mockMvc.get("/api/memberships") { cookie(owner.cookie) }.andReturn().response
        assertEquals(200, body.status)
        assertTrue(body.contentAsString.contains(person.accountId), "the approved member is listed")
        assertTrue(body.contentAsString.contains("\"centerCodes\":[\"KRHWN\"]"))
        assertFalse(body.contentAsString.contains("PENDING"), "waiting accounts are not members")
        assertEquals(403, mockMvc.get("/api/memberships") { cookie(person.cookie) }.andReturn().response.status)
    }

    @Test
    fun `revoking clears the centers, so a later approval cannot bring an old one back`() {
        val person = signedUpAndApproved("member")
        assertEquals(listOf("KRHWN"), centersOf(person.accountId))
        assertEquals(204, decide(owner, "/api/memberships/${person.accountId}/revoke").response.status)
        assertEquals(emptyList<String>(), centersOf(person.accountId))

        // The person asks again for another center; the owner approves that one.
        assertEquals(201, requestAgain(person, profileBody("krsel")).response.status)
        val requestId = requestIdOf(person.accountId)
        assertEquals(204, approve(requestId, "viewer").response.status)
        assertEquals(listOf("KRSEL"), centersOf(person.accountId), "only the newly approved center")
        mockMvc.get("/api/centers") { cookie(person.cookie) }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value("KRSEL") }
        }
    }

    @Test
    fun `an approval replaces the centers instead of adding to them`() {
        val person = signedUpAndApproved("member")
        jdbc.sql(
            "INSERT INTO account_center (account_id, center_code, granted_at) VALUES (:a, 'OLDONE', CURRENT_TIMESTAMP)",
        ).param("a", person.accountId).update()
        assertEquals(204, decide(owner, "/api/memberships/${person.accountId}/revoke").response.status)
        jdbc.sql(
            "INSERT INTO account_center (account_id, center_code, granted_at) VALUES (:a, 'STALE', CURRENT_TIMESTAMP)",
        ).param("a", person.accountId).update()
        assertEquals(201, requestAgain(person).response.status)
        assertEquals(204, approve(requestIdOf(person.accountId), "member").response.status)
        assertEquals(listOf("KRSEL"), centersOf(person.accountId))
    }

    @Test
    fun `a rejected, revoked or expired account may ask again, a waiting or active one may not`() {
        // Rejected.
        val rejectedId = unique("again")
        signUp(rejectedId)
        val rejected = login(rejectedId)
        assertEquals(409, requestAgain(rejected).response.status, "a waiting request blocks another")
        val rejection = decide(owner, "/api/access-requests/${requestIdOf(rejected.accountId)}/reject")
        assertEquals(204, rejection.response.status)
        assertEquals(201, requestAgain(rejected).response.status)
        mockMvc.get("/api/me") { cookie(rejected.cookie) }.andExpect { jsonPath("$.membership") { value("pending") } }

        // Active.
        val active = signedUpAndApproved("viewer")
        assertEquals(409, requestAgain(active).response.status)
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM access_request WHERE account_id = :a AND status = 'PENDING'")
            .param("a", rejected.accountId).query(Int::class.java).single())
    }

    @Test
    fun `asking again needs a session, the CSRF token and valid fields, and gives no access`() {
        val id = unique("fields")
        signUp(id)
        val person = login(id)
        assertEquals(204, decide(owner, "/api/access-requests/${requestIdOf(person.accountId)}/reject").response.status)

        assertEquals(401, mockMvc.post("/api/access-requests") {
            contentType = MediaType.APPLICATION_JSON
            content = profileBody()
        }.andReturn().response.status)
        assertEquals(403, mockMvc.post("/api/access-requests") {
            cookie(person.cookie)
            contentType = MediaType.APPLICATION_JSON
            content = profileBody()
        }.andReturn().response.status, "no CSRF token")
        assertEquals(400, requestAgain(person, profileBody(phone = "abc")).response.status)
        assertEquals(400, requestAgain(person, profileBody(phone = "02-123-4567")).response.status)
        assertEquals(201, requestAgain(person).response.status)
        // Still no data: the owner has not decided.
        mockMvc.get("/api/drivers") { cookie(person.cookie) }.andExpect {
            status { isForbidden() }
            jsonPath("$.detail") { value("membership-pending") }
        }
    }

    @Test
    fun `an account whose request expired may ask again`() {
        val id = unique("expired")
        signUp(id)
        val person = login(id)
        val requestId = requestIdOf(person.accountId)
        jdbc.sql(
            "UPDATE access_request SET expires_at = TIMESTAMP WITH TIME ZONE '2000-01-01 00:00:00+00:00' " +
                "WHERE request_id = :r",
        ).param("r", requestId).update()
        mockMvc.get("/api/access-requests") { cookie(owner.cookie) }.andExpect { status { isOk() } } // housekeeping
        mockMvc.get("/api/me") { cookie(person.cookie) }.andExpect { jsonPath("$.membership") { value("none") } }
        assertEquals(201, requestAgain(person).response.status)
        assertNotNull(requestIdOf(person.accountId))
    }

    private fun newInvite(center: String = "KRSNA", role: String = "member"): String {
        val made = decide(owner, "/api/center-invites", """{"centerCodes":["$center"],"role":"$role"}""")
        assertEquals(201, made.response.status)
        return Regex("\"code\":\"([A-Z0-9-]+)\"").find(made.response.contentAsString)!!.groupValues[1]
    }

    private fun redeem(session: Session, code: String): MvcResult =
        decide(session, "/api/center-invites/redeem", """{"code":"$code"}""")

    @Test
    fun `a center code makes a waiting account active with that center and works once`() {
        val id = unique("coded")
        assertEquals(201, signUp(id).response.status)
        val person = login(id)
        val code = newInvite("krsna", "viewer")
        assertEquals(12 + 2, code.length, "XXXX-XXXX-XXXX")
        assertEquals(200, redeem(person, code.lowercase()).response.status)
        mockMvc.get("/api/me") { cookie(person.cookie) }.andExpect {
            jsonPath("$.role") { value("viewer") }
            jsonPath("$.membership") { value("active") }
        }
        assertEquals(listOf("KRSNA"), centersOf(person.accountId))
        assertEquals("APPROVED", statusOf(requestIdOf(person.accountId)))
        assertEquals(400, redeem(person, code).response.status, "a code is used once")
        val stored = jdbc.sql("SELECT COUNT(*) FROM center_invite WHERE code_hash LIKE :c")
            .param("c", "%" + code.replace("-", "") + "%").query(Int::class.java).single()
        assertEquals(0, stored, "the code itself is never stored")
    }

    @Test
    fun `one code can carry several centers`() {
        val id = unique("multi")
        assertEquals(201, signUp(id).response.status)
        val person = login(id)
        val made = decide(owner, "/api/center-invites", """{"centerCodes":["krsna","KRHWN","KRCGW"],"role":"member"}""")
        assertEquals(201, made.response.status)
        val code = Regex("\"code\":\"([A-Z0-9-]+)\"").find(made.response.contentAsString)!!.groupValues[1]
        assertEquals(200, redeem(person, code).response.status)
        assertEquals(listOf("KRCGW", "KRHWN", "KRSNA"), centersOf(person.accountId))
        assertEquals(
            400,
            decide(owner, "/api/center-invites", """{"centerCodes":[],"role":"member"}""").response.status,
        )
    }

    @Test
    fun `an active member who types another code gets the center and keeps the role`() {
        val person = signedUpAndApproved("viewer")
        assertEquals(200, redeem(person, newInvite("KRSNA", "member")).response.status)
        assertEquals(listOf("KRHWN", "KRSNA"), centersOf(person.accountId).sorted())
        mockMvc.get("/api/me") { cookie(person.cookie) }.andExpect { jsonPath("$.role") { value("viewer") } }
    }

    @Test
    fun `only the owner makes codes, and only for member or viewer`() {
        val person = signedUpAndApproved("admin")
        fun make(by: Session, center: String, role: String) =
            decide(by, "/api/center-invites", """{"centerCodes":["$center"],"role":"$role"}""").response.status
        assertEquals(403, make(person, "KRSNA", "member"))
        assertEquals(400, make(owner, "KRSNA", "admin"))
        assertEquals(400, make(owner, "KRSNA", "owner"))
        assertEquals(400, make(owner, "x", "member"))
        val noToken = mockMvc.post("/api/center-invites") {
            cookie(owner.cookie)
            contentType = MediaType.APPLICATION_JSON
            content = """{"centerCodes":["KRSNA"],"role":"member"}"""
        }.andReturn()
        assertEquals(403, noToken.response.status)
    }

    @Test
    fun `wrong and expired codes are refused alike, and repeated wrong codes lock`() {
        val id = unique("guess")
        assertEquals(201, signUp(id).response.status)
        val person = login(id)
        val expired = newInvite()
        jdbc.sql("UPDATE center_invite SET expires_at = :t")
            .param("t", java.time.OffsetDateTime.now().minusDays(1)).update()
        assertEquals(400, redeem(person, expired).response.status)
        repeat(4) { assertEquals(400, redeem(person, "AAAA-AAAA-AAAA").response.status) }
        assertEquals(429, redeem(person, "AAAA-AAAA-AAAA").response.status)
        val fresh = newInvite()
        assertEquals(429, redeem(person, fresh).response.status, "the lock holds even for a good code")
        assertEquals(listOf<String>(), centersOf(person.accountId))
    }

    private fun typeKorean(by: Session, key: String, body: String = """{"addressKo":"경기도 성남시 수정구 1"}"""): MvcResult =
        mockMvc.put("/api/address-ko/$key") {
            cookie(by.cookie)
            header("X-CSRF-Token", by.csrf)
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andReturn()

    private fun addressRow(): String {
        val key = UUID.randomUUID().toString().replace("-", "").repeat(2)
        jdbc.sql("INSERT INTO address_location (address_key, address1) VALUES (:k, '1 Road')").param("k", key).update()
        return key
    }

    @Test
    fun `only an administrator or the owner may type a Korean address`() {
        val key = addressRow()
        assertEquals(403, typeKorean(signedUpAndApproved("member"), key).response.status)
        assertEquals(403, typeKorean(signedUpAndApproved("viewer"), key).response.status)
        val saved = typeKorean(signedUpAndApproved("admin"), key)
        assertEquals(200, saved.response.status)
        assertTrue(saved.response.contentAsString.contains("\"UNAVAILABLE\""), "no geocoder is set up in tests")
        assertEquals(200, typeKorean(owner, key, """{"addressKo":"  경기도   성남시 "}""").response.status)
        val row = jdbc.sql("SELECT address_ko, ko_source, status FROM address_location WHERE address_key = :k")
            .param("k", key).query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.single()
        assertEquals(Triple("경기도 성남시", "MANUAL", "PENDING"), row)
    }

    @Test
    fun `a typed Korean address is checked`() {
        val key = addressRow()
        assertEquals(400, typeKorean(owner, key, """{"addressKo":"   "}""").response.status)
        assertEquals(400, typeKorean(owner, "not-a-key").response.status)
        assertEquals(404, typeKorean(owner, "0".repeat(64)).response.status)
    }

    @Test
    fun `the owner ties a member to a driver, who then reaches only the driver route`() {
        val center = "KRDRV" + UUID.randomUUID().toString().take(4).uppercase()
        jdbc.sql("INSERT INTO center (center_code) VALUES (:c)").param("c", center).update()
        jdbc.sql("INSERT INTO driver (driver_id, center_code, driver_code) VALUES (:id, :c, '7001')")
            .param("id", UUID.randomUUID().toString()).param("c", center).update()
        val person = signedUpAndApproved("member")
        val link = { body: String, by: Session -> decide(by, "/api/memberships/${person.accountId}/driver", body) }
        assertEquals(400, link("""{"centerId":"$center","driverId":"9999"}""", owner).response.status)
        assertEquals(403, link("""{"centerId":"$center","driverId":"7001"}""", person).response.status)
        assertEquals(204, link("""{"centerId":"$center","driverId":"7001"}""", owner).response.status)
        mockMvc.get("/api/me") { cookie(person.cookie) }.andExpect { jsonPath("$.role") { value("driver") } }
        mockMvc.get("/api/orders") { cookie(person.cookie) }.andExpect { status { isForbidden() } }
        mockMvc.get("/api/driver/route") { cookie(person.cookie) }.andExpect { status { isNotFound() } }
        mockMvc.get("/api/driver/route") { cookie(owner.cookie) }.andExpect { status { isForbidden() } }
        val selfPath = "/api/memberships/${owner.accountId}/driver"
        assertEquals(409, decide(owner, selfPath, """{"centerId":"$center","driverId":"7001"}""").response.status)
        // A shared PostgreSQL (UPS_TEST_DB_URL) serves the other tests too: leave no tie to their drivers or accounts.
        jdbc.sql("DELETE FROM driver_account WHERE account_id = :a").param("a", person.accountId).update()
    }

    private fun editOrders(by: Session, body: String): MvcResult =
        mockMvc.put("/api/orders/edits") {
            cookie(by.cookie)
            header("X-CSRF-Token", by.csrf)
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andReturn()

    /** An order row read from Ours24, of a Job given to [center]. */
    private fun taskOf(center: String): String {
        val key = addressRow()
        val job = unique("job")
        val known = jdbc.sql("SELECT COUNT(*) FROM center WHERE center_code = :c").param("c", center)
            .query(Int::class.java).single()
        if (known == 0) jdbc.sql("INSERT INTO center (center_code) VALUES (:c)").param("c", center).update()
        jdbc.sql("INSERT INTO job_center (job_id, center_code, method) VALUES (:j, :c, 'MANUAL')")
            .param("j", job).param("c", center).update()
        jdbc.sql(
            "INSERT INTO delivery_task (task_key, job_id, row_no, tracking_number, address_key) " +
                "VALUES (:t, :j, 1, :t, :k)",
        ).param("t", "$job:1").param("j", job).param("k", key).update()
        return "$job:1"
    }

    @Test
    fun `an administrator corrects orders of own centers only, and an empty value gives the cell back`() {
        val mine = taskOf("KRHWN")
        val other = taskOf("KRSEL")
        val body = { id: String, value: String? ->
            val text = if (value == null) "null" else "\"$value\""
            """{"day":"2026-10-15","edits":[{"orderId":"$id","field":"customerName","value":$text}]}"""
        }
        assertEquals(403, editOrders(signedUpAndApproved("member"), body(mine, "Kim")).response.status)
        val admin = signedUpAndApproved("admin")
        assertEquals(204, editOrders(admin, body(mine, " Kim Fixed ")).response.status)
        assertEquals(404, editOrders(admin, body(other, "Lee")).response.status, "KRSEL is not the admin's")
        assertEquals(404, editOrders(admin, body("never-read:1", "Lee")).response.status)
        val unknownField = """{"day":"2026-10-15","edits":[{"orderId":"$mine","field":"status","value":"DONE"}]}"""
        assertEquals(400, editOrders(admin, unknownField).response.status)
        val saved = jdbc.sql("SELECT cell_value, edited_by FROM order_edit WHERE task_key = :t").param("t", mine)
            .query { rs, _ -> rs.getString(1) to rs.getString(2).trim() }.single()
        assertEquals("Kim Fixed" to admin.accountId, saved)
        assertEquals(204, editOrders(admin, body(mine, null)).response.status)
        val left = jdbc.sql("SELECT COUNT(*) FROM order_edit WHERE task_key = :t").param("t", mine)
            .query(Int::class.java).single()
        assertEquals(0, left)
        jdbc.sql("DELETE FROM delivery_task WHERE task_key IN (:t)").param("t", listOf(mine, other)).update()
    }

    private fun send(by: Session, method: String, path: String, body: String): MvcResult =
        mockMvc.perform(
            MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path)
                .cookie(by.cookie).header("X-CSRF-Token", by.csrf)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        ).andReturn()

    @Test
    fun `an administrator makes zones of own centers only, and members only read them`() {
        taskOf("KRHWN")
        taskOf("KRSEL")
        val name = unique("zone")
        val member = signedUpAndApproved("member")
        assertEquals(403, send(member, "POST", "/api/zones", """{"centerId":"KRHWN","name":"$name"}""").response.status)
        val admin = signedUpAndApproved("admin")
        val made = send(admin, "POST", "/api/zones", """{"centerId":"KRHWN","name":"$name"}""")
        assertEquals(200, made.response.status)
        assertEquals(403, send(admin, "POST", "/api/zones", """{"centerId":"KRSEL","name":"$name"}""").response.status)
        val id = made.response.contentAsString.substringAfter("\"id\":\"").substringBefore('"')
        mockMvc.get("/api/zones?centerId=KRHWN") { cookie(member.cookie) }.andExpect {
            status { isOk() }
            jsonPath("$[?(@.name == '$name')].id") { value(id) }
        }
        val other = UUID.randomUUID().toString()
        jdbc.sql("INSERT INTO zone (zone_id, center_code, name) VALUES (:z, 'KRSEL', :n)").param("z", other)
            .param("n", name).update()
        assertEquals(404, send(admin, "PUT", "/api/zones/$other/shape", "{}").response.status)
        assertEquals(200, send(admin, "PUT", "/api/zones/$id/shape", "{}").response.status, "no input clears it")
        jdbc.sql("DELETE FROM zone WHERE zone_id IN (:z)").param("z", listOf(id, other)).update()
    }

    @Test
    fun `only the owner makes centers, which then show for the owner, and administrators register drivers`() {
        val code = "Z" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        val body = """{"id":"${code.lowercase()}","name":"테스트 센터","depotAddress":"경기도 성남시 분당구 1"}"""
        val admin = signedUpAndApproved("admin")
        assertEquals(403, send(admin, "POST", "/api/centers", body).response.status)
        assertEquals(200, send(owner, "POST", "/api/centers", body).response.status)
        assertEquals(409, send(owner, "POST", "/api/centers", body).response.status)
        assertEquals(400, send(owner, "POST", "/api/centers", """{"id":"x","name":""}""").response.status)
        mockMvc.get("/api/centers") { cookie(owner.cookie) }
            .andExpect { jsonPath("$[?(@.id == '$code')]") { exists() } }
        val renamed = """{"name":"새 이름","depotAddress":""}"""
        assertEquals(200, send(owner, "PUT", "/api/centers/$code", renamed).response.status)
        assertEquals(404, send(owner, "PUT", "/api/centers/NOPE404", renamed).response.status)
        // The admin has KRHWN only: a driver goes there, not into the new center.
        taskOf("KRHWN")
        val driver = unique("D")
        val add = { center: String, id: String ->
            send(admin, "POST", "/api/drivers", """{"centerId":"$center","driverId":"$id"}""").response.status
        }
        assertEquals(200, add("krhwn", driver))
        assertEquals(409, add("KRHWN", driver))
        assertEquals(403, add(code, driver))
        assertEquals(400, add("KRHWN", "a b"))
        jdbc.sql("DELETE FROM driver WHERE driver_code = :d").param("d", driver).update()
        jdbc.sql("DELETE FROM center WHERE center_code = :c").param("c", code).update()
    }

    @Test
    fun `the owner cannot use a code`() {
        assertEquals(409, redeem(owner, newInvite()).response.status)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun settings(registry: DynamicPropertyRegistry) {
            val dir = Files.createTempDirectory("access-flow")
            registry.add("ups.service-token-file") { writeKeyFile(dir, "service-token").toString() }
            registry.add("ups.signup.code-required") { "false" }
            registry.add("ups.db.url") {
                System.getenv("UPS_TEST_DB_URL")
                    ?: "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            }
            System.getenv("UPS_TEST_DB_USER")?.let { user -> registry.add("ups.db.user") { user } }
        }
    }
}
