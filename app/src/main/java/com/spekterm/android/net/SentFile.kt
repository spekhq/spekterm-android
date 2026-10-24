package com.spekterm.android.net

import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * One answer of `files.read` (`tool-call-display` D1): [bytes] start at [offset] of a file of [size] bytes; [version]
 * is named on every later chunk.
 */
class FileChunk(
    val name: String,
    val size: Long,
    val mediaType: String,
    val version: String,
    val offset: Long,
    val bytes: ByteArray,
    val complete: Boolean,
) {
    companion object {
        /**
         * The answer, or `null` when it is malformed: a missing `size`, `version` or `data`, `data` that is not
         * base64, a negative size or offset.
         */
        fun decode(element: JsonElement?): FileChunk? {
            val o = element as? JsonObject ?: return null
            val size = o.long("size")?.takeIf { it >= 0 } ?: return null
            val offset = o.long("offset")?.takeIf { it >= 0 } ?: 0L
            return FileChunk(
                name = o.string("name") ?: "",
                size = size,
                mediaType = o.string("mediaType")?.lowercase() ?: "application/octet-stream",
                version = o.string("version") ?: return null,
                offset = offset,
                bytes = runCatching { Base64.getDecoder().decode(o.string("data") ?: return null) }.getOrNull() ?: return null,
                complete = (o["complete"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null,
            )
        }
    }
}
