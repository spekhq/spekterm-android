package com.spekterm.android.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration

/**
 * A text built outside composition (`scan-connect-zh` D8): a string resource with its arguments, a plural, or what
 * the desktop sent, shown as sent. Resolved where it is shown, so it follows the app's language; tests compare the
 * values themselves. Arguments that are [UiText] are resolved first.
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
    }

    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    data class Raw(val text: String) : UiText

    /** Parts shown in one line, each in the language, joined by [separator] (which holds no words). */
    data class Joined(val parts: List<UiText>, val separator: String = " · ") : UiText

    fun resolve(context: Context): String = when (this) {
        is Res -> context.getString(id, *args.map { it.resolved(context) }.toTypedArray())
        is Plural -> context.resources.getQuantityString(id, count, *args.map { it.resolved(context) }.toTypedArray())
        is Raw -> text
        is Joined -> parts.joinToString(separator) { it.resolve(context) }
    }
}

private fun Any.resolved(context: Context): Any = if (this is UiText) resolve(context) else this

/** The text in the app's current language; recomposes when the configuration changes. */
@Composable
fun UiText.text(): String {
    LocalConfiguration.current
    return resolve(LocalContext.current)
}
