package com.spekterm.android.desktop

import com.spekterm.android.net.Folder
import com.spekterm.android.net.Operations
import com.spekterm.android.net.Session

/**
 * What the session list, a session's screen and the OpenSpec screens may offer, by the operations the desktop listed (`openspec-browse`
 * design D2; obligation 8).
 */
data class OpenSpecGates(
    val browse: Boolean,
    val specs: Boolean,
    val openChange: Boolean,
    val openSpec: Boolean,
    val live: Boolean,
) {
    /** The folders whose OpenSpec can be browsed, in the desktop's order. */
    fun folders(folders: List<Folder>): List<Folder> = if (browse) folders.filter { it.hasOpenSpec } else emptyList()

    /** The OpenSpec control in a group's heading: a folder group marked as having OpenSpec. */
    fun heading(group: SessionsView.Group): Boolean = browse && !group.global && group.folderId != null && group.hasOpenSpec

    /**
     * The OpenSpec control on [session]'s screen (`session-detail-openspec` D2): its folder, when that is among
     * [folders] and has OpenSpec; the same folders the heading's control shows for.
     */
    fun folder(session: Session?, folders: List<Folder>): Folder? =
        session?.folderId?.takeIf { browse }?.let { id -> folders.firstOrNull { it.id == id } }?.takeIf { it.hasOpenSpec }

    /** The session list menu's "OpenSpec": at least one folder has it. */
    fun menu(folders: List<Folder>): Boolean = folders(folders).isNotEmpty()

    companion object {
        fun of(operations: Set<String>): OpenSpecGates = OpenSpecGates(
            browse = Operations.OPENSPEC_CHANGES in operations,
            specs = Operations.OPENSPEC_SPECS in operations,
            openChange = Operations.OPENSPEC_CHANGE in operations,
            openSpec = Operations.OPENSPEC_SPEC in operations,
            live = Operations.OPENSPEC_WATCH in operations,
        )
    }
}
