package com.spekterm.android.desktop

import com.spekterm.android.net.Folder
import com.spekterm.android.net.Operations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSpecGatesTest {
    private val all = setOf(
        Operations.OPENSPEC_CHANGES, Operations.OPENSPEC_CHANGE, Operations.OPENSPEC_SPECS, Operations.OPENSPEC_SPEC, Operations.OPENSPEC_WATCH,
    )
    private val folders = listOf(Folder("f1", "alpha", hasOpenSpec = true), Folder("f2", "beta"), Folder("f3", "gamma", hasOpenSpec = true))
    private fun group(folderId: String?, global: Boolean = false, hasOpenSpec: Boolean = true) =
        SessionsView.Group(folderId, "n", global, emptyList(), hasOpenSpec = hasOpenSpec)

    @Test
    fun withoutTheChangeListNothingIsOffered() {
        val gates = OpenSpecGates.of(all - Operations.OPENSPEC_CHANGES)
        assertFalse(gates.heading(group("f1")))
        assertFalse(gates.menu(folders))
        assertEquals(emptyList<Folder>(), gates.folders(folders))
    }

    @Test
    fun onlyMarkedFoldersAreOffered() {
        val gates = OpenSpecGates.of(all)
        assertEquals(listOf("f1", "f3"), gates.folders(folders).map { it.id })
        assertTrue(gates.heading(group("f1")))
        assertFalse(gates.heading(group("f2", hasOpenSpec = false)))
        assertTrue(gates.menu(folders))
        assertFalse(gates.menu(listOf(Folder("f2", "beta"))))
    }

    @Test
    fun theGlobalAndUnknownGroupsNeverOfferIt() {
        val gates = OpenSpecGates.of(all)
        assertFalse(gates.heading(group(null, global = true)))
        assertFalse(gates.heading(group(null)))
    }

    @Test
    fun eachReadIsGatedByItsOperation() {
        assertTrue(OpenSpecGates.of(all).let { it.specs && it.openChange && it.openSpec && it.live })
        assertFalse(OpenSpecGates.of(all - Operations.OPENSPEC_SPECS).specs)
        assertFalse(OpenSpecGates.of(all - Operations.OPENSPEC_CHANGE).openChange)
        assertFalse(OpenSpecGates.of(all - Operations.OPENSPEC_SPEC).openSpec)
        assertFalse(OpenSpecGates.of(all - Operations.OPENSPEC_WATCH).live)
    }
}
