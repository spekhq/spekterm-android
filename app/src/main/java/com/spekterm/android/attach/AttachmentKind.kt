package com.spekterm.android.attach

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** The content the desktop accepts, by signature, mirroring its `kindOf` (`remote-attachments.ts`; design D5). */
object AttachmentKind {
    enum class Kind(val image: Boolean) { PNG(true), JPEG(true), GIF(true), WEBP(true), PDF(false) }

    /** How many leading bytes [of] needs. */
    const val HEAD = 16

    fun of(head: ByteArray): Kind? = when {
        head.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> Kind.PNG
        head.startsWith(0xFF, 0xD8, 0xFF) -> Kind.JPEG
        head.startsWithText("GIF87a") || head.startsWithText("GIF89a") -> Kind.GIF
        head.startsWithText("RIFF") && head.size >= 12 && String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> Kind.WEBP
        head.startsWithText("%PDF-") -> Kind.PDF
        else -> null
    }

    /** UTF-8 text with no NUL, decoded strictly (the desktop's rule). An empty file is not text: the desktop needs a size of 1. */
    fun isText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.any { it == 0.toByte() }) return false
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }

    private fun ByteArray.startsWith(vararg bytes: Int): Boolean =
        size >= bytes.size && bytes.indices.all { this[it] == bytes[it].toByte() }

    private fun ByteArray.startsWithText(text: String): Boolean = startsWith(*text.map { it.code }.toIntArray())
}
