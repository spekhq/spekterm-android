package com.spekterm.android.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** [content] in en-US whatever language the device was left in, so a test can name the English text. */
@Composable
fun English(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val config = Configuration(LocalConfiguration.current).apply { setLocale(Locale.US) }
    CompositionLocalProvider(LocalContext provides base.createConfigurationContext(config), LocalConfiguration provides config, content = content)
}
