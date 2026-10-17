package com.spekterm.android.desktop

import com.spekterm.android.net.Folder
import com.spekterm.android.net.Lineage
import com.spekterm.android.net.Session
import com.spekterm.android.net.SessionList
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTreeTest {
    private fun s(id: String, parent: String? = null, folder: String? = "f1") =
        Session(id, folder, "claude", lineage = parent?.let { Lineage(it) })

    private fun tree(vararg sessions: Session, present: Set<String> = sessions.map { it.id }.toSet()) =
        Relations.tree(sessions.toList(), present).map { Triple(it.id, it.depth, it.nested) }

    private fun assertEachOnce(input: List<Session>, placed: List<Triple<String, Int, Boolean>>) =
        assertEquals(input.map { it.id }.sorted(), placed.map { it.first }.sorted())

    @Test
    fun noLineageKeepsTheOrderAtTheTop() {
        assertEquals(listOf(Triple("a", 0, false), Triple("b", 0, false)), tree(s("a"), s("b")))
    }

    @Test
    fun aChildListedBeforeItsParentGoesUnderIt() {
        assertEquals(listOf(Triple("p", 0, false), Triple("c", 1, true)), tree(s("c", "p"), s("p")))
    }

    @Test
    fun twoChildrenKeepTheDesktopsOrder() {
        assertEquals(
            listOf(Triple("p", 0, false), Triple("c2", 1, true), Triple("c1", 1, true), Triple("x", 0, false)),
            tree(s("p"), s("c2", "p"), s("x"), s("c1", "p")),
        )
    }

    @Test
    fun aParentNotPresentOrMissingOrItselfMakesARoot() {
        assertEquals(listOf(Triple("c", 0, false)), tree(s("c", "elsewhere")))
        assertEquals(listOf(Triple("p", 0, false), Triple("c", 0, false)), tree(s("p"), s("c", "p"), present = setOf("c")))
        assertEquals(listOf(Triple("me", 0, false)), tree(s("me", "me")))
    }

    @Test
    fun loopsAndTailsIntoLoopsAreRoots() {
        val input = listOf(s("a", "b"), s("b", "a"), s("t", "a"))
        val placed = tree(*input.toTypedArray())
        assertEquals(listOf(Triple("a", 0, false), Triple("b", 0, false), Triple("t", 0, false)), placed)
        assertEachOnce(input, placed)
    }

    @Test
    fun aFiveDeepChainStopsIndentingAtThree() {
        val input = listOf(s("1"), s("2", "1"), s("3", "2"), s("4", "3"), s("5", "4"))
        assertEquals(listOf(0, 1, 2, 3, 3), tree(*input.toTypedArray()).map { it.second })
    }

    @Test
    fun aParentInAnotherGroupIsARootInTheList() {
        val list = SessionList(true, listOf(Folder("f1", "alpha"), Folder("f2", "beta")), listOf(s("p", folder = "f1"), s("c", "p", "f2")))
        val groups = SessionsView.groups(list)
        assertEquals(listOf(0, 0), groups.map { it.rows.single().depth })
    }

    @Test
    fun otherFoldersBuildNoTree() {
        val list = SessionList(true, emptyList(), listOf(s("p", folder = "gone"), s("c", "p", "gone")))
        val rows = SessionsView.groups(list).single().rows
        assertEquals(listOf("p" to 0, "c" to 0), rows.map { it.session.id to it.depth })
        assertEquals(listOf(false, false), rows.map { it.nested })
    }

    @Test
    fun aGroupIsArrangedAsATree() {
        val list = SessionList(true, listOf(Folder("f1", "alpha")), listOf(s("c", "p"), s("x"), s("p")))
        assertEquals(listOf("x" to 0, "p" to 0, "c" to 1), SessionsView.groups(list).single().rows.map { it.session.id to it.depth })
    }
}
