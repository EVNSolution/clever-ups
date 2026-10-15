package com.evnsolution.ups.partner

import com.evnsolution.ups.SignedIn
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.nio.file.Files

/** With ours24.key-file set, /api/orders reads from Ours24. Here the "Ours24" is a local fake. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SignedIn::class)
class Ours24WiringTest(@param:Autowired private val mockMvc: MockMvc) {
    @BeforeEach
    fun clear() = fake.seen.clear()

    @Test
    fun `orders come from Ours24 with the key from the file`() {
        fake.json("/jobs", """{"jobs":[{"id":"J1"}],"total":1,"page":1,"limit":100,"totalPages":1}""")
        fake.json(
            "/jobs/J1/tasks",
            """{"tasks":[{"rowNo":1,"trackingNumber":"TRK1"}],"total":1,"page":1,"totalPages":1}""",
        )
        mockMvc.get("/api/orders?from=2026-10-07&to=2026-10-07").andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value("J1:1") }
            jsonPath("$[0].trackingNumber") { value("TRK1") }
            header { doesNotExist("X-Data-Incomplete") }
        }
        assertEquals(setOf("wiring-key"), fake.seen.map { it.key }.toSet())
    }

    @Test
    fun `a failing Ours24 is a 502 that passes nothing on`() {
        fake.json("/jobs", """{"secret":"upstream-secret"}""", status = 500)
        mockMvc.get("/api/orders?from=2026-10-07").andExpect {
            status { isBadGateway() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.detail") { value("Ours24 연동에 실패했습니다.") }
            content { string(not(containsString("upstream-secret"))) }
        }
    }

    companion object {
        private val fake = FakeOurs24()
        private val keyFile = writeKeyFile(Files.createTempDirectory("ours24-wiring"), "wiring-key")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("ours24.base-url") { fake.url }
            registry.add("ours24.key-file") { keyFile.toString() }
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            fake.close()
            Files.deleteIfExists(keyFile)
            Files.deleteIfExists(keyFile.parent)
        }
    }
}
