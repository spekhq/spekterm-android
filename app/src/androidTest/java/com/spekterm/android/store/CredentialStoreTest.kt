package com.spekterm.android.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spekterm.android.net.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        positions.save(Position("e", 3))

        assertEquals(pairing, CredentialStore(context).load())
        assertEquals(Position("e", 3), positions.load())

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
        assertNull(positions.load())
    }

    @Test
    fun addressOrderPutsTheLastGoodFirst() {
        val pairing = Pairing("d", listOf("a", "b", "c"), 1, "AB".repeat(32), "i", "s", lastAddress = "b")
        assertEquals(listOf("b", "a", "c"), pairing.addressOrder())
        assertEquals(listOf("a", "b", "c"), pairing.copy(lastAddress = null).addressOrder())
        assertEquals(listOf("a", "b", "c"), pairing.copy(lastAddress = "gone").addressOrder())
    }
}
