package com.evnsolution.ups

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * Calls every GET path in contract/openapi.yaml with test-only data and checks the 200 JSON body against its schema.
 * Lists must hold items, so item fields are checked too, not just the empty array.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FilledStores::class)
class ContractTest(@param:Autowired private val mockMvc: MockMvc) {
    @Test
    fun `GET responses match the contract`() {
        assertTrue(Contract.getPaths.isNotEmpty(), "contract has no GET path")
        for (path in Contract.getPaths) {
            val response = mockMvc.get(path).andReturn().response
            assertEquals(200, response.status, "GET $path did not return 200")
            val body = ObjectMapper().readTree(response.contentAsString)
            if (body.isArray) assertTrue(body.size() > 0, "GET $path returned no items")
            val errors = Contract.responseSchema(path).validate(body)
            assertEquals(emptySet<Any>(), errors.toSet(), "GET $path violates the contract")
        }
    }
}
