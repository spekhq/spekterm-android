package com.spekterm.android.log

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spekterm.android.attention.AttentionNotifier
import com.spekterm.android.attention.AttentionPolicy
import com.spekterm.android.net.Attention
import com.spekterm.android.net.Position
import com.spekterm.android.store.PositionStore
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionLogTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun freshLog(): ConnectionLog {
        val dir = File(context.cacheDir, "log-test-${System.nanoTime()}")
        return ConnectionLog(dir, conditions = { ConnectionLog.conditionsOf(context) })
    }

    @Test
    fun rotatesAtTheBoundAndKeepsOnePreviousFile() {
        val log = freshLog()
        repeat(20_000) { log.write(ConnectionLog.Kind.ATTEMPT, "address" to "192.168.50.22", "n" to it) }
        assertTrue(log.previous.exists())
        assertTrue(log.current.length() <= ConnectionLog.MAX_BYTES)
        assertTrue(log.previous.length() <= ConnectionLog.MAX_BYTES)
        assertTrue(log.export().lines().last { it.isNotEmpty() }.contains("n=19999"))
    }

    @Test
    fun anAttentionEntryHoldsNoEventText() {
        val log = freshLog()
        val event = Attention(
            Position("e", 1), Attention.CHOICE, at = System.currentTimeMillis(), sessionId = "s",
            folderName = "FOLDER-MARK", title = "TITLE-MARK", summary = "SUMMARY-MARK", tool = "TOOL-MARK", suppressed = true,
        )
        AttentionNotifier(context, "test-device", PositionStore(context), log).apply(AttentionPolicy.onLive(event), listOf(event), "live")
        val text = log.export()
        assertTrue(text.contains("attention") && text.contains("kind=choice") && text.contains("outcome=suppressed"))
        for (mark in listOf("FOLDER-MARK", "TITLE-MARK", "SUMMARY-MARK", "TOOL-MARK")) assertFalse(mark, text.contains(mark))
        PositionStore(context).delete()
    }

    @Test
    fun recordsThePhonesConditions() {
        val log = freshLog()
        log.write(ConnectionLog.Kind.NETWORK, "event" to "available")
        val line = log.export()
        assertTrue(line, line.contains("battery=") && line.contains("idle=") && line.contains("bucket="))
    }
}
