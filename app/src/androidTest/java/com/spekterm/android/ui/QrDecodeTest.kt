package com.spekterm.android.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import zxingcpp.BarcodeReader

/**
 * The old decoding path against the new on generated codes (`scan-connect-zh` D2 "Measuring it", tasks 1.3): the
 * desktop's pairing code drawn at a share of the preview's width, in each path's square crop — 480 px for the old
 * (CameraX's default 640×480 frames, ZXing Java), 1080 px for the new (1080p frames, zxing-cpp). Emulator only.
 */
@RunWith(AndroidJUnit4::class)
class QrDecodeTest {
    private val fp = "9FD2EB61946CBD8FF05E18C2166D9B7AD66E6AFF2F68D432B96F3CBC08E3BCB9"
    private val code = "ABCDEFGHIJKLMNOPQRSTUVWXY2"

    /** Built as spekterm's `pairingLink` does: `URLSearchParams`, so `,` → `%2C` and `:` → `%3A`. */
    private fun link(addresses: List<String>) = "spekterm://pair?v=1&a=" +
        addresses.joinToString(",").replace(",", "%2C").replace(":", "%3A") + "&p=47615&fp=$fp&c=$code&n=kewang-7420"

    private val five = listOf(
        "192.168.0.199",
        "2001:b011:2020:b721:a3e4:6e01:79d9:c4da",
        "2001:b011:2020:b721:520b:58de:f1f2:69ce",
        "100.106.81.33",
        "fd7a:115c:a1e0::6432:5121",
    )
    private val seven = five + listOf("2001:b011:2020:b721:1c2d:3e4f:5a6b:7c8d", "2001:b011:2020:b721:9e8d:7c6b:5a4f:3e2d")

    private val shares = listOf(4, 5, 6, 8)
    private val angles = listOf(0f, 15f, 90f)

    /** The code at ECC M with a two-module border, as the desktop draws it, one pixel per module. */
    private fun modules(text: String): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        return bitmap
    }

    /** A [crop]-pixel square frame, the code 1/[share] of its width, turned by [angle], filtered like a camera. */
    private fun frame(code: Bitmap, crop: Int, share: Int, angle: Float, blur: Boolean): Bitmap {
        val out = Bitmap.createBitmap(crop, crop, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(200, 200, 200))
        val scale = crop.toFloat() / share / code.width
        val m = Matrix().apply {
            postTranslate(-code.width / 2f, -code.height / 2f)
            postScale(scale, scale)
            postRotate(angle)
            postTranslate(crop / 2f, crop / 2f)
        }
        canvas.drawBitmap(code, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return if (blur) boxBlur(out) else out
    }

    private fun boxBlur(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h).also { source.getPixels(it, 0, w, 0, 0, w, h) }
        val gray = IntArray(w * h) { Color.red(pixels[it]) }
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) {
                    sum += gray[yy * w + xx]
                    n++
                }
            }
            val v = sum / n
            out[y * w + x] = Color.rgb(v, v, v)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private val newReader = BarcodeReader(
        BarcodeReader.Options().apply {
            formats = setOf(BarcodeReader.Format.QR_CODE)
            tryHarder = true
            tryRotate = true
            tryInvert = true
            tryDownscale = true
        },
    )

    private fun readNew(bitmap: Bitmap): String? = newReader.read(bitmap, Rect(0, 0, bitmap.width, bitmap.height), 0).firstOrNull()?.text

    private fun readOld(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))), mapOf(DecodeHintType.TRY_HARDER to true)).text
        } catch (_: Exception) {
            null
        }
    }

    @Test
    fun theNewPathReadsTheRealCodeAtAFifthOfThePreview() {
        val misses = mutableListOf<String>()
        for ((name, addresses) in listOf("five" to five, "seven" to seven)) {
            val text = link(addresses)
            val code = modules(text)
            Log.i(TAG, "$name addresses: ${text.length} bytes, ${code.width} modules with the border")
            for (share in shares) {
                val row = StringBuilder("$name 1/$share:")
                for (angle in angles) for (blur in listOf(false, true)) {
                    val old = readOld(frame(code, 480, share, angle, blur)) == text
                    val new = readNew(frame(code, 1080, share, angle, blur)) == text
                    row.append(" ${angle.toInt()}°${if (blur) "+blur" else ""} old=${mark(old)} new=${mark(new)} (${"%.1f".format(480f / share / code.width)}/${"%.1f".format(1080f / share / code.width)} px)")
                    if (name == "five" && share <= 5 && !new) misses += "1/$share ${angle.toInt()}° blur=$blur"
                }
                Log.i(TAG, row.toString())
            }
        }
        assertTrue("the new path missed: $misses", misses.isEmpty())
    }

    private fun mark(read: Boolean) = if (read) "✓" else "✗"

    private companion object {
        const val TAG = "QrDecodeTest"
    }
}
