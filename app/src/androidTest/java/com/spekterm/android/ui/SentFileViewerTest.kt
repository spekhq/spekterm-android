package com.spekterm.android.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spekterm.android.desktop.CacheSink
import com.spekterm.android.desktop.SentFileRead
import java.io.ByteArrayOutputStream
import java.io.File
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The sent-file viewer's views and errors, and handing a file to another app (`tool-call-display` D6, D9). */
@RunWith(AndroidJUnit4::class)
class SentFileViewerTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun start() = server.start()

    @After fun stop() {
        server.close()
        SentFileCache.clear(context)
    }

    @Test
    fun markdownLoadsNothing() {
        val url = server.url("/x.png").toString()
        compose.setContent { English { ViewerBody(ViewerState.Markdown("# Draft\n\n![x]($url)\n\n<img src=\"$url\">")) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Draft").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(1_500)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aLongOneLineFileShows() {
        val json = "{" + "\"k\":1,".repeat(170_000) + "}"
        compose.setContent { English { ViewerBody(ViewerState.Lines(SentFileViewer.pieces(json))) } }
        compose.waitForIdle()
        assertTrue(SentFileViewer.pieces(json).all { it.length <= SentFileViewer.PIECE })
    }

    @Test
    fun imagesDecodeNoLargerThanTheScreen() {
        val bitmap = Bitmap.createBitmap(4000, 3000, Bitmap.Config.RGB_565)
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val image = decode(png, 2400)!!
        assertTrue("${image.width}x${image.height}", maxOf(image.width, image.height) in 2000..4000 && image.width <= 4000)
        assertTrue(maxOf(decode(png, 1000)!!.width, decode(png, 1000)!!.height) <= 2000)
        assertNull(decode("not an image".toByteArray(), 2400))
    }

    @Test
    fun eachStateSaysWhatHappened() {
        val cases = listOf(
            ViewerState.Failed(SentFileRead.Reason.NOT_SENT) to "not among those sent",
            ViewerState.Failed(SentFileRead.Reason.GONE) to "no longer on the desktop",
            ViewerState.Failed(SentFileRead.Reason.CHANGED) to "changed while",
            ViewerState.Failed(SentFileRead.Reason.TOO_LARGE, 2_000_000) to "Too large",
            ViewerState.Failed(SentFileRead.Reason.NOT_CONNECTED) to "not connected",
            ViewerState.Failed(SentFileRead.Reason.SESSION_GONE) to "no longer running",
            ViewerState.Failed(SentFileRead.Reason.OTHER, code = "BAD_PARAMS") to "BAD_PARAMS",
            ViewerState.CannotShow to "can't be shown",
            ViewerState.NoApp to "No app on this phone",
        )
        var state: ViewerState by androidx.compose.runtime.mutableStateOf(cases.first().first)
        compose.setContent { English { ViewerBody(state) } }
        for ((s, text) in cases) {
            state = s
            compose.waitForIdle()
            compose.onNodeWithText(text, substring = true).assertExists()
        }
    }

    @Test
    fun theHandOffGrantsReadOnOneUri() {
        val sink = CacheSink(SentFileCache.dir(context), "reel.mp4")
        sink.write(byteArrayOf(0, 0, 0, 24))
        sink.close()
        val intent = handOffIntent(context, sink.file, "video/mp4")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("video/mp4", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("application/zip", handOffIntent(context, File(sink.file.parentFile, "a.zip").also { it.writeText("x") }, "application/octet-stream").type)
    }

    @Test
    fun noAppForAMadeUpTypeAndTheCacheEmpties() {
        val sink = CacheSink(SentFileCache.dir(context), "x.spekterm-test")
        sink.write(byteArrayOf(1))
        sink.close()
        val intent = handOffIntent(context, sink.file, "application/x-spekterm-test")
        assertTrue(context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isEmpty())
        SentFileCache.clear(context)
        assertFalse(SentFileCache.dir(context).exists())
    }
}
