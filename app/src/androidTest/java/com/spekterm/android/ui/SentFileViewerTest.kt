package com.spekterm.android.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spekterm.android.desktop.CacheSink
import com.spekterm.android.desktop.SentFileRead
import com.spekterm.android.net.RemoteError
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

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.RGB_565)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** The viewer opened on a result picture, read by a fake that answers [bytes] as [mediaType], or fails with [code]. */
    private fun openResult(ref: SentFileRef, bytes: ByteArray = png(), mediaType: String = "image/png", code: String? = null) {
        val ctx = ToolContext(null, true, "s1", read = { _, _, _, sink, _ ->
            if (code != null) {
                SentFileRead.Outcome.Failed(SentFileRead.reasonOf(code), code = code)
            } else {
                sink.write(bytes)
                SentFileRead.Outcome.Done("", mediaType, bytes.size.toLong())
            }
        }) {}
        compose.setContent { English { CompositionLocalProvider(LocalToolContext provides ctx) { SentFileDialog("s1", ref) {} } } }
    }

    private val screenshot = SentFileRef("toolu_1", 0, null, SentFileRef.Source.RESULT, "claude-in-chrome · computer · picture 1")

    @Test
    fun aResultPictureWithNoPathOpens() {
        openResult(screenshot)
        compose.onNodeWithText("claude-in-chrome · computer · picture 1").assertIsDisplayed()
        compose.waitForIdle()
        compose.onAllNodesWithText("can't be shown", substring = true).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    @Test
    fun aReadPictureIsTitledByItsFile() {
        openResult(SentFileRef("toolu_2", 0, null, SentFileRef.Source.RESULT, "sp.png"))
        compose.onNodeWithText("sp.png").assertIsDisplayed()
    }

    @Test
    fun anSvgOrUndecodableBytesAreNotShownAndNotHandedOff() {
        openResult(screenshot, "<svg/>".toByteArray(), "image/svg+xml")
        compose.onNodeWithText("This picture can't be shown", substring = true).assertIsDisplayed()
        assertFalse(SentFileCache.dir(context).exists())
    }

    @Test
    fun undecodablePngBytes() {
        openResult(screenshot, "not a png".toByteArray())
        compose.onNodeWithText("This picture can't be shown", substring = true).assertIsDisplayed()
    }

    @Test
    fun aPicturesFailuresInPictureWords() {
        openResult(screenshot, code = RemoteError.NO_IMAGE)
        compose.onNodeWithText("This picture is no longer in the conversation", substring = true).assertIsDisplayed()
    }

    @Test
    fun anUnknownCodeForAPicture() {
        openResult(screenshot, code = "LATER")
        compose.onNodeWithText("The desktop could not send this picture (LATER)", substring = true).assertIsDisplayed()
    }

    // Swiping through what the conversation shows (`tool-result-content` D8).

    private val reads = java.util.Collections.synchronizedList(mutableListOf<String>())

    private fun result(n: Int, size: Long = 1_000) = ViewerItem(SentFileRef("t$n", 0, null, SentFileRef.Source.RESULT, "p$n.png"), size)

    private fun sent(path: String) = ViewerItem(SentFileRef("f", 0, path))

    /** The view over [items] opened at [at], read by a fake recording "id:limit"; a sent `big.png` is too large for a thumbnail. */
    private fun swipe(items: List<ViewerItem>, at: SentFileRef = items.first().ref) {
        val ctx = ToolContext(null, true, "s1", read = { _, ref, limit, sink, _ ->
            reads += "${ref.toolUseId}:$limit"
            val path = ref.path ?: ""
            when {
                path.endsWith("big.png") && limit <= THUMB_LIMIT -> SentFileRead.Outcome.Failed(SentFileRead.Reason.TOO_LARGE, size = 6_000_000)
                path.endsWith(".md") -> "# Notes\n\nfrom the agent".toByteArray().let { sink.write(it); SentFileRead.Outcome.Done("", "text/markdown", it.size.toLong()) }
                path.endsWith(".x-spekterm-test") -> byteArrayOf(1).let { sink.write(it); SentFileRead.Outcome.Done("", "application/x-spekterm-test", 1) }
                else -> png().let { sink.write(it); SentFileRead.Outcome.Done("", "image/png", it.size.toLong()) }
            }
        }) {}
        compose.setContent { English { CompositionLocalProvider(LocalToolContext provides ctx) { SentFileDialog("s1", at, items) {} } } }
        compose.waitForIdle()
    }

    private fun read(): Set<String> = reads.map { it.substringBefore(':') }.toSet()

    private fun swipeLeft() {
        compose.onNode(isDialog()).performTouchInput { swipeLeft() }
        compose.waitForIdle()
    }

    @Test
    fun onlyTheOneShownAndItsNeighboursAreRead() {
        swipe((0..4).map { result(it) })
        compose.onNodeWithText("1 / 5").assertExists()
        assertEquals(setOf("t0", "t1"), read())
        swipeLeft()
        compose.onNodeWithText("p1.png").assertExists()
        compose.onNodeWithText("2 / 5").assertExists()
        assertEquals(setOf("t0", "t1", "t2"), read())
        swipeLeft()
        compose.onNodeWithText("3 / 5").assertExists()
        assertTrue(read().toString(), "t3" in read() && "t4" !in read())
        compose.onNode(isDialog()).performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithText("2 / 5").assertExists()
    }

    @Test
    fun zoomedInASwipeMovesThePicture() {
        swipe((0..2).map { result(it) }, at = result(1).ref)
        compose.onNodeWithText("2 / 3").assertExists()
        compose.onNode(isDialog()).performTouchInput {
            pinch(center - Offset(20f, 0f), center - Offset(200f, 0f), center + Offset(20f, 0f), center + Offset(200f, 0f))
        }
        compose.waitForIdle()
        swipeLeft()
        compose.onNodeWithText("2 / 3").assertExists()
    }

    @Test
    fun aLargeNeighbourWaitsToBeShown() {
        swipe(listOf(result(0), result(1, 6_000_000)))
        assertEquals(setOf("t0"), read())
        swipeLeft()
        assertEquals(setOf("t0", "t1"), read())
    }

    @Test
    fun aFileBetweenTwoPicturesIsOpenedOnItsButton() {
        val file = sent("/x/report.x-spekterm-test")
        swipe(listOf(result(0), file, result(2)))
        assertEquals(setOf("t0"), read())
        swipeLeft()
        compose.onNodeWithText("2 / 3").assertExists()
        compose.onAllNodesWithText("report.x-spekterm-test").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        assertFalse("f" in read())
        compose.onNodeWithText("Open in another app").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("No app on this phone", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, reads.count { it.startsWith("f:") })
        compose.onNodeWithText("2 / 3").assertExists()
    }

    @Test
    fun aFileTappedInItsMessageIsHandedOffAtOnce() {
        val file = sent("/x/report.x-spekterm-test")
        swipe(listOf(result(0), file, result(2)), at = file.ref)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("No app on this phone", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(setOf("f"), read())
        compose.onAllNodesWithText("/ 3", substring = true).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    @Test
    fun aSentPictureTooLargeForANeighbourWaits() {
        swipe(listOf(result(0), sent("/x/big.png")))
        assertEquals(listOf("f:$THUMB_LIMIT"), reads.filter { it.startsWith("f:") })
        compose.onNodeWithText("5.7 MB", substring = true).assertExists()
        swipeLeft()
        compose.waitUntil(5_000) { reads.any { it == "f:${16L * 1024 * 1024}" } }
    }

    @Test
    fun aSentMarkdownFileShowsInPlace() {
        swipe(listOf(result(0), sent("/x/notes.md")))
        swipeLeft()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("from the agent", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun onePictureHasNoPlace() {
        swipe(listOf(result(0)))
        compose.onAllNodesWithText("1 / 1").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }
}

