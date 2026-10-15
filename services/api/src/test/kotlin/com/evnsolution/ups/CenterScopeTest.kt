package com.evnsolution.ups

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** The centerId query reaches the store of every list, and is optional. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FilledStores::class)
class CenterScopeTest(@param:Autowired private val mockMvc: MockMvc) {
    @Test
    fun `centerId reaches the stores`() {
        val prefixes = mapOf("/api/drivers" to "d", "/api/routes" to "r")
        for ((path, prefix) in prefixes) {
            mockMvc.get("$path?centerId=c2").andExpect { jsonPath("$[0].id") { value("$prefix-c2") } }
            mockMvc.get(path).andExpect { jsonPath("$[0].id") { value("$prefix-null") } }
        }
    }
}
