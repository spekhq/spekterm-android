package com.spekterm.android.attach

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.ExifInterface
import com.spekterm.android.desktop.AttachNote
import com.spekterm.android.desktop.Uploads
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Turns a picked file into one the desktop accepts, under [dir] (design D5): accepted content of at most 10 MiB is
 * copied as is; HEIC and other decodable photos, JPEGs relying on their orientation tag, and images over 10 MiB
 * become an upright JPEG; anything else is refused with the reason.
 */
class Preparer(private val dir: File) {
    sealed interface Result {
        data class Ready(val file: File, val name: String, val size: Long, val converted: Boolean) : Result

        data class Refused(val note: AttachNote) : Result
    }

    /** [open] gives the picked content; it is read once, and no further than the limits below. */
    fun prepare(name: String, open: () -> InputStream): Result {
        dir.mkdirs()
        val source = File(dir, "pick-" + UUID.randomUUID())
        try {
            val copied = try {
                open().use { copy(it, source) }
            } catch (_: Exception) {
                return Result.Refused(AttachNote.UNREADABLE)
            } ?: return Result.Refused(AttachNote.SIZE)
            val (size, head) = copied
            if (size == 0L) return Result.Refused(AttachNote.EMPTY)
            val kind = AttachmentKind.of(head)
            return when {
                kind == AttachmentKind.Kind.PDF ->
                    if (size > Uploads.MAX_FILE) Result.Refused(AttachNote.SIZE) else keep(source, name, size)
                kind != null && (size > Uploads.MAX_FILE || (kind == AttachmentKind.Kind.JPEG && rotated(source))) ->
                    convert(source, name) ?: Result.Refused(AttachNote.UNREADABLE)
                kind != null -> keep(source, name, size)
                // Not a known signature: text if it is, else a photo the phone can decode (HEIC, AVIF, BMP…).
                size <= Uploads.MAX_FILE && AttachmentKind.isText(source.readBytes()) -> keep(source, name, size)
                else -> convert(source, name) ?: Result.Refused(if (size > Uploads.MAX_FILE) AttachNote.SIZE else AttachNote.KIND)
            }
        } finally {
            source.delete()
        }
    }

    /**
     * Copies [input] to [target]; `null` once more than the cap was read — 10 MiB, or [MAX_IMAGE_SOURCE] for content
     * that starts like an image, which is re-encoded smaller. Returns the size and the first bytes.
     */
    private fun copy(input: InputStream, target: File): Pair<Long, ByteArray>? {
        val buffer = ByteArray(64 * 1024)
        var head = ByteArray(0)
        var total = 0L
        var cap = Uploads.MAX_FILE
        target.outputStream().use { out ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (head.size < AttachmentKind.HEAD) {
                    head += buffer.copyOf(minOf(n, AttachmentKind.HEAD - head.size))
                    if (head.size >= AttachmentKind.HEAD || total == 0L) cap = if (likelyImage(head)) MAX_IMAGE_SOURCE else Uploads.MAX_FILE
                }
                total += n
                if (total > cap) return null
                out.write(buffer, 0, n)
            }
        }
        return total to head
    }

    /** An accepted image signature, or an ISO media (`ftyp`: HEIC, HEIF, AVIF) or BMP one. */
    private fun likelyImage(head: ByteArray): Boolean =
        AttachmentKind.of(head)?.image == true ||
            (head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp") ||
            (head.size >= 2 && head[0] == 'B'.code.toByte() && head[1] == 'M'.code.toByte())

    private fun rotated(file: File): Boolean = try {
        val orientation = ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED
    } catch (_: Exception) {
        false
    }

    private fun keep(source: File, name: String, size: Long): Result {
        val target = File(dir, UUID.randomUUID().toString())
        if (!source.renameTo(target)) source.copyTo(target, overwrite = true)
        return Result.Ready(target, name, size, converted = false)
    }

    /**
     * Decodes [source] at most [MAX_EDGE] px on its long edge, upright (the decoder applies the orientation tag),
     * and encodes JPEG quality 90, scaling by 0.75 while over 10 MiB. A converted photo carries no EXIF.
     */
    private fun convert(source: File, name: String): Result? {
        var bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val long = maxOf(info.size.width, info.size.height)
                if (long > MAX_EDGE) {
                    val scale = MAX_EDGE.toDouble() / long
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
            }
        } catch (_: Exception) {
            return null
        } catch (_: OutOfMemoryError) {
            return null
        }
        if (bitmap.hasAlpha()) bitmap = flatten(bitmap)
        val target = File(dir, UUID.randomUUID().toString())
        try {
            while (true) {
                target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                if (target.length() <= Uploads.MAX_FILE) break
                val smaller = Bitmap.createScaledBitmap(bitmap, (bitmap.width * 0.75).toInt().coerceAtLeast(1), (bitmap.height * 0.75).toInt().coerceAtLeast(1), true)
                bitmap.recycle()
                bitmap = smaller
            }
        } catch (_: Exception) {
            target.delete()
            return null
        } finally {
            bitmap.recycle()
        }
        return Result.Ready(target, jpegName(name), target.length(), converted = true)
    }

    /** Transparent pixels become white, not JPEG's black. */
    private fun flatten(bitmap: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        bitmap.recycle()
        return out
    }

    companion object {
        const val MAX_EDGE = 4096
        const val QUALITY = 90

        /** How much of a photo is read before giving up: a 50 MP HEIC is far below it. */
        const val MAX_IMAGE_SOURCE = 100L * 1024 * 1024

        /** The name with a `.jpg` extension. */
        fun jpegName(name: String): String {
            val base = name.substringBeforeLast('.', name).ifEmpty { "photo" }
            return "$base.jpg"
        }
    }
}
