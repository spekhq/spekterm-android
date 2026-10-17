package com.spekterm.android.desktop

/** What an OpenSpec screen reads (`openspec-browse` design D3). The folder list has none: it is the session list's. */
sealed interface OpenSpecTarget {
    val folderId: String

    data class Changes(override val folderId: String) : OpenSpecTarget

    data class Specs(override val folderId: String) : OpenSpecTarget

    data class Change(override val folderId: String, val slug: String) : OpenSpecTarget

    data class Spec(override val folderId: String, val topic: String) : OpenSpecTarget
}

/**
 * Watch, then read (design D3): which folder the desktop watches for this connection, and when the focused target
 * is read. Pure; every input returns the next state and what to send now ([Step]). `Desktop` sends it and reports
 * the answers back.
 *
 * The desktop answers requests concurrently, records a watch only once the folder's scan is done and a stop at once,
 * so a watch in flight can undo a later stop or watch. Hence at most one watch in flight, re-checked after every
 * answer, and a read only once its folder's watch is settled.
 */
data class OpenSpecTracker(
    val attached: Boolean = false,
    /** `openspec.watch` is listed on this connection. */
    val live: Boolean = false,
    val focus: Focus? = null,
    /** The folder the phone wants watched; `null` for none. */
    val wanted: String? = null,
    /** The folder the desktop watches for this connection, by its last watch answer. */
    val confirmed: String? = null,
    /** Folders whose watch was refused on this connection; emptied by refresh and by every attach. */
    val refused: Set<String> = emptySet(),
    val watching: Watch? = null,
    val reading: OpenSpecTarget? = null,
    /** The focused target needs a read. Cleared when the read is sent. */
    val stale: Boolean = false,
) {
    data class Focus(val owner: String, val target: OpenSpecTarget)

    /** A watch of [folderId]; `null` stops watching. */
    data class Watch(val folderId: String?)

    /** The state after an input, and what to send now. */
    data class Step(val state: OpenSpecTracker, val watch: Watch? = null, val read: OpenSpecTarget? = null)

    /** From when [target] becomes stale until its read answers, including the wait for its watch. */
    fun pending(target: OpenSpecTarget): Boolean = attached && focus?.target == target && (stale || reading == target)

    /** A live desktop whose watch of [target]'s folder was refused: the screen does not update by itself. */
    fun notLive(target: OpenSpecTarget): Boolean = attached && live && target.folderId in refused

    fun focus(owner: String, target: OpenSpecTarget): Step =
        copy(focus = Focus(owner, target), wanted = target.folderId, stale = true).settle()

    /** Ignored unless [owner] holds the focus. `wanted` stays until [idle], after the linger. */
    fun unfocus(owner: String): Step =
        if (focus?.owner != owner) Step(this) else copy(focus = null, stale = false).settle()

    fun idle(): Step = if (focus != null) Step(this) else copy(wanted = null).settle()

    /** A new connection: nothing of the last one's watch is known. */
    fun attached(live: Boolean): Step = copy(
        attached = true,
        live = live,
        wanted = focus?.target?.folderId,
        confirmed = null,
        refused = emptySet(),
        watching = null,
        reading = null,
        stale = focus != null,
    ).settle()

    fun detached(): Step = Step(copy(attached = false, watching = null, reading = null))

    /** The answer to the watch in flight: [ok] unless the desktop refused it. */
    fun watchAnswered(ok: Boolean): Step {
        val w = watching ?: return Step(this)
        val folder = w.folderId
        val next = when {
            // A stop never fails on the desktop: whatever the answer, nothing is watched.
            folder == null -> copy(confirmed = null)
            ok -> copy(confirmed = folder, stale = stale || focus?.target?.folderId == folder)
            // The desktop keeps the previous watch.
            else -> copy(refused = refused + folder)
        }
        return next.copy(watching = null).settle()
    }

    fun readAnswered(): Step = copy(reading = null).settle()

    /** `openspec.changed`: only the folder the desktop watches for us, shown now. */
    fun changed(folderId: String): Step =
        if (attached && folderId == confirmed && focus?.target?.folderId == folderId) copy(stale = true).settle() else Step(this)

    /** A manual refresh: also tries again a folder whose watch was refused. */
    fun refresh(): Step = if (!attached) Step(this) else copy(refused = emptySet(), stale = stale || focus != null).settle()

    private fun settle(): Step {
        if (!attached) return Step(this)
        var s = this
        var watch: Watch? = null
        var read: OpenSpecTarget? = null
        if (live && watching == null) {
            val folder = wanted?.takeIf { it !in refused }
            if (folder != confirmed) {
                watch = Watch(folder)
                s = s.copy(watching = watch)
            }
        }
        val f = focus
        if (f != null && s.stale && s.reading == null) {
            val folder = f.target.folderId
            if (!live || folder in s.refused || (s.watching == null && s.confirmed == folder)) {
                read = f.target
                s = s.copy(reading = read, stale = false)
            }
        }
        return Step(s, watch, read)
    }
}
