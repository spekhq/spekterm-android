package com.spekterm.android.ui

import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Agent text loads nothing, keeps HTML as text, and opens only web links on tap (`conversation-view`). */
@RunWith(AndroidJUnit4::class)
class AgentMarkdownTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer()

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    @Test
    fun loadsNothingAndShowsHtmlAsText() {
        val url = server.url("/x.png").toString()
        val text = """
            ![x]($url)

            An inline <img src="$url"> tag, and a [link]($url).

            Use `Vec<T>` or Vec<T> here; <command-name>/opsx:continue</command-name>

            <div>
            block html
            </div>
        """.trimIndent()
        compose.setContent { AgentMarkdown(text) }
        compose.waitForIdle()
        Thread.sleep(1_500)
        compose.waitForIdle()
        assertEquals("nothing may be fetched", 0, server.requestCount)
        compose.onNodeWithText("Vec<T> here", substring = true).assertExists()
        compose.onNodeWithText("<command-name>/opsx:continue</command-name>", substring = true).assertExists()
        compose.onNodeWithText("block html", substring = true).assertExists()
        compose.onNodeWithText("<img", substring = true).assertExists()
    }

    @Test
    fun opensOnlyWebLinks() {
        val started = mutableListOf<Intent>()
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent: Intent) {
                started += intent
            }
        }
        val handler = SafeUriHandler(context)
        handler.openUri("javascript:alert(1)")
        handler.openUri("intent://x#Intent;end")
        handler.openUri("file:///sdcard/x")
        handler.openUri("not a uri at all")
        assertEquals(0, started.size)
        handler.openUri("https://example.com/a")
        assertEquals("https://example.com/a", started.single().data.toString())
    }
}
