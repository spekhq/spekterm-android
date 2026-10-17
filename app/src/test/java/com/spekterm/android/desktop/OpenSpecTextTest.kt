package com.spekterm.android.desktop

import com.spekterm.android.net.OsChange
import com.spekterm.android.net.RemoteError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenSpecTextTest {
    private fun change(slug: String) = OsChange(slug, "archived", "", null, null, null, null, null)

    @Test
    fun aVanishedChangeIsFoundUnderItsArchivedName() {
        val archived = listOf(change("2026-10-17-xy"), change("x-2"), change("2026-10-17-x"), change("2026-09-01-x"))
        assertEquals("2026-10-17-x", OpenSpecText.archivedAs("x", archived)?.slug)
        assertNull(OpenSpecText.archivedAs("x", listOf(change("2026-10-17-xy"), change("x-2"))))
        assertNull(OpenSpecText.archivedAs("2026-10-17-x", archived))
    }

    @Test
    fun aSlugIsMadeReadableAsTheDesktopDoes() {
        assertEquals("close offer", OpenSpecText.readableSlug("2026-10-16-close_offer"))
        assertEquals("add thing", OpenSpecText.readableSlug("add--thing"))
        assertEquals("ui polish", OpenSpecText.readableSlug("ui-polish"))
    }

    @Test
    fun eachErrorHasItsText() {
        val list = OpenSpecTarget.Changes("f")
        val change = OpenSpecTarget.Change("f", "x")
        val spec = OpenSpecTarget.Spec("f", "t")
        assertEquals("This folder is no longer in the desktop's workspace.", OpenSpecText.error(list, RemoteError(RemoteError.NOT_FOUND)))
        assertEquals("This folder is no longer in the desktop's workspace.", OpenSpecText.error(OpenSpecTarget.Specs("f"), RemoteError(RemoteError.NOT_FOUND)))
        assertEquals("This change is no longer in the folder.", OpenSpecText.error(change, RemoteError(RemoteError.NOT_FOUND)))
        assertEquals("This spec is no longer in the folder.", OpenSpecText.error(spec, RemoteError(RemoteError.NOT_FOUND)))
        val limit = RemoteError(RemoteError.TOO_LARGE, detail = JsonObject(mapOf("limit" to JsonPrimitive(1048576))))
        assertEquals("Too large for the phone: the desktop sends at most 1 MB.", OpenSpecText.error(list, limit))
        val half = RemoteError(RemoteError.TOO_LARGE, detail = JsonObject(mapOf("limit" to JsonPrimitive(1572864))))
        assertEquals("Too large for the phone: the desktop sends at most 1.5 MB.", OpenSpecText.error(list, half))
        assertEquals("The desktop could not read it.", OpenSpecText.error(change, RemoteError(RemoteError.INTERNAL)))
        assertEquals("This desktop does not offer it.", OpenSpecText.error(spec, RemoteError(RemoteError.UNKNOWN_OPERATION)))
        assertEquals("Not connected.", OpenSpecText.error(spec, RemoteError(Desktop.NOT_CONNECTED)))
        assertEquals("The desktop answered BAD_PARAMS.", OpenSpecText.error(spec, RemoteError(RemoteError.BAD_PARAMS)))
    }
}
