package com.spekterm.android.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The appearance choice is kept, and anything it cannot read is the phone's setting (`app-appearance`). */
@RunWith(AndroidJUnit4::class)
class ThemeStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ThemeStore(context)
    private val file = File(context.noBackupFilesDir, "theme.json")
    private var before: ThemeChoice? = null

    @Before
    fun keep() {
        before = if (file.exists()) store.load() else null
        store.delete()
    }

    @After
    fun restore() {
        before?.let { store.save(it) } ?: store.delete()
    }

    @Test
    fun defaultsToTheSystemSetting() = assertEquals(ThemeChoice.SYSTEM, store.load())

    @Test
    fun eachChoiceReadsBack() {
        for (choice in ThemeChoice.entries) {
            store.save(choice)
            assertEquals(choice, ThemeStore(context).load())
        }
    }

    @Test
    fun unreadableOrUnknownIsTheSystemSetting() {
        file.writeText("not json")
        assertEquals(ThemeChoice.SYSTEM, store.load())
        file.writeText("""{"choice":"SEPIA"}""")
        assertEquals(ThemeChoice.SYSTEM, store.load())
    }
}
