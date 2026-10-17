package com.spekterm.android.desktop

import com.spekterm.android.net.Folder
import com.spekterm.android.net.Operations
import com.spekterm.android.net.Session
import com.spekterm.android.net.SessionList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun aSessionsScreenOffersItsMarkedFolder() {
        val gates = OpenSpecGates.of(all)
        assertEquals("f1", gates.folder(Session("s1", "f1", "claude", running = true), folders)?.id)
        assertNull(gates.folder(Session("s2", "f2", "claude", running = true), folders))
        assertNull(gates.folder(Session("g1", null, "claude", running = true), folders))
        assertNull(gates.folder(Session("u1", "f9", "claude", running = true), folders))
        assertNull(gates.folder(null, folders))
        assertNull(OpenSpecGates.of(all - Operations.OPENSPEC_CHANGES).folder(Session("s1", "f1", "claude", running = true), folders))
    }

    @Test
    fun dormantAndShellSessionsOfferItToo() {
        val gates = OpenSpecGates.of(all)
        assertEquals("f1", gates.folder(Session("d1", "f1", "claude", running = false), folders)?.id)
        assertEquals("f3", gates.folder(Session("sh1", "f3", "shell", running = true), folders)?.id)
    }

    @Test
    fun aSessionsScreenAgreesWithItsGroupsHeading() {
        val list = SessionList(
            window = true,
            folders = folders,
            sessions = listOf(
                Session("g1", null, "claude", running = true),
                Session("a1", "f1", "claude", running = true),
                Session("a2", "f1", "shell", running = false),
                Session("b1", "f2", "claude", running = true),
                Session("c1", "f3", "claude", running = false),
                Session("u1", "f9", "claude", running = true),
            ),
        )
        for (operations in listOf(all, all - Operations.OPENSPEC_CHANGES)) {
            val gates = OpenSpecGates.of(operations)
            for (group in SessionsView.groups(list)) {
                for (row in group.rows) {
                    assertEquals(row.session.id, gates.heading(group), gates.folder(row.session, list.folders) != null)
                }
            }
        }
    }
}
