package com.spekterm.android.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The quick replies are kept on the phone; a damaged file is not set (`quick-replies` D2). */
@RunWith(AndroidJUnit4::class)
class QuickReplyStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = QuickReplyStore(context)
    private val file = File(context.noBackupFilesDir, "quick-replies.json")
    private var before: ByteArray? = null

    @Before
    fun keep() {
        before = if (file.exists()) file.readBytes() else null
        store.reset()
    }

    @After
    fun restore() {
        before?.let { file.writeBytes(it) } ?: store.reset()
    }

    @Test
    fun notSetAtFirst() = assertNull(store.load())

    @Test
    fun aSavedListReadsBackTrimmed() {
        store.save(listOf(" Go ahead if there is no risk ", "/model opus"))
        assertEquals(listOf("Go ahead if there is no risk", "/model opus"), QuickReplyStore(context).load())
        store.save(emptyList())
        assertEquals(emptyList<String>(), QuickReplyStore(context).load())
    }

    @Test
    fun resetIsNotSet() {
        store.save(listOf("a"))
        store.reset()
        assertNull(store.load())
    }

    @Test
    fun aDamagedFileIsNotSet() {
        file.writeText("not json")
        assertNull(store.load())
        file.writeText("""{"replies":["same","same"]}""")
        assertNull(store.load())
    }
}
