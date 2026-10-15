package com.spekterm.android.store

import com.spekterm.android.net.Frames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PairingTest {
    private val link = Pairing("desk", listOf("a", "b", "c"), 47615, "AB".repeat(32), "dev", "s")

    @Test
    fun withNoLatestListTheLastGoodComesFirstThenTheLink() {
        assertEquals(listOf("a", "b", "c"), link.addressOrder())
        assertEquals(listOf("b", "a", "c"), link.copy(lastAddress = "b").addressOrder())
        // Outside the link (learned from an earlier desktop list): still first, never dropped.
        assertEquals(listOf("x", "a", "b", "c"), link.copy(lastAddress = "x").addressOrder())
    }

    @Test
    fun aLatestListHoldingTheLastGoodPutsItFirstThenTheLatestThenTheLink() {
        val p = link.copy(lastAddress = "y", latestAddresses = listOf("x", "y", "b"))
        assertEquals(listOf("y", "x", "b", "a", "c"), p.addressOrder())
    }

    @Test
    fun aLatestListWithoutTheLastGoodComesFirst() {
        // The last good one is in the link: it keeps its place there.
        assertEquals(listOf("x", "a", "b", "c"), link.copy(lastAddress = "b", latestAddresses = listOf("x")).addressOrder())
        // In neither list: tried last.
        assertEquals(listOf("x", "a", "b", "c", "old"), link.copy(lastAddress = "old", latestAddresses = listOf("x")).addressOrder())
    }

    @Test
    fun anEmptyLatestListLeavesTheOrderToTheLastGoodAndTheLink() {
        val p = link.copy(lastAddress = "b", latestAddresses = listOf("x")).withLatest(emptyList())
        assertEquals(emptyList<String>(), p.latestAddresses)
        assertEquals(listOf("b", "a", "c"), p.addressOrder())
    }

    @Test
    fun noListKeepsTheStoredOne() {
        val p = link.copy(latestAddresses = listOf("x"))
        assertSame(p, p.withLatest(null))
        assertEquals(p.copy(lastAddress = "a"), p.authenticated("a", null))
        assertEquals(p.copy(lastAddress = "a", latestAddresses = listOf("y")), p.authenticated("a", listOf("y")))
    }

    @Test
    fun aBracketedAddressFromTheDesktopIsTheLinksAddress() {
        val p = Pairing("desk", listOf("fd7a::1"), 1, "AB".repeat(32), "dev", "s")
            .withLatest(Frames.addressList(listOf("[fd7a::1]", "192.168.1.5")))
        assertEquals(listOf("fd7a::1", "192.168.1.5"), p.addressOrder())
    }

    @Test
    fun aStoredPairingWithoutTheFieldReadsAsHavingNoLatestList() {
        val stored = """{"desktopName":"desk","addresses":["a"],"port":1,"fingerprint":"ff","deviceId":"d","deviceSecret":"s","lastAddress":"a"}"""
        val p = Frames.json.decodeFromString(Pairing.serializer(), stored)
        assertEquals(emptyList<String>(), p.latestAddresses)
        assertEquals(listOf("a"), p.addressOrder())
    }
}
