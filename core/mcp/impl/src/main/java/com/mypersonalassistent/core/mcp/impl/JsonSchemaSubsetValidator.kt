package com.mypersonalassistent.core.mcp.impl

import kotlinx.serialization.json.*

/** Fail-closed schema subset used for MCP tool input and structured output. */
internal object JsonSchemaSubsetValidator {
    private val allowed = setOf("type", "properties", "required", "additionalProperties", "items", "enum", "const", "minLength", "maxLength", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "minItems", "maxItems", "title", "description", "\$comment", "default", "examples", "deprecated", "readOnly", "writeOnly", "\$schema")
    private val types = setOf("object", "array", "string", "number", "integer", "boolean", "null")
    fun isSupported(schema: JsonElement?, input: Boolean): Boolean {
        if (schema == null) return !input
        var nodes = 0
        fun bounded(value: JsonElement, depth: Int): Boolean {
            if (++nodes > 1024 || depth > 16) return false
            return when (value) {
                is JsonObject -> value.values.all { bounded(it, depth + 1) }
                is JsonArray -> value.all { bounded(it, depth + 1) }
                else -> true
            }
        }
        if (!bounded(schema, 0)) return false
        fun visit(value: JsonElement, depth: Int, root: Boolean): Boolean {
            if (depth > 16) return false
            val obj = value as? JsonObject ?: return false
            val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (type !in types || obj.keys.any { it !in allowed }) return false
            if (root && (input && type != "object" || !input && type !in setOf("object", "array"))) return false
            if (obj["\$schema"] != null && (!root || (obj["\$schema"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull !in setOf("https://json-schema.org/draft/2020-12/schema", "http://json-schema.org/draft/2020-12/schema"))) return false
            if (obj["additionalProperties"] != null && !obj["additionalProperties"].isJsonBoolean(false)) return false
            val props = obj["properties"] as? JsonObject
            if (obj["properties"] != null && (props == null || props.values.any { !visit(it, depth + 1, false) })) return false
            val required = obj["required"] as? JsonArray
            if (obj["required"] != null && (required == null || required.any { it !is JsonPrimitive || !it.isString } || required.distinct().size != required.size)) return false
            if (obj["items"] != null && !visit(obj["items"]!!, depth + 1, false)) return false
            val enum = obj["enum"] as? JsonArray
            if (obj["enum"] != null && (enum == null || enum.isEmpty() || enum.distinct().size != enum.size)) return false
            if (!validMetadata(obj)) return false
            if (!obj.hasValidNonNegativeInt("minLength") || !obj.hasValidNonNegativeInt("maxLength") || !obj.hasValidNonNegativeInt("minItems") || !obj.hasValidNonNegativeInt("maxItems")) return false
            val minLength = (obj["minLength"] as? JsonPrimitive)?.intOrNull
            val maxLength = (obj["maxLength"] as? JsonPrimitive)?.intOrNull
            val minItems = (obj["minItems"] as? JsonPrimitive)?.intOrNull
            val maxItems = (obj["maxItems"] as? JsonPrimitive)?.intOrNull
            if (minLength != null && maxLength != null && minLength > maxLength) return false
            if (minItems != null && maxItems != null && minItems > maxItems) return false
            if (!obj.hasValidFiniteNumber("minimum") || !obj.hasValidFiniteNumber("maximum") || !obj.hasValidFiniteNumber("exclusiveMinimum") || !obj.hasValidFiniteNumber("exclusiveMaximum")) return false
            val minimum = (obj["minimum"] as? JsonPrimitive)?.doubleOrNull
            val maximum = (obj["maximum"] as? JsonPrimitive)?.doubleOrNull
            val exclusiveMinimum = (obj["exclusiveMinimum"] as? JsonPrimitive)?.doubleOrNull
            val exclusiveMaximum = (obj["exclusiveMaximum"] as? JsonPrimitive)?.doubleOrNull
            if (minimum != null && maximum != null && minimum > maximum) return false
            if (exclusiveMinimum != null && exclusiveMaximum != null && exclusiveMinimum >= exclusiveMaximum) return false
            if (minimum != null && exclusiveMaximum != null && minimum >= exclusiveMaximum) return false
            if (exclusiveMinimum != null && maximum != null && exclusiveMinimum >= maximum) return false
            return true
        }
        return visit(schema, 0, true)
    }
    fun validateArguments(schema: JsonElement, value: JsonElement): Boolean {
        val objectSchema = schema as? JsonObject ?: return false
        return validate(objectSchema, value)
    }
    fun validateOutput(schema: JsonElement?, value: JsonElement): Boolean {
        if (schema == null) return true
        val objectSchema = schema as? JsonObject ?: return false
        return validate(objectSchema, value)
    }
    private fun validate(schema: JsonObject, value: JsonElement): Boolean {
        schema["const"]?.let { if (it != value) return false }; (schema["enum"] as? JsonArray)?.let { if (value !in it) return false }
        when ((schema["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull) {
            "object" -> {
                val objectValue = value as? JsonObject ?: return false
                val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull }.orEmpty()
                if (required.any { it !in objectValue }) return false
                if ((schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false && objectValue.keys.any { it !in properties }) return false
                for ((key, childValue) in objectValue) {
                    val childSchema = properties[key] ?: continue
                    if (!validate(childSchema as? JsonObject ?: return false, childValue)) return false
                }
            }
            "array" -> {
                val arrayValue = value as? JsonArray ?: return false
                (schema["minItems"] as? JsonPrimitive)?.intOrNull?.let { if (arrayValue.size < it) return false }
                (schema["maxItems"] as? JsonPrimitive)?.intOrNull?.let { if (arrayValue.size > it) return false }
                schema["items"]?.let { child ->
                    val childSchema = child as? JsonObject ?: return false
                    if (arrayValue.any { !validate(childSchema, it) }) return false
                }
            }
            "string" -> {
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
                val length = text.codePointCount(0, text.length)
                (schema["minLength"] as? JsonPrimitive)?.intOrNull?.let { if (length < it) return false }
                (schema["maxLength"] as? JsonPrimitive)?.intOrNull?.let { if (length > it) return false }
            }
            "number" -> { val primitive = value as? JsonPrimitive ?: return false; if (primitive.isString) return false; val n = primitive.doubleOrNull ?: return false; if (!n.isFinite() || !numericBoundsAllow(schema, n)) return false }
            "integer" -> { val primitive = value as? JsonPrimitive ?: return false; if (primitive.isString) return false; val n = primitive.doubleOrNull ?: return false; if (!n.isFinite() || n % 1 != 0.0 || !numericBoundsAllow(schema, n)) return false }
            "boolean" -> { val primitive = value as? JsonPrimitive ?: return false; if (primitive.isString || primitive.booleanOrNull == null) return false }
            "null" -> if (value !is JsonNull) return false
            else -> return false
        }
        return true
    }

    private fun JsonObject.hasValidNonNegativeInt(name: String): Boolean = this[name]?.let { value ->
        val primitive = value as? JsonPrimitive ?: return@let false
        !primitive.isString && primitive.intOrNull?.let { it >= 0 } == true
    } ?: true
    private fun JsonObject.hasValidFiniteNumber(name: String): Boolean = this[name]?.let { value ->
        val primitive = value as? JsonPrimitive ?: return@let false
        !primitive.isString && primitive.doubleOrNull?.isFinite() == true
    } ?: true
    private fun validMetadata(schema: JsonObject): Boolean =
        listOf("title", "description", "\$comment").all { schema[it] == null || (schema[it] as? JsonPrimitive)?.isString == true } &&
            listOf("deprecated", "readOnly", "writeOnly").all { schema[it] == null || schema[it].isJsonBoolean() } &&
            (schema["examples"] == null || schema["examples"] is JsonArray)
    private fun numericBoundsAllow(schema: JsonObject, value: Double): Boolean =
        ((schema["minimum"] as? JsonPrimitive)?.doubleOrNull?.let { value >= it } ?: true) &&
            ((schema["maximum"] as? JsonPrimitive)?.doubleOrNull?.let { value <= it } ?: true) &&
            ((schema["exclusiveMinimum"] as? JsonPrimitive)?.doubleOrNull?.let { value > it } ?: true) &&
            ((schema["exclusiveMaximum"] as? JsonPrimitive)?.doubleOrNull?.let { value < it } ?: true)

    private fun JsonElement?.isJsonBoolean(expected: Boolean? = null): Boolean {
        val primitive = this as? JsonPrimitive ?: return false
        if (primitive.isString) return false
        val value = primitive.booleanOrNull ?: return false
        return expected == null || value == expected
    }
}
