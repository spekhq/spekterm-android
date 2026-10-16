package com.spekterm.android.attach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `AttachmentKind`, mirroring the desktop's `kindOf` (design D5). */
class AttachmentKindTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() } + ByteArray(8)

    @Test
    fun signatures() {
        assertEquals(AttachmentKind.Kind.PNG, AttachmentKind.of(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(AttachmentKind.Kind.JPEG, AttachmentKind.of(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(AttachmentKind.Kind.GIF, AttachmentKind.of("GIF87a....".toByteArray()))
        assertEquals(AttachmentKind.Kind.GIF, AttachmentKind.of("GIF89a....".toByteArray()))
        assertEquals(AttachmentKind.Kind.WEBP, AttachmentKind.of("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertNull(AttachmentKind.of("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(AttachmentKind.Kind.PDF, AttachmentKind.of("%PDF-1.7\n".toByteArray()))
        assertNull(AttachmentKind.of("PK\u0003\u0004".toByteArray()))
        assertNull(AttachmentKind.of(ByteArray(0)))
    }

    @Test
    fun text() {
        assertTrue(AttachmentKind.isText("plain text\n".toByteArray()))
        assertTrue(AttachmentKind.isText("﻿中文與 emoji 🎉".toByteArray()))
        assertFalse(AttachmentKind.isText("a\u0000b".toByteArray()))
        assertFalse(AttachmentKind.isText(byteArrayOf(0x61, 0xC3.toByte(), 0x28)))
        assertFalse(AttachmentKind.isText(ByteArray(0)))
    }
}
