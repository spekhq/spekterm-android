package com.spekterm.android.desktop

import com.spekterm.android.R
import com.spekterm.android.ui.UiText
import com.spekterm.android.net.Folder
import com.spekterm.android.net.Lineage
import com.spekterm.android.net.Session
import com.spekterm.android.net.SessionList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationsTest {
    private val folders = listOf(Folder("f1", "alpha"), Folder("f2", "beta"))

    private fun s(id: String, folder: String? = "f1", parent: String? = null, title: String? = null, label: String = id, lifecycle: String? = null, running: Boolean = true) =
        Session(id, folder, "claude", label = label, running = running, lifecycle = lifecycle, lineage = parent?.let { Lineage(it, title) })

    private fun list(vararg sessions: Session) = SessionList(true, folders, sessions.toList())

    @Test
    fun existingIsInTheListAndInANamedGroup() {
        val l = list(s("a"), s("g", null), s("x", "gone"), s("d", running = false))
        assertEquals(setOf("a", "g", "d"), Relations.existing(l))
    }

    @Test
    fun theSourceDescription() {
        val l = list(s("p", "f1", label = "planner"), s("c", "f2", "p"), s("g", null, label = "lead"), s("cg", "f2", "g"))
        assertEquals(UiText.Res(R.string.relations_from, UiText.Raw("alpha"), "planner"), Relations.sourceText(l.sessions[1], l))
        assertEquals(UiText.Res(R.string.relations_from, UiText.Res(R.string.sessions_global), "lead"), Relations.sourceText(l.sessions[3], l))
        assertEquals(UiText.Res(R.string.relations_from_closed, "planner"), Relations.sourceText(s("c", parent = "gone", title = "planner"), l))
        assertEquals(UiText.Res(R.string.relations_from_gone), Relations.sourceText(s("c", parent = "gone", title = " "), l))
        assertEquals(UiText.Res(R.string.relations_from_gone), Relations.sourceText(s("c", parent = "gone"), l))
        assertNull(Relations.sourceText(s("c"), l))
    }

    @Test
    fun aSourceInAnUnnamedFolderIsClosedAndADormantOneExists() {
        val l = list(s("p", "gone", label = "old"), s("c", parent = "p", title = "old"), s("d", running = false, label = "sleepy"), s("c2", parent = "d"))
        assertEquals(UiText.Res(R.string.relations_from_closed, "old"), Relations.sourceText(l.sessions[1], l))
        assertEquals(UiText.Res(R.string.relations_from, UiText.Raw("alpha"), "sleepy"), Relations.sourceText(l.sessions[3], l))
    }

    @Test
    fun aRenamedSourceReadsItsNewLabel() {
        val l = list(s("p", label = "renamed"), s("c", "f2", "p", title = "original"))
        assertEquals(UiText.Res(R.string.relations_from, UiText.Raw("alpha"), "renamed"), Relations.sourceText(l.sessions[1], l))
    }

    @Test
    fun theRowLine() {
        val l = list(s("p", label = "planner"), s("c", "f2", "p", lifecycle = "working"))
        assertEquals(UiText.Joined(listOf(UiText.Res(R.string.relations_from, UiText.Raw("alpha"), "planner"), UiText.Res(R.string.relations_state_working))), Relations.line(l.sessions[1], nested = false, list = l))
        assertEquals(UiText.Joined(listOf(UiText.Res(R.string.relations_handoff), UiText.Res(R.string.relations_state_working))), Relations.line(l.sessions[1], nested = true, list = l))
        assertEquals(UiText.Joined(listOf(UiText.Res(R.string.relations_handoff), UiText.Res(R.string.relations_state_waiting))), Relations.line(s("c", parent = "p", lifecycle = "waiting"), true, l))
        assertEquals(UiText.Joined(listOf(UiText.Res(R.string.relations_handoff), UiText.Res(R.string.relations_state_done))), Relations.line(s("c", parent = "p", lifecycle = "done"), true, l))
        assertEquals(UiText.Joined(listOf(UiText.Res(R.string.relations_handoff))), Relations.line(s("c", parent = "p", lifecycle = "idle"), true, l))
        assertEquals(UiText.Joined(listOf(UiText.Res(R.string.relations_handoff))), Relations.line(s("c", parent = "p", lifecycle = "someday"), true, l))
        assertEquals(UiText.Joined(listOf(UiText.Res(R.string.relations_handoff))), Relations.line(s("c", parent = "p"), true, l))
        assertNull(Relations.line(s("p"), false, l))
    }

    @Test
    fun aNestedRowsSourceDescriptionStillNamesItsSource() {
        val l = list(s("p", label = "planner"), s("c", parent = "p"))
        assertEquals(UiText.Res(R.string.relations_from, UiText.Raw("alpha"), "planner"), Relations.sourceText(l.sessions[1], l))
    }

    @Test
    fun theChildrenLineCountsExistingChildrenAcrossGroups() {
        val p = s("p")
        assertEquals(UiText.Plural(R.plurals.relations_handed_off, 1), Relations.childrenText(p, list(p, s("c", "f2", "p"))))
        assertEquals(UiText.Joined(listOf(UiText.Plural(R.plurals.relations_handed_off, 2), UiText.Res(R.string.relations_done_of, 1, 2))), Relations.childrenText(p, list(p, s("c1", "f2", "p", lifecycle = "done"), s("c2", null, "p", lifecycle = "working"))))
        assertEquals(UiText.Joined(listOf(UiText.Plural(R.plurals.relations_handed_off, 2), UiText.Res(R.string.relations_all_done))), Relations.childrenText(p, list(p, s("c1", parent = "p", lifecycle = "done"), s("c2", parent = "p", lifecycle = "done"))))
        assertEquals(UiText.Plural(R.plurals.relations_handed_off, 1), Relations.childrenText(p, list(p, s("c1", parent = "p"), s("c2", "gone", "p"))))
        assertNull(Relations.childrenText(p, list(p, s("other"))))
    }

    @Test
    fun aRowInAnUnnamedFolderGetsNoChildrenLine() {
        val p = s("p", "gone")
        val l = list(p, s("c", "f1", "p"))
        assertNull(Relations.childrenText(p, l))
        assertFalse(Relations.related(p, l))
        assertTrue(Relations.related(l.sessions[1], l))
    }
}
