package com.spekterm.android.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spekterm.android.net.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val secret = "SECRET-${System.nanoTime()}-do-not-store-in-plain-text"

    @Test
    fun roundTripsWithoutThePlainSecretOnDiskAndDeletes() {
        val store = CredentialStore(context)
        val positions = PositionStore(context)
        val pairing = Pairing("desk", listOf("192.168.50.22", "100.106.81.33"), 47615, "AB".repeat(32), "dev-1", secret, "100.106.81.33")
        store.save(pairing)
        positions.save("dev-1", Position("e", 3))

        assertEquals(pairing, CredentialStore(context).load())
        assertEquals(Position("e", 3), positions.load("dev-1"))
        // A position written for another pairing is never read as this one's.
        assertNull(positions.load("dev-2"))

        // No file anywhere under the app's data directory holds the secret's bytes.
        val needle = secret.encodeToByteArray()
        val offenders = context.dataDir.walkTopDown().filter { it.isFile && it.canRead() }.filter { file ->
            val bytes = file.readBytes()
            bytes.size >= needle.size && (0..bytes.size - needle.size).any { i -> needle.indices.all { bytes[i + it] == needle[it] } }
        }.toList()
        assertEquals(emptyList<java.io.File>(), offenders)
        assertFalse(pairing.toString().contains(secret))

        store.delete()
        positions.delete()
        assertNull(store.load())
        assertNull(positions.load("dev-1"))
    }

    @Test
    fun conditionalWritesTouchOnlyTheSamePairing() {
        val store = CredentialStore(context)
        val old = Pairing("desk", listOf("a"), 1, "AB".repeat(32), "old", "s1")
        val new = old.copy(deviceId = "new", deviceSecret = "s2")
        store.save(new)
        // A stopping service of the old pairing must not overwrite or delete the new one.
        store.updateIf("old") { it.copy(lastAddress = "a") }
        assertEquals(new, store.load())
        assertFalse(store.deleteIf("old"))
        assertEquals(new, store.load())
        store.updateIf("new") { it.copy(lastAddress = "a") }
        assertEquals("a", store.load()!!.lastAddress)
        assertTrue(store.deleteIf("new"))
        assertNull(store.load())
    }

    @Test
    fun addressOrderPutsTheLastGoodFirst() {
        val pairing = Pairing("d", listOf("a", "b", "c"), 1, "AB".repeat(32), "i", "s", lastAddress = "b")
        assertEquals(listOf("b", "a", "c"), pairing.addressOrder())
        assertEquals(listOf("a", "b", "c"), pairing.copy(lastAddress = null).addressOrder())
        assertEquals(listOf("a", "b", "c"), pairing.copy(lastAddress = "gone").addressOrder())
    }
}
