package com.evnsolution.ups

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import java.io.File

/** Reads contract/openapi.yaml. Shared by the contract tests. */
object Contract {
    private val spec: JsonNode = ObjectMapper(YAMLFactory()).readTree(File("../../contract/openapi.yaml"))

    private fun usesServiceToken(path: JsonNode) = path["get"]["security"]?.any { it.has("serviceToken") } == true

    /** GET paths a person or anyone may call. Paths that need the service token are in [servicePaths]. */
    /** Paths marked `x-requires-database` exist only on a run with a database; their own tests cover them. */
    private fun needsDatabase(path: JsonNode) = path["get"]["x-requires-database"]?.asBoolean() == true

    val getPaths: List<String> = spec["paths"].properties()
        .filter { it.value.has("get") && !usesServiceToken(it.value) && !needsDatabase(it.value) }.map { it.key }

    val servicePaths: List<String> = spec["paths"].properties()
        .filter { it.value.has("get") && usesServiceToken(it.value) }.map { it.key }

    /** Schema of the 200 JSON response of GET [path], with the shared components attached. */
    fun responseSchema(path: String): JsonSchema {
        val doc = spec["paths"][path]["get"]["responses"]["200"]["content"]["application/json"]["schema"]
            .deepCopy<ObjectNode>()
        doc.set<JsonNode>("components", spec["components"])
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(doc)
    }
}
