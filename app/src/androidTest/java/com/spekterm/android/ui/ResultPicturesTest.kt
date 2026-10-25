package com.spekterm.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spekterm.android.desktop.ConversationItems
import com.spekterm.android.desktop.PictureCache
import com.spekterm.android.desktop.SentFileRead
import com.spekterm.android.net.Entry
import com.spekterm.android.net.ResultImage
import com.spekterm.android.net.ToolInput
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pictures a tool brought back, and a Read's content, on screen (`tool-result-content` D3, D4). */
@RunWith(AndroidJUnit4::class)
class ResultPicturesTest {
    @get:Rule val compose = createComposeRule()

    private fun png(width: Int, height: Int) = ByteArrayOutputStream().also { Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    private var png = png(1080, 2400)
    private val reads = mutableListOf<String>()

    private fun context(imagesReadable: Boolean = true) = ToolContext(null, true, "s1", imagesReadable = imagesReadable, pictures = PictureCache(entries = mutableStateMapOf()), read = { _, ref, _, sink, _ ->
        reads += "${ref.toolUseId}:${ref.index}"
        sink.write(png)
        SentFileRead.Outcome.Done("", "image/png", png.size.toLong())
    }) {}

    private fun read(id: String, file: String, size: Long = png.size.toLong()) = ConversationItems.Call(
        "k$id",
        Entry.Tool("u$id", 0, id, "Read", file, ToolInput.of(Json.parseToJsonElement("""{"file_path":"/x/$file"}"""))),
        Entry.Result("r$id", 0, id, "", false, listOf(ResultImage("image/png", size))),
    )

    private fun show(shown: ConversationItems.Shown, ctx: ToolContext = context(), drawn: () -> Unit = {}) {
        val open = mutableStateMapOf<String, Boolean>()
        compose.setContent {
            English {
                CompositionLocalProvider(LocalToolContext provides ctx, LocalPictureDrawn provides drawn) {
                    Box(Modifier.width(412.dp)) { ShownRow(shown, open, null) }
                }
            }
        }
    }

    private fun pictureNodes() = compose.onAllNodesWithContentDescription("Picture: ", substring = true).fetchSemanticsNodes()

    @Test
    fun aLoneReadShowsItsPictureWithoutATap() {
        var drawn = 0
        show(read("t1", "rn.png"), drawn = { drawn++ })
        compose.onNodeWithContentDescription("Picture: rn.png").assertExists()
        compose.waitUntil(5_000) { drawn > 0 }
        assertEquals(listOf("t1:0"), reads)
    }

    @Test
    fun aTallPictureStopsAt360dp() {
        show(read("t1", "tall.png"))
        compose.waitUntil(5_000) { reads.isNotEmpty() }
        compose.waitForIdle()
        with(compose.density) { assertEquals(360.dp.toPx(), pictureNodes().first().boundsInRoot.height, 2f) }
    }

    @Test
    fun aSmallPictureKeepsItsOwnSize() {
        png = png(120, 120)
        show(read("t1", "icon.png"))
        compose.waitUntil(5_000) { reads.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(120f, pictureNodes().first().boundsInRoot.width, 2f)
    }

    @Test
    fun aCollapsedRunShowsThreeTilesAndMore() {
        val calls = (1..5).map { read("t$it", "s$it.png") }
        show(ConversationItems.Run("k", calls))
        compose.waitForIdle()
        assertEquals(3, pictureNodes().size)
        compose.onNodeWithContentDescription("2 more pictures, expand").assertExists()
        compose.waitUntil(5_000) { reads.size == 3 }
        assertEquals(listOf("t5:0", "t4:0", "t3:0"), reads.sorted().reversed())
        compose.onNodeWithContentDescription("2 more pictures, expand").performClick()
        compose.waitUntil(5_000) { reads.size == 8 }
        assertEquals(5, pictureNodes().size)
    }

    @Test
    fun aLargePictureWaitsForATap() {
        show(read("t1", "big.png", 6_400_000))
        compose.onNodeWithContentDescription("Picture: big.png, Picture · 6.1 MB · tap to load").assertExists()
        compose.waitForIdle()
        assertTrue(reads.isEmpty())
    }

    @Test
    fun noPictureWithoutTheOperation() {
        show(read("t1", "rn.png"), context(imagesReadable = false))
        compose.waitForIdle()
        assertTrue(pictureNodes().isEmpty())
        assertTrue(reads.isEmpty())
    }

    @Test
    fun aReadsContentIsOneNodeWithoutNumbers() {
        val call = ConversationItems.Call(
            "k",
            Entry.Tool("u", 0, "t", "Read", "a.kt", ToolInput.of(Json.parseToJsonElement("""{"file_path":"/x/a.kt"}"""))),
            Entry.Result("r", 0, "t", "1\tfun a()\n2\t\n3\tfun b()", false),
        )
        val open = mutableStateMapOf("k" to true)
        compose.setContent { English { CompositionLocalProvider(LocalToolContext provides context()) { ShownRow(call, open, null) } } }
        val descriptions = mutableListOf<String>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { descriptions += it }
            n.children.forEach(::walk)
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        assertTrue(descriptions.toString(), descriptions.contains("Content, fun a()\n\nfun b()"))
    }

    @Test
    fun aLongLineIsShownWholeWrapped() {
        val line = "x".repeat(600)
        val call = ConversationItems.Call(
            "k",
            Entry.Tool("u", 0, "t", "Read", "a.txt", ToolInput.of(Json.parseToJsonElement("""{"file_path":"/x/a.txt"}"""))),
            Entry.Result("r", 0, "t", "1\t$line", false),
        )
        val open = mutableStateMapOf("k" to true)
        compose.setContent { English { CompositionLocalProvider(LocalToolContext provides context()) { Box(Modifier.width(412.dp)) { ShownRow(call, open, null) } } } }
        compose.onAllNodesWithText(line, substring = true, useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }
}
