package com.spekterm.android.ui

import java.util.Locale

/** A size in bytes as the app shows it: an attachment's, a piece of OpenSpec content the desktop left out. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
