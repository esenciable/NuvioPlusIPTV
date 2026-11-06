package com.nuvio.tv.ext.livetv.magis

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The portal's JSON, in a form that compiles in the KMP mobile client.
 *
 * `kotlinx.serialization` is the JSON stack on BOTH clients (the TV app already depends on it),
 * so this package uses it instead of `org.json` — which exists only on Android.
 *
 * The accessor names are deliberately org.json-flavoured (`optString`, `optJSONArray`,
 * `optJSONObject`, `length`, and a `put`-building constructor). The portal's payloads are
 * lenient: a missing key, a key of the wrong shape and an explicit `null` are all normal, and
 * the client's behaviour depends on org.json's answer to each. Keeping those semantics in ONE
 * tested place is cheaper — and safer — than rewriting 80 accessor chains by hand and hoping
 * they still mean the same thing.
 *
 * So this is a compatibility layer, not a style choice. Do not "clean it up" without porting
 * its tests.
 */
internal class MagisJsonObject internal constructor(private var raw: JsonObject) {

    /** `JSONObject()` — an empty object, for the serializers that build with `put`. */
    constructor() : this(JsonObject(emptyMap()))

    /** `JSONObject(text)` — throws on malformed input, exactly like org.json does. */
    constructor(text: String) : this(kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObjectOrThrow())

    /** `JSONObject(body)` — the portal's request bodies are `Map<String, Any?>`. */
    constructor(values: Map<String, Any?>) : this(JsonObject(values.mapValues { toJsonElement(it.value) }))

    fun optString(name: String): String {
        val element = raw[name] ?: return ""
        return when (element) {
            // org.json renders its NULL sentinel as the STRING "null"; the callers guard with
            // isNull() before reading, and keeping the exact rendering keeps their logic intact.
            JsonNull -> "null"
            is JsonPrimitive -> element.contentOrNull.orEmpty()
            // org.json answers the JSON text for a nested value; callers rely on the emptiness
            // check that follows, never on the text.
            else -> element.toString()
        }
    }

    /** org.json's `opt`: the raw value, `null` when absent or the null sentinel. */
    fun opt(name: String): Any? = when (val element = raw[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> element.contentOrNull
        else -> element.toString()
    }

    fun optDouble(name: String, fallback: Double = Double.NaN): Double =
        (raw[name] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: fallback

    /** org.json's `getString(name)`: the value as text, empty when absent. */
    fun getString(name: String): String = optString(name)

    fun optInt(name: String, fallback: Int = 0): Int =
        (raw[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: fallback

    fun optLong(name: String, fallback: Long = 0L): Long =
        (raw[name] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: fallback

    fun optBoolean(name: String, fallback: Boolean = false): Boolean =
        (raw[name] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: fallback

    fun optJSONObject(name: String): MagisJsonObject? = (raw[name] as? JsonObject)?.let { MagisJsonObject(it) }

    fun optJSONArray(name: String): MagisJsonArray? = (raw[name] as? JsonArray)?.let { MagisJsonArray(it) }

    fun has(name: String): Boolean = raw.containsKey(name)

    /** org.json's `isNull`: true when the key is absent OR its value is the null sentinel. */
    fun isNull(name: String): Boolean {
        val element = raw[name] ?: return true
        return element is JsonNull
    }

    fun length(): Int = raw.size

    /** org.json's mutating builder, used by the config serializer's `apply { put(...) }`. */
    fun put(name: String, value: Any?): MagisJsonObject {
        raw = JsonObject(raw + (name to toJsonElement(value)))
        return this
    }

    internal fun toJsonElement(): JsonObject = raw

    override fun toString(): String = raw.toString()

    override fun equals(other: Any?): Boolean = other is MagisJsonObject && other.raw == raw

    override fun hashCode(): Int = raw.hashCode()

    companion object {
        /** org.json's `JSONObject.NULL` sentinel, for callers that build values with it. */
        val NULL: Any = kotlinx.serialization.json.JsonNull
    }
}

internal class MagisJsonArray internal constructor(private var raw: JsonArray) {

    /** `JSONArray()` — an empty array, for the builders that append with `put`. */
    constructor() : this(JsonArray(emptyList()))

    /** `JSONArray(text)` — throws on malformed input, like org.json. */
    constructor(text: String) : this(kotlinx.serialization.json.Json.parseToJsonElement(text).jsonArrayOrThrow())

    constructor(values: List<Any?>) : this(JsonArray(values.map { toJsonElement(it) }))

    fun length(): Int = raw.size

    /** org.json's `JSONArray.put(value)`: appends and returns itself, for `apply { put(...) }`. */
    fun put(value: Any?): MagisJsonArray {
        raw = JsonArray(raw + toJsonElement(value))
        return this
    }

    fun optString(index: Int): String = (raw.getOrNull(index) as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** org.json's `getString(index)`: the value as text, empty when absent. */
    fun getString(index: Int): String = optString(index)

    fun optInt(index: Int, fallback: Int = 0): Int =
        (raw.getOrNull(index) as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: fallback

    fun optJSONObject(index: Int): MagisJsonObject? =
        (raw.getOrNull(index) as? JsonObject)?.let { MagisJsonObject(it) }

    fun optJSONArray(index: Int): MagisJsonArray? =
        (raw.getOrNull(index) as? JsonArray)?.let { MagisJsonArray(it) }

    /** Iteration helper for the "walk every object in the list" shape the decoders use. */
    fun forEachObject(action: (MagisJsonObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(action)
    }

    internal fun toJsonElement(): JsonArray = raw

    override fun toString(): String = raw.toString()

    override fun equals(other: Any?): Boolean = other is MagisJsonArray && other.raw == raw

    override fun hashCode(): Int = raw.hashCode()
}

/** `Any?` -> JSON, for the request bodies built from maps and the config serializer. */
internal fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is MagisJsonObject -> value.toJsonElement()
    is MagisJsonArray -> value.toJsonElement()
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to toJsonElement(it.value) })
    is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
    is Array<*> -> JsonArray(value.map { toJsonElement(it) })
    else -> JsonPrimitive(value.toString())
}

private fun JsonElement.jsonObjectOrThrow(): JsonObject =
    this as? JsonObject ?: throw IllegalArgumentException("not a JSON object: ${toString().take(80)}")

private fun JsonElement.jsonArrayOrThrow(): JsonArray =
    this as? JsonArray ?: throw IllegalArgumentException("not a JSON array: ${toString().take(80)}")
