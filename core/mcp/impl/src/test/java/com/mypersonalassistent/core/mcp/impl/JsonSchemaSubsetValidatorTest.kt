package com.mypersonalassistent.core.mcp.impl

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonSchemaSubsetValidatorTest {
    private val json = Json

    @Test fun `accepts the bounded object schema and validates its values`() {
        val schema = json.parseToJsonElement("""{"type":"object","properties":{"query":{"type":"string","minLength":2,"maxLength":4},"count":{"type":"integer","minimum":1,"maximum":3}},"required":["query"],"additionalProperties":false}""")

        assertTrue(JsonSchemaSubsetValidator.isSupported(schema, input = true))
        assertTrue(JsonSchemaSubsetValidator.validateArguments(schema, json.parseToJsonElement("""{"query":"ok","count":2}""")))
        assertFalse(JsonSchemaSubsetValidator.validateArguments(schema, json.parseToJsonElement("""{"query":"x"}""")))
        assertFalse(JsonSchemaSubsetValidator.validateArguments(schema, json.parseToJsonElement("""{"query":"good","count":4}""")))
        assertFalse(JsonSchemaSubsetValidator.validateArguments(schema, json.parseToJsonElement("""{"query":"ok","extra":true}""")))
    }

    @Test fun `rejects unsupported keywords malformed constraints and inconsistent ranges`() {
        assertFalse(JsonSchemaSubsetValidator.isSupported(json.parseToJsonElement("""{"type":"object","patternProperties":{}}"""), input = true))
        assertFalse(JsonSchemaSubsetValidator.isSupported(json.parseToJsonElement("""{"type":"object","minItems":-1}"""), input = true))
        assertFalse(JsonSchemaSubsetValidator.isSupported(json.parseToJsonElement("""{"type":"string","minLength":4,"maxLength":2}"""), input = false))
        assertFalse(JsonSchemaSubsetValidator.isSupported(json.parseToJsonElement("""{"type":"number","exclusiveMinimum":3,"maximum":3}"""), input = false))
        assertFalse(JsonSchemaSubsetValidator.isSupported(json.parseToJsonElement("""{"type":"object","required":["a","a"]}"""), input = true))
    }

    @Test fun `accepts only the approved metadata and draft dialect`() {
        assertTrue(JsonSchemaSubsetValidator.isSupported(json.parseToJsonElement("""{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","type":"object","title":"Title","description":"Untrusted","${'$'}comment":"note","default":{},"examples":[{}],"deprecated":false,"readOnly":true,"writeOnly":false}"""), input = true))
        assertFalse(JsonSchemaSubsetValidator.isSupported(json.parseToJsonElement("""{"${'$'}schema":"https://json-schema.org/draft/2019-09/schema","type":"object"}"""), input = true))
        assertFalse(JsonSchemaSubsetValidator.isSupported(json.parseToJsonElement("""{"type":"object","properties":{"value":{"type":"string","${'$'}schema":"https://json-schema.org/draft/2020-12/schema"}}}"""), input = true))
    }

    @Test fun `malformed primitive fields are rejected without throwing`() {
        listOf(
            """{"type":{}}""",
            """{"type":"object","title":{}}""",
            """{"type":"object","description":[]}""",
            """{"type":"object","${'$'}comment":{}}""",
            """{"type":"object","${'$'}schema":{}}""",
            """{"type":"object","minLength":{}}""",
            """{"type":"object","minimum":[]}""",
        ).forEach { malformed ->
            assertFalse(JsonSchemaSubsetValidator.isSupported(json.parseToJsonElement(malformed), input = true))
        }
    }

    @Test fun `rejects every representative unsupported schema family`() {
        listOf(
            "\"${'$'}ref\":\"#/defs/x\"",
            "\"allOf\":[]",
            "\"format\":\"uri\"",
            "\"multipleOf\":2",
            "\"uniqueItems\":true",
            "\"patternProperties\":{}",
            "\"contains\":{\"type\":\"string\"}",
        ).forEach { keyword ->
            assertFalse(keyword, JsonSchemaSubsetValidator.isSupported(json.parseToJsonElement("""{"type":"object",$keyword}"""), input = true))
        }
    }

    @Test fun `validates arrays integer semantics enum and const at runtime`() {
        val schema = json.parseToJsonElement("""{"type":"array","minItems":1,"maxItems":2,"items":{"type":"integer","enum":[1,2],"const":2}}""")

        assertTrue(JsonSchemaSubsetValidator.isSupported(schema, input = false))
        assertTrue(JsonSchemaSubsetValidator.validateOutput(schema, json.parseToJsonElement("[2]")))
        assertFalse(JsonSchemaSubsetValidator.validateOutput(schema, json.parseToJsonElement("[]")))
        assertFalse(JsonSchemaSubsetValidator.validateOutput(schema, json.parseToJsonElement("[2,2,2]")))
        assertFalse(JsonSchemaSubsetValidator.validateOutput(schema, json.parseToJsonElement("[1]")))
        assertFalse(JsonSchemaSubsetValidator.validateOutput(schema, json.parseToJsonElement("[2.5]")))
    }

    @Test fun `numeric and boolean strings never satisfy JSON typed constraints`() {
        assertFalse(JsonSchemaSubsetValidator.isSupported(json.parseToJsonElement("""{"type":"string","minLength":"1"}"""), input = false))
        assertFalse(JsonSchemaSubsetValidator.isSupported(json.parseToJsonElement("""{"type":"number","minimum":"1"}"""), input = false))
        assertFalse(JsonSchemaSubsetValidator.isSupported(json.parseToJsonElement("""{"type":"object","deprecated":"false"}"""), input = true))
        assertFalse(JsonSchemaSubsetValidator.validateOutput(json.parseToJsonElement("""{"type":"integer"}"""), json.parseToJsonElement("\"2\"")))
        assertFalse(JsonSchemaSubsetValidator.validateOutput(json.parseToJsonElement("""{"type":"boolean"}"""), json.parseToJsonElement("\"true\"")))
    }

    @Test fun `metadata values participate in depth and node bounds`() {
        val deeplyNestedDefault = "[".repeat(17) + "0" + "]".repeat(17)
        val manyExamples = (1..1025).joinToString(",") { "0" }

        assertFalse(JsonSchemaSubsetValidator.isSupported(json.parseToJsonElement("""{"type":"object","default":$deeplyNestedDefault}"""), input = true))
        assertFalse(JsonSchemaSubsetValidator.isSupported(json.parseToJsonElement("""{"type":"object","examples":[$manyExamples]}"""), input = true))
    }
}
