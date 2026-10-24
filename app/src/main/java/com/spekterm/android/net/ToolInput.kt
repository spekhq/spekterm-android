package com.spekterm.android.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A tool call's `input` as the desktop selected it (`tool-call-display` D1): fields read by type, each one that is
 * missing or of another type reading `null`, so a field never drops the call. The agent CLI records some flags and
 * numbers as strings (`run_in_background: "true"`), so [int] and [bool] accept those too.
 */
class ToolInput(private val fields: Map<String, JsonElement>) {
    val isEmpty: Boolean get() = fields.isEmpty()

    fun string(key: String): String? = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A non-blank string, trimmed of nothing: blank reads `null`. */
    fun text(key: String): String? = string(key)?.takeIf { it.isNotBlank() }

    /** The string items of an array; other items are skipped. */
    fun strings(key: String): List<String>? =
        (fields[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    fun int(key: String): Long? {
        val p = fields[key] as? JsonPrimitive ?: return null
        return if (p.isString) p.content.trim().toLongOrNull() else p.longOrNull ?: p.doubleOrNull?.takeIf { it.isFinite() }?.toLong()
    }

    fun bool(key: String): Boolean? {
        val p = fields[key] as? JsonPrimitive ?: return null
        return if (p.isString) p.content.trim().lowercase().toBooleanStrictOrNull() else p.booleanOrNull
    }

    /** The object items of an array, each as a [ToolInput]; other items are skipped. */
    fun objects(key: String): List<ToolInput>? =
        (fields[key] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::ToolInput) }

    override fun equals(other: Any?) = other is ToolInput && other.fields == fields

    override fun hashCode() = fields.hashCode()

    override fun toString() = "ToolInput($fields)"

    companion object {
        val EMPTY = ToolInput(emptyMap())

        fun of(element: JsonElement?): ToolInput = (element as? JsonObject)?.let(::ToolInput) ?: EMPTY

        /** `inputTruncated`: each shortened field and its original length (or item count); other values are dropped. */
        fun cut(element: JsonElement?): Map<String, Long> {
            val o = element as? JsonObject ?: return emptyMap()
            return o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeUnless { it.isString }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }?.let { k to it } }.toMap()
        }
    }
}
