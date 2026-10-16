package com.spekterm.android.attach

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.spekterm.android.desktop.AttachNote
import com.spekterm.android.desktop.Desktop
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Picked photos and files, prepared for the desktop (design D5) in an app-wide scope, so leaving the screen does
 * not stop it, then handed to [Desktop], which uploads them.
 */
object Attach {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun dir(context: Context): File = File(context.cacheDir, "attachments")

    /** Memory state does not survive a process, so neither do the copies (`App.onCreate`). */
    fun clearCache(context: Context) {
        scope.launch { dir(context).deleteRecursively() }
    }

    /** Adds [uris], picked for [sessionId]'s reply; [fallback] names one without a display name. */
    fun add(context: Context, sessionId: String, uris: List<Uri>, fallback: String) {
        if (uris.isEmpty()) return
        val app = context.applicationContext
        scope.launch {
            val resolver = app.contentResolver
            val names = uris.map { uri ->
                runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
            }
            val ids = Desktop.addAttachments(sessionId, names)
            val preparer = Preparer(dir(app))
            ids.forEachIndexed { i, id ->
                val result = runCatching {
                    preparer.prepare(names[i]) { resolver.openInputStream(uris[i]) ?: error("no stream") }
                }.getOrElse { Preparer.Result.Refused(AttachNote.UNREADABLE) }
                when (result) {
                    is Preparer.Result.Ready -> Desktop.prepared(id, result.file, result.name, result.size, result.converted)
                    is Preparer.Result.Refused -> Desktop.prepareFailed(id, result.note)
                }
            }
        }
    }
}
