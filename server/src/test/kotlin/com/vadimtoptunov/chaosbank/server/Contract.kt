package com.vadimtoptunov.chaosbank.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.yaml.snakeyaml.Yaml

/**
 * `openapi.yaml`, loaded once, plus a small JSON Schema checker covering what the spec
 * uses: `$ref`, `type` (incl. `[string, "null"]`), `const`, `enum`, `pattern`, `required`,
 * `properties`, `additionalProperties`, `items`, `minimum` / `maximum`.
 */
object Contract {
    @Suppress("UNCHECKED_CAST")
    val spec: Map<String, Any?> =
        Yaml().load<Map<String, Any?>>(Contract::class.java.getResourceAsStream("/openapi.yaml")!!.reader())

    @Suppress("UNCHECKED_CAST")
    private val paths = spec["paths"] as Map<String, Map<String, Any?>>

    /** Every documented operation as "METHOD /template". */
    val operations: Set<String> = paths.flatMap { (path, item) ->
        item.keys.filter { it in setOf("get", "post", "put", "delete", "patch") }.map { "${it.uppercase()} $path" }
    }.toSet()

    /** The spec path template that [path] (no query) instantiates, e.g. /v1/accounts/EUR → /v1/accounts/{currency}. */
    fun template(path: String): String? = paths.keys.firstOrNull { t ->
        Regex("^" + t.replace(Regex("\\{[^}]+}"), "[^/]+") + "$").matches(path)
    }

    /**
     * Checks one response against the spec: the operation and its status are documented,
     * the content type matches, the body fits the schema. Returns the problems found.
     */
    @Suppress("UNCHECKED_CAST")
    fun check(method: String, path: String, status: Int, contentType: String?, body: JsonElement?): List<String> {
        val template = template(path.substringBefore('?')) ?: return listOf("$method $path: path not in openapi.yaml")
        val op = paths[template]!![method.lowercase()] as Map<String, Any?>?
            ?: return listOf("$method $template: method not in openapi.yaml")
        val responses = op["responses"] as Map<Any?, Any?>
        val response = resolve(responses[status.toString()] ?: responses[status])
            as Map<String, Any?>? ?: return listOf("$method $template: status $status not documented")
        val content = response["content"] as Map<String, Map<String, Any?>>? ?: return emptyList()
        val media = contentType?.substringBefore(';')?.trim()
        val entry = content[media] ?: return listOf("$method $template $status: content type $media not documented (${content.keys})")
        val schema = entry["schema"] ?: return emptyList()
        if (body == null) return listOf("$method $template $status: no JSON body")
        return mutableListOf<String>().also { validate(schema, body, "$method $template $status $", it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolve(node: Any?): Any? {
        val ref = (node as? Map<String, Any?>)?.get("\$ref") as String? ?: return node
        var cur: Any? = spec
        for (part in ref.removePrefix("#/").split('/')) cur = (cur as Map<String, Any?>)[part]
        return resolve(cur)
    }

    @Suppress("UNCHECKED_CAST")
    private fun validate(rawSchema: Any?, value: JsonElement, at: String, errors: MutableList<String>) {
        val schema = resolve(rawSchema) as Map<String, Any?>? ?: return
        val types = when (val t = schema["type"]) {
            null -> null
            is List<*> -> t.map { it.toString() }
            else -> listOf(t.toString())
        }
        if (types != null && types.none { matchesType(it, value) }) {
            errors += "$at: expected $types, got $value"; return
        }
        if (value is JsonNull) return
        schema["const"]?.let { if ((value as? JsonPrimitive)?.content != it.toString()) errors += "$at: expected const $it, got $value" }
        (schema["enum"] as List<*>?)?.let { allowed ->
            if ((value as? JsonPrimitive)?.content !in allowed.map { it.toString() }) errors += "$at: $value not in $allowed"
        }
        (schema["pattern"] as String?)?.let { p ->
            val s = (value as? JsonPrimitive)?.content
            if (s == null || !Regex(p).containsMatchIn(s)) errors += "$at: '$s' doesn't match $p"
        }
        (value as? JsonPrimitive)?.doubleOrNull?.let { n ->
            (schema["minimum"] as Number?)?.let { if (n < it.toDouble()) errors += "$at: $n < minimum $it" }
            (schema["maximum"] as Number?)?.let { if (n > it.toDouble()) errors += "$at: $n > maximum $it" }
        }
        if (value is JsonObject) {
            val props = schema["properties"] as Map<String, Any?>? ?: emptyMap()
            (schema["required"] as List<String>?)?.forEach { if (it !in value) errors += "$at: missing required '$it'" }
            for ((k, v) in value) {
                when {
                    k in props -> validate(props[k], v, "$at.$k", errors)
                    schema["additionalProperties"] == false -> errors += "$at: undocumented property '$k'"
                    schema["additionalProperties"] is Map<*, *> -> validate(schema["additionalProperties"], v, "$at.$k", errors)
                }
            }
        }
        if (value is JsonArray) schema["items"]?.let { items -> value.forEachIndexed { i, v -> validate(items, v, "$at[$i]", errors) } }
    }

    private fun matchesType(type: String, v: JsonElement): Boolean = when (type) {
        "null" -> v is JsonNull
        "object" -> v is JsonObject
        "array" -> v is JsonArray
        "string" -> v is JsonPrimitive && v.isString
        "integer" -> v is JsonPrimitive && !v.isString && v.longOrNull != null
        "number" -> v is JsonPrimitive && !v.isString && v.doubleOrNull != null
        "boolean" -> v is JsonPrimitive && !v.isString && v.booleanOrNull != null
        else -> true
    }
}
