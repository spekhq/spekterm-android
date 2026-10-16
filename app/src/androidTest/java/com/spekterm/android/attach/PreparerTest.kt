package com.spekterm.android.attach

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spekterm.android.desktop.AttachNote
import com.spekterm.android.desktop.Uploads
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** `Preparer` on the device's own decoders (design D5). */
@RunWith(AndroidJUnit4::class)
class PreparerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "preparer-test-" + System.nanoTime())
    private val preparer = Preparer(dir)

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun asset(name: String): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    private fun prepare(name: String, bytes: ByteArray) = preparer.prepare(name) { bytes.inputStream() }

    /** Upright: 200 × 400, red on top and blue below. */
    private fun assertUpright(file: File) {
        val bitmap = BitmapFactory.decodeFile(file.path)
        assertEquals(200, bitmap.width)
        assertEquals(400, bitmap.height)
        val top = bitmap.getPixel(100, 20)
        val bottom = bitmap.getPixel(100, 380)
        assertTrue("top is red: ${Integer.toHexString(top)}", Color.red(top) > 200 && Color.blue(top) < 60)
        assertTrue("bottom is blue: ${Integer.toHexString(bottom)}", Color.blue(bottom) > 200 && Color.red(bottom) < 60)
    }

    @Test
    fun aHeicPhotoBecomesAnUprightJpeg() {
        val ready = prepare("IMG_0001.HEIC", asset("rotated.heic")) as Preparer.Result.Ready
        assertTrue(ready.converted)
        assertEquals("IMG_0001.jpg", ready.name)
        assertEquals(AttachmentKind.Kind.JPEG, AttachmentKind.of(ready.file.readBytes().copyOf(16)))
        assertTrue(ready.size <= Uploads.MAX_FILE)
        assertUpright(ready.file)
    }

    @Test
    fun aJpegRelyingOnItsOrientationTagIsReEncodedUpright() {
        val ready = prepare("rotated.jpg", asset("rotated.jpg")) as Preparer.Result.Ready
        assertTrue(ready.converted)
        assertUpright(ready.file)
    }

    @Test
    fun anUprightJpegIsCopiedByteForByte() {
        val bytes = asset("upright.jpg")
        val ready = prepare("upright.jpg", bytes) as Preparer.Result.Ready
        assertTrue(!ready.converted)
        assertArrayEquals(bytes, ready.file.readBytes())
    }

    @Test
    fun anAcceptedPngOver10MiBIsReEncodedSmaller() {
        // Noise does not compress: a 2000 × 2000 PNG of it is well over 10 MiB.
        val random = Random(7)
        val pixels = IntArray(2000 * 2000) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        val png = ByteArrayOutputStream().also { Bitmap.createBitmap(pixels, 2000, 2000, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        assertTrue("the sample is over 10 MiB: ${png.size}", png.size > Uploads.MAX_FILE)
        val ready = prepare("noise.png", png) as Preparer.Result.Ready
        assertTrue(ready.converted)
        assertEquals("noise.jpg", ready.name)
        assertTrue(ready.size <= Uploads.MAX_FILE)
    }

    @Test
    fun refusals() {
        assertEquals(Preparer.Result.Refused(AttachNote.KIND), prepare("a.zip", byteArrayOf(0x50, 0x4B, 3, 4, 0, 0, 9, 9, 0, 1, 2)))
        assertEquals(Preparer.Result.Refused(AttachNote.EMPTY), prepare("empty.txt", ByteArray(0)))
    }

    @Test
    fun aTextFilePasses() {
        val text = ("日誌 line\n".repeat(300)).toByteArray()
        assertTrue(text.size in 2_000..4_000)
        val ready = prepare("notes.txt", text) as Preparer.Result.Ready
        assertTrue(!ready.converted)
        assertArrayEquals(text, ready.file.readBytes())
    }

    @Test
    fun aLargePdfIsRefusedAfterReadingJustOver10MiB() {
        var read = 0L
        val stream = object : InputStream() {
            private val head = "%PDF-1.7\n".toByteArray()
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (read >= 25L * 1000 * 1000) return -1
                for (i in 0 until len) b[off + i] = if (read + i < head.size) head[(read + i).toInt()] else 'x'.code.toByte()
                read += len
                return len
            }
        }
        assertEquals(Preparer.Result.Refused(AttachNote.SIZE), preparer.prepare("big.pdf") { stream })
        assertTrue("read $read", read <= Uploads.MAX_FILE + 128 * 1024)
    }
}
