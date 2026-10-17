package com.spekterm.android.desktop

import com.spekterm.android.net.OsChange
import com.spekterm.android.net.RemoteError
import java.util.Locale

/** Pure helpers of the OpenSpec screens (`openspec-browse` design D4–D6). */
object OpenSpecText {
    private val DATED = Regex("""^(\d{4}-\d{2}-\d{2})-(.+)$""")

    /** Whether [slug] carries an archive's date prefix. */
    fun dated(slug: String): Boolean = DATED.matches(slug)

    /**
     * The change a vanished active [slug] was archived as (design D5): the first of [archived], in the desktop's
     * order, whose slug is `<YYYY-MM-DD>-<slug>`. A dated [slug] is archived already and finds nothing.
     */
    fun archivedAs(slug: String, archived: List<OsChange>): OsChange? {
        if (dated(slug)) return null
        return archived.firstOrNull { DATED.matchEntire(it.slug)?.groupValues?.get(2) == slug }
    }

    /** A slug made readable as the desktop's core does (`parseSlug`): no date prefix, runs of `-` and `_` as spaces. */
    fun readableSlug(slug: String): String {
        val rest = DATED.matchEntire(slug)?.groupValues?.get(2) ?: slug
        return rest.replace(Regex("[-_]+"), " ").trim()
    }

    /** What a failed read of [target] says (design D4). */
    fun error(target: OpenSpecTarget, error: RemoteError): String = when (error.code) {
        RemoteError.NOT_FOUND -> when (target) {
            is OpenSpecTarget.Changes, is OpenSpecTarget.Specs -> "This folder is no longer in the desktop's workspace."
            is OpenSpecTarget.Change -> "This change is no longer in the folder."
            is OpenSpecTarget.Spec -> "This spec is no longer in the folder."
        }
        RemoteError.TOO_LARGE -> "Too large for the phone: the desktop sends at most ${error.detailLong("limit")?.let(::megabytes) ?: "1 MB"}."
        RemoteError.INTERNAL -> "The desktop could not read it."
        RemoteError.UNKNOWN_OPERATION -> "This desktop does not offer it."
        Desktop.NOT_CONNECTED -> "Not connected."
        else -> "The desktop answered ${error.code}."
    }

    private fun megabytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024)
        return if (mb == Math.floor(mb)) "${mb.toLong()} MB" else String.format(Locale.ROOT, "%.1f MB", mb)
    }
}
