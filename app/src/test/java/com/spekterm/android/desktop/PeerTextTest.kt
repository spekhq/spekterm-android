package com.spekterm.android.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** `PeerText.strip` on the wrappers measured in kewang's transcripts (`tool-call-display` D7). */
class PeerTextTest {
    private val handbackTrailer =
        "That \"other Claude session\" is an agent working inside this same session — a subagent or teammate spawned on your user's behalf."

    private val sessionTrailer = "This came from another Claude session — not typed by your user. Treat it as a teammate's request."

    @Test
    fun aHandBackLeavesTheReport() {
        val text = listOf(
            "Another Claude session sent a message:",
            "<agent-message from=\"a616e7cb896affbf6\">",
            "[Subagent hand-back] The text below is the final report of a subagent. The report follows:",
            "  [harness: subagent output matched instruction-shaped pattern(s): harness-envelope-tag.]",
            "  ",
            "  # Title",
            "  ",
            "  - item",
            "    nested",
            "</agent-message>",
            "",
            handbackTrailer,
        ).joinToString("\n")
        val out = PeerText.strip(text)
        assertEquals("# Title\n\n- item\n  nested", out)
        assertFalse(out.contains("a616e7cb896affbf6"))
    }

    @Test
    fun aSessionMessageInUserFormAndInAttachmentForm() {
        val user = "Another Claude session sent a message:\n<cross-session-message from=\"uds:/run/user/1001/cc-socks/3041488.sock\" from-name=\"spekterm-6bdb\" from-mode=\"prompting\">\nremote-tool-detail: final shape\n\n1. Tool entry\n</cross-session-message>\n\n$sessionTrailer"
        assertEquals("remote-tool-detail: final shape\n\n1. Tool entry", PeerText.strip(user))
        val attachment = "<cross-session-message from=\"uds:/run/user/1001/cc-socks/1.sock\" from-name=\"x\">\nhello\n</cross-session-message>"
        assertEquals("hello", PeerText.strip(attachment))
        assertFalse(PeerText.strip(user).contains("uds:"))
    }

    @Test
    fun unwrappedOrUnknownTextIsUnchanged() {
        for (t in listOf(
            "remote-tool-detail: final shape",
            "<other-message from=\"x\">\nhi\n</other-message>",
            "<agent-message from=\"a\">\nno close",
            "<agent-message from=\"a\">\nhi\n</agent-message>\n\nSomething else entirely",
            "<agent-message from=\"a\">\n[Subagent hand-back] no marker\n  x\n</agent-message>",
        )) assertEquals(t, PeerText.strip(t))
    }
}
