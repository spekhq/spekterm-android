package com.spekterm.android.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollapsedStoreTest {
    private val store = CollapsedStore(InstrumentationRegistry.getInstrumentation().targetContext)

    @After
    fun tearDown() {
        store.delete()
    }

    @Test
    fun roundTripsPerPairingAndDeletes() {
        store.delete()
        assertEquals(emptySet<String>(), store.load("dev-1"))

        store.save("dev-1", setOf("global", "folder:f1", "folder:gone"))
        assertEquals(setOf("global", "folder:f1", "folder:gone"), CollapsedStore(InstrumentationRegistry.getInstrumentation().targetContext).load("dev-1"))
        // Another pairing never reads this one's groups.
        assertEquals(emptySet<String>(), store.load("dev-2"))

        store.delete()
        assertEquals(emptySet<String>(), store.load("dev-1"))
    }
}
